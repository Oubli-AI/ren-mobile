# /// script
# requires-python = ">=3.12"
# dependencies = ["fastmcp==3.4.0", "mcp==1.26.0", "websockets==15.0.1"]
# ///
"""The user's own computer on Ren's hub: Desktop Commander, the tools of the phone's work mode.

Run it on the computer (it needs uv and Node): ``REN_HUB_URL=wss://<host>/hub REN_HUB_TOKEN=<the hub's token> uv run
deploy/local/computer.py``. It runs Desktop Commander over stdio, the version the workspace VM runs, with this user's
own environment, and serves it through FastMCP's proxy on the hub's ``/attach/local`` socket, the way the phone
attaches as ``phone``: the computer is behind a home router, so it dials out. The hub lists its tools under ``local_``
for every agent (``deploy/vm/hub/app.py``). It dials again after every drop, waiting longer after each failure.

Every Desktop Commander schema carries ``$schema`` and ``additionalProperties``, which Gemini Live refuses (1007), so
the agents' Live filter would drop every tool from voice; ``LiveSchemas`` serves the same schemas without those two
keys, so the voice agent gets the same tools as the text one.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from collections.abc import Sequence

import anyio
import mcp.types
from fastmcp import Client, FastMCP
from fastmcp.server.providers.proxy import ProxyProvider
from fastmcp.server.transforms import GetToolNext, Transform
from fastmcp.tools.base import Tool
from fastmcp.utilities.versions import VersionSpec
from mcp.shared.message import SessionMessage
from pydantic import ValidationError
from websockets.asyncio.client import connect
from websockets.typing import Subprotocol

#: The Desktop Commander the workspace VM serves (``deploy/vm/computer``).
DESKTOP_COMMANDER = "@wonderwhy-er/desktop-commander@0.3.1"
#: The keys Gemini Live refuses in a tool's input schema.
LIVE_REFUSED = frozenset({"$schema", "additionalProperties"})
FIRST_RETRY_SECONDS = 2.0
LAST_RETRY_SECONDS = 60.0

logger = logging.getLogger("ren-local")


def without_refused(schema: object) -> object:
    if isinstance(schema, dict):
        return {key: without_refused(value) for key, value in schema.items() if key not in LIVE_REFUSED}
    if isinstance(schema, list):
        return [without_refused(value) for value in schema]
    return schema


class LiveSchemas(Transform):
    """Each tool with its input schema minus the keys Gemini Live refuses."""

    async def list_tools(self, tools: Sequence[Tool]) -> Sequence[Tool]:
        return [tool.model_copy(update={"parameters": without_refused(tool.parameters)}) for tool in tools]

    async def get_tool(self, name: str, call_next: GetToolNext, *, version: VersionSpec | None = None) -> Tool | None:
        tool = await call_next(name, version=version)
        return None if tool is None else tool.model_copy(update={"parameters": without_refused(tool.parameters)})


async def serve_on_socket(server: FastMCP[None], url: str, token: str) -> None:
    """Dial [url] and serve [server] on that WebSocket until it closes: the dialed end is the MCP server.

    The MCP SDK's WebSocket client transport can't send the bearer token Caddy asks for, so this is that transport with
    the header: JSON-RPC messages as text frames under the ``mcp`` subprotocol.
    """
    headers = {"Authorization": f"Bearer {token}"}
    async with connect(url, subprotocols=[Subprotocol("mcp")], additional_headers=headers) as socket:
        to_server, server_reads = anyio.create_memory_object_stream[SessionMessage | Exception](0)
        server_writes, from_server = anyio.create_memory_object_stream[SessionMessage](0)

        async def reader() -> None:
            async with to_server:
                async for raw in socket:
                    try:
                        await to_server.send(SessionMessage(mcp.types.JSONRPCMessage.model_validate_json(raw)))
                    except ValidationError as error:
                        await to_server.send(error)

        async def writer() -> None:
            async with from_server:
                async for outgoing in from_server:
                    await socket.send(json.dumps(outgoing.message.model_dump(by_alias=True, mode="json", exclude_none=True)))

        async with anyio.create_task_group() as tasks:
            tasks.start_soon(reader)
            tasks.start_soon(writer)
            logger.info("attached to the hub at %s", url)
            low_level = server._mcp_server
            await low_level.run(server_reads, server_writes, low_level.create_initialization_options())
            tasks.cancel_scope.cancel()


async def main() -> None:
    url = os.environ["REN_HUB_URL"].rstrip("/") + "/attach/local"
    token = os.environ["REN_HUB_TOKEN"]
    # Desktop Commander's terminal is this user's, with their environment, less the hub's token.
    environment = {name: value for name, value in os.environ.items() if not name.startswith("REN_HUB_")}
    servers = {"desktop-commander": {"command": "npx", "args": ["-y", DESKTOP_COMMANDER], "env": environment}}
    async with Client({"mcpServers": servers}) as connected:
        computer = ProxyProvider(lambda: connected, cache_ttl=0)
        computer.add_transform(LiveSchemas())
        server: FastMCP[None] = FastMCP("ren-local", instructions="Desktop Commander on the user's own computer.")
        server.add_provider(computer)
        wait = FIRST_RETRY_SECONDS
        while True:
            try:
                await serve_on_socket(server, url, token)
                wait = FIRST_RETRY_SECONDS
                logger.info("the hub closed the link; dialing again")
            except Exception as error:  # noqa: BLE001 - every failure to reach the hub is retried
                logger.warning("could not attach to the hub (%s: %s); retrying in %.0fs", type(error).__name__, error, wait)
            await asyncio.sleep(wait)
            wait = min(wait * 2, LAST_RETRY_SECONDS)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="[ren-local] %(message)s")
    asyncio.run(main())
