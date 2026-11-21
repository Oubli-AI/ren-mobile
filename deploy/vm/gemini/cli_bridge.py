"""The MCP server `ren` that a CLI agent (`cli_models.py`) starts over stdio: the agent's own tools, relayed to the gemini
service at the address it is given (`/bridge/<token>`).

It is stdio, not the service's own HTTP, because a call waits for as long as Strands takes to run the tool, and Claude
Code ends an HTTP MCP request after 60 seconds ("For an HTTP, SSE, or claude.ai connector server, each request also
times out after 60 seconds by default"; stdio servers have no per-request timer, https://code.claude.com/docs/en/env-vars).

    python cli_bridge.py http://127.0.0.1:8100/bridge/<token>
"""

import sys

import anyio
import httpx
from mcp import types
from mcp.server.context import ServerRequestContext
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server

from cli_models import BridgeResult, BridgeTool

URL = sys.argv[1]
client = httpx.AsyncClient(timeout=None)


async def list_tools(ctx: ServerRequestContext[object, object], params: types.PaginatedRequestParams | None) -> types.ListToolsResult:
    response = await client.get(f"{URL}/tools")
    response.raise_for_status()
    tools = [BridgeTool.model_validate(item) for item in response.json()]
    return types.ListToolsResult(tools=[types.Tool(name=tool.name, description=tool.description, input_schema=tool.input_schema) for tool in tools])


async def call_tool(ctx: ServerRequestContext[object, object], params: types.CallToolRequestParams) -> types.CallToolResult:
    response = await client.post(f"{URL}/call", json={"name": params.name, "arguments": params.arguments or {}})
    response.raise_for_status()
    result = BridgeResult.model_validate(response.json())
    content: list[types.ContentBlock] = [
        types.TextContent(text=block.text) if block.type == "text" else types.ImageContent(data=block.data, mime_type=block.mime_type)
        for block in result.content
    ]
    return types.CallToolResult(content=content, is_error=result.is_error)


async def main() -> None:
    server: Server[object] = Server("ren", on_list_tools=list_tools, on_call_tool=call_tool)
    async with stdio_server() as (read, write):
        await server.run(read, write, server.create_initialization_options())


if __name__ == "__main__":
    anyio.run(main)
