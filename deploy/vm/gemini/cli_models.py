"""Claude Code and Antigravity CLI as Strands model providers, so each one is a text agent `create_harness` makes.

Each is a custom model provider (Strands' docs, "Custom Model Providers"): a `Model` whose `stream` drives the CLI (Claude
Code through Anthropic's Claude Agent SDK, https://code.claude.com/docs/en/agent-sdk/python; Antigravity as an Agent
Client Protocol server, https://antigravity.google/docs/ide/extensions/acp) and yields Strands' stream events.

- The CLI keeps the conversation itself (its session, resumed by id), so the model is stateful: Strands sets
  `capabilities.stateful` "only for a provider that keeps state server-side" (`strands/models/model.py`), sends only the
  new turn, and saves the CLI's ids in `model_state` with the session, as it saves OpenAIResponsesModel's `response_id`.
- The CLI's native tools (Claude Code's Bash, Read, Edit, WebSearch, Agent and the rest; Antigravity's run_command,
  write_to_file and the rest) run inside the CLI, as a provider's server-side tools run at the provider. Strands gives
  those no toolUse/toolResult ("They have no toolUse/toolResult equivalent (the agent never runs them)",
  `strands/models/anthropic.py`), so each goes out as a raw chunk `{"nativeTool": …}`, which Strands' `process_stream`
  passes on as `{"event": chunk}`, the way ren's xAI model sends xAI's server-side tools (`{"xai": …}`).
- The agent's own tools, the ones `create_harness` gives it, reach the CLI as one MCP server, `ren`: the SDK's in-process
  server for Claude Code (`create_sdk_mcp_server`), and for Antigravity `cli_bridge.py`, a stdio relay to `/bridge/<token>`
  here. A call there ends the message with that `toolUse`, as the custom provider docs' Tool Support says, Strands runs
  the tool, and the `toolResult` in the next `stream` call is the CLI's answer.
"""

from __future__ import annotations

import abc
import asyncio
import base64
import itertools
import json
import secrets
import shutil
import threading
import uuid
from collections.abc import AsyncGenerator, AsyncIterator, Awaitable, Callable, Iterator
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal, TypedDict, TypeVar

from claude_agent_sdk import (
    AssistantMessage,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    EffortLevel,
    Message,
    ResultMessage,
    SdkMcpTool,
    StreamEvent as ClaudeStreamEvent,
    ToolResultBlock,
    ToolUseBlock,
    UserMessage,
    create_sdk_mcp_server,
)
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel
from strands.event_loop.streaming import process_stream
from strands.models import Model, ModelCapabilities, ModelInfo
from strands.tools import convert_pydantic_to_tool_spec
from strands.agent.agent_metadata import AgentMetadata
from strands.types.content import ContentBlock, Messages, SystemContentBlock
from strands.types.streaming import StreamEvent
from strands.types.tools import ToolChoice, ToolResult, ToolSpec

T = TypeVar("T", bound=BaseModel)
type Json = dict[str, Json] | list[Json] | str | int | float | bool | None

CliName = Literal["claude-code", "antigravity"]
#: Where each Antigravity conversation keeps its workspace MCP config.
HOME = Path("/config/emrys-gemini/cli")
#: The folder both CLIs work in, the agents' shared workspace (the image's WORKDIR).
WORKSPACE = "/config/workspace"
BRIDGE_COMMAND = ["/opt/gemini/bin/python", "/opt/gemini/cli_bridge.py"]
BRIDGE_URL = "http://127.0.0.1:8100/bridge"
#: The CLI's own default model ("default: Special value that clears any model override and reverts to the runtime
#: default for your account", https://code.claude.com/docs/en/model-config); no `--model` is passed for it.
DEFAULT_MODEL = "default"

#: Claude Code's model aliases (https://code.claude.com/docs/en/model-config); `--model` also takes a model's full name.
CLAUDE_MODELS = ("default", "best", "fable", "opus", "sonnet", "haiku", "opus[1m]", "sonnet[1m]", "opusplan")
#: The SDK's `effort` levels (`EffortLevel`).
CLAUDE_EFFORTS: tuple[EffortLevel, ...] = ("low", "medium", "high", "xhigh", "max")
#: Antigravity's reasoning effort: "low, medium, high, xhigh (only models that publish an xhigh variant), max (only models
#: that publish a max variant)" (https://antigravity.google/docs/cli/commands/effort). Over ACP it is the session's
#: `thought_level` option, matched by value or name.
ANTIGRAVITY_EFFORTS = ("low", "medium", "high", "xhigh", "max")


class NativeTool(TypedDict, total=False):
    """One of the CLI's own tool calls: `input` when it starts, `output` and `status` when it ends (JSON text)."""

    id: str
    name: str
    input: str
    output: str
    status: Literal["running", "success", "error"]


class CliStreamEvent(StreamEvent, total=False):
    """Strands' stream event, plus the CLI's native tool calls as their own raw chunk."""

    nativeTool: NativeTool


class BridgeContent(BaseModel):
    """One block of a tool's result for the CLI: MCP's TextContent or ImageContent."""

    type: Literal["text", "image"]
    text: str = ""
    data: str = ""
    mime_type: str = ""


class BridgeResult(BaseModel):
    """A tool's result for the CLI, as MCP's CallToolResult carries it."""

    content: list[BridgeContent]
    is_error: bool


class BridgeTool(BaseModel):
    name: str
    description: str
    input_schema: dict[str, Json]


class BridgeCall(BaseModel):
    name: str
    arguments: dict[str, Json] = {}


@dataclass
class Call:
    """A call the CLI made to one of the agent's tools, answered when Strands has run it."""

    name: str
    arguments: dict[str, Json]
    answer: asyncio.Future[BridgeResult]
    id: str = field(default_factory=lambda: f"ren_{uuid.uuid4().hex}")


@dataclass
class Exited:
    code: int
    stderr: str


def at(value: Json, *path: str | int) -> Json:
    """The value at `path` in a CLI event, or None where the event has none."""
    for key in path:
        if isinstance(value, dict) and isinstance(key, str):
            value = value.get(key)
        elif isinstance(value, list) and isinstance(key, int) and 0 <= key < len(value):
            value = value[key]
        else:
            return None
    return value


def text_at(value: Json, *path: str | int) -> str:
    found = at(value, *path)
    return found if isinstance(found, str) else ""


def int_at(value: Json, *path: str | int) -> int:
    found = at(value, *path)
    return found if isinstance(found, int) and not isinstance(found, bool) else 0


def items_at(value: Json, *path: str | int) -> list[Json]:
    found = at(value, *path)
    return found if isinstance(found, list) else []


def flat(value: object) -> str:
    """A tool's output as text: text as it is, a list of text blocks joined, anything else as JSON."""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(str(item["text"]) if isinstance(item, dict) and item.get("type") == "text" else json.dumps(item) for item in value)
    return "" if value is None else json.dumps(value)


def encoded(block: ContentBlock) -> str:
    """An image block's bytes, base64."""
    return base64.b64encode(block["image"]["source"]["bytes"]).decode("ascii")


def bridge_result(result: ToolResult) -> BridgeResult:
    """Strands' toolResult as the CLI's MCP result."""
    content: list[BridgeContent] = []
    for block in result["content"]:
        if "text" in block:
            content.append(BridgeContent(type="text", text=block["text"]))
        elif "json" in block:
            content.append(BridgeContent(type="text", text=json.dumps(block["json"])))
        elif "image" in block and "bytes" in block["image"]["source"]:
            image = block["image"]
            content.append(BridgeContent(type="image", data=base64.b64encode(image["source"]["bytes"]).decode("ascii"), mime_type=f"image/{image['format']}"))
        elif "document" in block:
            content.append(BridgeContent(type="text", text=f"[document {block['document'].get('name', '')}]"))
    return BridgeResult(content=content, is_error=result["status"] == "error")


class Session:
    """One running CLI for one conversation, and the agent's tools it calls."""

    def __init__(self, tools: list[ToolSpec]) -> None:
        self.token = secrets.token_urlsafe(24)
        #: Claude Code's id for a new session (`--session-id` takes a UUID); Antigravity's ACP session id once it is open.
        self.id = str(uuid.uuid4())
        #: Antigravity's JSON-RPC request ids.
        self.ids = itertools.count(1)
        self.tools = tools
        self.inbox: asyncio.Queue[Json | Message | Call | Exited] = asyncio.Queue()
        self.waiting: dict[str, Call] = {}
        #: The native tool calls under way, id to name, and to input where it comes after the call starts (ACP's `rawInput`).
        self.native: dict[str, str] = {}
        self.inputs: dict[str, str] = {}
        self.busy = False
        self.folder = HOME / self.token
        self.process: asyncio.subprocess.Process | None = None
        self.client: ClaudeSDKClient | None = None
        self.readers: list[asyncio.Task[None]] = []
        BRIDGES[self.token] = self

    @property
    def tool_names(self) -> list[str]:
        return [spec["name"] for spec in self.tools]

    def mcp_server(self) -> dict[str, Json]:
        return {"command": BRIDGE_COMMAND[0], "args": [*BRIDGE_COMMAND[1:], f"{BRIDGE_URL}/{self.token}"]}

    async def start(self, argv: list[str], cwd: str) -> None:
        self.process = await asyncio.create_subprocess_exec(
            *argv, cwd=cwd, stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, limit=64 * 1024 * 1024
        )
        stderr: list[str] = []
        self.readers = [asyncio.create_task(self._read(self.process, stderr)), asyncio.create_task(self._errors(self.process, stderr))]

    async def _read(self, process: asyncio.subprocess.Process, stderr: list[str]) -> None:
        assert process.stdout is not None
        async for raw in process.stdout:
            try:
                line: Json = json.loads(raw)
            except json.JSONDecodeError:
                continue
            await self.inbox.put(line)
        code = await process.wait()
        await self.inbox.put(Exited(code, "".join(stderr)[-4000:]))

    @staticmethod
    async def _errors(process: asyncio.subprocess.Process, stderr: list[str]) -> None:
        assert process.stderr is not None
        async for raw in process.stderr:
            stderr.append(raw.decode(errors="replace"))

    def pump(self, messages: AsyncIterator[Message]) -> None:
        """Puts the SDK's messages in the inbox, then the end of the CLI."""

        async def read() -> None:
            try:
                async for message in messages:
                    await self.inbox.put(message)
                await self.inbox.put(Exited(0, ""))
            except Exception as error:
                await self.inbox.put(Exited(1, str(error)))

        self.readers = [asyncio.create_task(read())]

    async def call(self, name: str, arguments: dict[str, Json]) -> BridgeResult:
        """One call from the CLI to the agent's tools, answered once Strands has run it."""
        answer: asyncio.Future[BridgeResult] = asyncio.get_running_loop().create_future()
        await self.inbox.put(Call(name, arguments, answer))
        return await answer

    @property
    def running(self) -> bool:
        return bool(self.readers) and not self.readers[0].done()

    async def send(self, line: dict[str, Json]) -> None:
        assert self.process is not None and self.process.stdin is not None
        self.process.stdin.write(json.dumps(line).encode() + b"\n")
        await self.process.stdin.drain()
        self.busy = True

    async def close(self) -> None:
        BRIDGES.pop(self.token, None)
        for call in self.waiting.values():
            call.answer.cancel()
        if self.client is not None:
            await self.client.disconnect()
        if self.running and self.process is not None:
            self.process.terminate()
            try:
                await asyncio.wait_for(self.process.wait(), 10)
            except TimeoutError:
                self.process.kill()
        for reader in self.readers:
            reader.cancel()
        shutil.rmtree(self.folder, ignore_errors=True)


#: Every running CLI's bridge, by the token in its `ren` MCP server's address.
BRIDGES: dict[str, Session] = {}
router = APIRouter()


def bridge(token: str, request: Request) -> Session:
    session = BRIDGES.get(token)
    if session is None or request.client is None or request.client.host != "127.0.0.1":
        raise HTTPException(404)
    return session


@router.get("/bridge/{token}/tools")
async def bridge_tools(token: str, request: Request) -> list[BridgeTool]:
    """The agent's tools for the CLI's `ren` MCP server."""
    return [BridgeTool(name=spec["name"], description=spec["description"], input_schema=spec["inputSchema"]["json"]) for spec in bridge(token, request).tools]


@router.post("/bridge/{token}/call")
async def bridge_call(token: str, call: BridgeCall, request: Request) -> BridgeResult:
    """One call from the CLI, answered once Strands has run the tool."""
    return await bridge(token, request).call(call.name, call.arguments)


class CliConfig(TypedDict):
    model_id: str
    effort: str | None


class CliModel(Model, abc.ABC):
    """A CLI agent as a Strands model; each conversation (each agent, a subagent's too) has its own CLI process."""

    #: The prefix the CLI gives the `ren` MCP server's tools, so its own calls to them aren't shown twice.
    bridge_prefix: str

    def __init__(self, model_id: str, effort: str | None) -> None:
        self.config = CliConfig(model_id=model_id, effort=effort)
        self.sessions: dict[str, Session] = {}

    def update_config(self, **model_config: str | None) -> None:
        for key, value in model_config.items():
            if key == "model_id" and isinstance(value, str):
                self.config["model_id"] = value
            elif key == "effort":
                self.config["effort"] = value
            else:
                raise ValueError(f"{type(self).__name__} has no setting {key}={value!r}")

    def get_config(self) -> CliConfig:
        return self.config

    @property
    def model_info(self) -> ModelInfo:
        return ModelInfo.merge(super().model_info, ModelInfo(capabilities=ModelCapabilities(stateful=True)))

    @abc.abstractmethod
    async def open(self, session: Session, conversation: str | None, instructions: str | None) -> None:
        """Starts the CLI for `session`, resuming `conversation` when there is one."""

    @abc.abstractmethod
    async def send(self, session: Session, blocks: list[ContentBlock], instructions: str | None) -> None:
        """Sends a user turn, `instructions` first on a new conversation where the CLI has no system prompt of its own."""

    @abc.abstractmethod
    def translate(self, item: Json | Message, session: Session, state: dict[str, object], turn: Turn) -> Iterator[CliStreamEvent]:
        """The Strands events for one thing the CLI sent."""

    async def stream(
        self,
        messages: Messages,
        tool_specs: list[ToolSpec] | None = None,
        system_prompt: str | None = None,
        *,
        tool_choice: ToolChoice | None = None,
        system_prompt_content: list[SystemContentBlock] | None = None,
        invocation_state: dict[str, object] | None = None,
        cancel_signal: threading.Event | None = None,
        agent_metadata: AgentMetadata | None = None,
        structured_output_model: type[BaseModel] | None = None,
        model_state: dict[str, object] | None = None,
        **kwargs: object,
    ) -> AsyncGenerator[CliStreamEvent, None]:
        state: dict[str, object] = {} if model_state is None else model_state
        key = state.get("process")
        session = self.sessions.get(key) if isinstance(key, str) else None
        last = messages[-1]["content"]
        results = [block["toolResult"] for block in last if "toolResult" in block]
        if session is not None and session.waiting and results:
            for result in results:
                if call := session.waiting.pop(result["toolUseId"], None):
                    call.answer.set_result(bridge_result(result))
        else:
            tools = tool_specs or []
            if session is None or session.busy or not session.running or session.tool_names != [spec["name"] for spec in tools]:
                if session is not None:
                    await session.close()
                session = Session(tools)
                session.folder.mkdir(parents=True, exist_ok=True)
                conversation = state.get("conversation")
                await self.open(session, conversation if isinstance(conversation, str) else None, system_prompt)
                key = session.token
                self.sessions[key] = session
                state["process"] = key
            await self.send(session, last, None if "conversation" in state else system_prompt)
            session.busy = True
            state.setdefault("conversation", session.id)
        yield {"messageStart": {"role": "assistant"}}
        turn = Turn()
        while True:
            item = await session.inbox.get()
            if isinstance(item, Call):
                for event in turn.close():
                    yield event
                session.waiting[item.id] = item
                yield {"contentBlockStart": {"start": {"toolUse": {"toolUseId": item.id, "name": item.name}}}}
                yield {"contentBlockDelta": {"delta": {"toolUse": {"input": json.dumps(item.arguments)}}}}
                yield {"contentBlockStop": {}}
                yield {"messageStop": {"stopReason": "tool_use"}}
                return
            if isinstance(item, Exited):
                raise self.exited(item)
            for event in self.translate(item, session, state, turn):
                yield event
            if turn.done:
                session.busy = False
                if not tool_specs:
                    # A call with no tools is the harness's web_fetch summarizer, a one-turn Agent on this model that
                    # never comes back to its process.
                    await self.sessions.pop(session.token).close()
                return

    def exited(self, item: Exited) -> RuntimeError:
        return RuntimeError(f"{type(self).__name__} exited with code {item.code}: {item.stderr.strip()}")

    async def structured_output(self, output_model: type[T], prompt: Messages, system_prompt: str | None = None, **kwargs: object) -> AsyncGenerator[dict[str, T | object], None]:
        """As Strands' own providers do it: the output model as the one tool, and its call's input as the output."""
        tool_spec = convert_pydantic_to_tool_spec(output_model)
        state: dict[str, object] = {}
        stop = None
        async for event in process_stream(self.stream(prompt, [tool_spec], system_prompt, model_state=state)):
            stop = event.get("stop")
            yield event
        key = state.get("process")
        if isinstance(key, str) and (session := self.sessions.pop(key, None)):
            await session.close()
        if stop is None:
            raise ValueError("The model returned nothing.")
        for block in stop[1]["content"]:
            if "toolUse" in block and block["toolUse"]["name"] == tool_spec["name"]:
                yield {"output": output_model.model_validate(block["toolUse"]["input"])}
                return
        raise ValueError(f"The model did not call {tool_spec['name']}.")

    async def close(self) -> None:
        for session in self.sessions.values():
            await session.close()
        self.sessions.clear()


@dataclass
class Turn:
    """A turn's open text and reasoning blocks, and whether the CLI has finished it."""

    blocks: dict[int, str] = field(default_factory=dict)
    streamed: bool = False
    done: bool = False

    def open(self, index: int, kind: str) -> CliStreamEvent:
        self.blocks[index] = kind
        self.streamed = True
        return {"contentBlockStart": {"start": {}}}

    def close(self) -> Iterator[CliStreamEvent]:
        for _ in self.blocks:
            yield {"contentBlockStop": {}}
        self.blocks.clear()


class ClaudeCodeModel(CliModel):
    """Claude Code through the Claude Agent SDK (`claude-agent-sdk`, which ships Claude Code 2.1.330), signed in with
    the owner's Claude account (`CLAUDE_CODE_OAUTH_TOKEN`).

    Its own tools and the agent's, as the SDK's in-process MCP server `ren` and no other (`strict_mcp_config`); the
    agent's instructions after Claude Code's own system prompt (the `claude_code` preset's `append`); partial messages
    for the deltas, and summarized adaptive thinking, since Claude's thinking arrives as a signature only unless a
    display is asked for.
    """

    bridge_prefix = "mcp__ren__"

    async def open(self, session: Session, conversation: str | None, instructions: str | None) -> None:
        tools = [SdkMcpTool(name=spec["name"], description=spec["description"], input_schema=spec["inputSchema"]["json"], handler=handler(session, spec["name"])) for spec in session.tools]
        session.client = ClaudeSDKClient(ClaudeAgentOptions(
            mcp_servers={"ren": create_sdk_mcp_server("ren", tools=tools)}, strict_mcp_config=True,
            system_prompt={"type": "preset", "preset": "claude_code", "append": instructions or ""},
            permission_mode="bypassPermissions", include_partial_messages=True, thinking={"type": "adaptive", "display": "summarized"},
            resume=conversation, session_id=None if conversation else session.id, cwd=WORKSPACE,
            model=None if self.config["model_id"] == DEFAULT_MODEL else self.config["model_id"],
            effort=next((level for level in CLAUDE_EFFORTS if level == self.config["effort"]), None),
        ))
        await session.client.connect()
        session.pump(session.client.receive_messages())

    async def send(self, session: Session, blocks: list[ContentBlock], instructions: str | None) -> None:
        content: list[Json] = []
        for block in blocks:
            if "text" in block:
                content.append({"type": "text", "text": block["text"]})
            elif "image" in block and "bytes" in block["image"]["source"]:
                content.append({"type": "image", "source": {"type": "base64", "media_type": f"image/{block['image']['format']}", "data": encoded(block)}})

        async def turn() -> AsyncIterator[dict[str, Json]]:
            yield {"type": "user", "message": {"role": "user", "content": content}, "parent_tool_use_id": None}

        assert session.client is not None
        await session.client.query(turn())

    def translate(self, item: Json | Message, session: Session, state: dict[str, object], turn: Turn) -> Iterator[CliStreamEvent]:
        if isinstance(item, ClaudeStreamEvent) and item.parent_tool_use_id is None:
            index = int_at(item.event, "index")
            match text_at(item.event, "type"):
                case "content_block_start" if text_at(item.event, "content_block", "type") in ("text", "thinking"):
                    yield turn.open(index, text_at(item.event, "content_block", "type"))
                case "content_block_delta" if index in turn.blocks:
                    delta = at(item.event, "delta")
                    match text_at(delta, "type"):
                        case "text_delta":
                            yield {"contentBlockDelta": {"delta": {"text": text_at(delta, "text")}}}
                        case "thinking_delta":
                            yield {"contentBlockDelta": {"delta": {"reasoningContent": {"text": text_at(delta, "thinking")}}}}
                        case "signature_delta":
                            yield {"contentBlockDelta": {"delta": {"reasoningContent": {"signature": text_at(delta, "signature")}}}}
                case "content_block_stop" if turn.blocks.pop(index, ""):
                    yield {"contentBlockStop": {}}
        elif isinstance(item, AssistantMessage):
            for block in item.content:
                if isinstance(block, ToolUseBlock) and not block.name.startswith(self.bridge_prefix):
                    session.native[block.id] = block.name
                    yield {"nativeTool": {"id": block.id, "name": block.name, "input": json.dumps(block.input), "status": "running"}}
        elif isinstance(item, UserMessage) and isinstance(item.content, list):
            for result in item.content:
                if isinstance(result, ToolResultBlock) and (name := session.native.pop(result.tool_use_id, "")):
                    yield {"nativeTool": {"id": result.tool_use_id, "name": name, "output": flat(result.content), "status": "error" if result.is_error else "success"}}
        elif isinstance(item, ResultMessage):
            turn.done = True
            if item.is_error:
                raise RuntimeError(item.result or item.subtype)
            yield from turn.close()
            usage = item.usage or {}
            read, written = int_at(usage, "cache_read_input_tokens"), int_at(usage, "cache_creation_input_tokens")
            used_in, used_out = int_at(usage, "input_tokens") + read + written, int_at(usage, "output_tokens")
            yield {"metadata": {"usage": {"inputTokens": used_in, "outputTokens": used_out, "totalTokens": used_in + used_out, "cacheReadInputTokens": read, "cacheWriteInputTokens": written}, "metrics": {"latencyMs": item.duration_ms}}}
            yield {"messageStop": {"stopReason": "end_turn"}}


def handler(session: Session, name: str) -> Callable[[dict[str, Json]], Awaitable[dict[str, Json]]]:
    """The SDK tool handler for one of the agent's tools: the call goes to Strands through the session."""

    async def run(arguments: dict[str, Json]) -> dict[str, Json]:
        result = await session.call(name, arguments)
        content: list[Json] = [{"type": "text", "text": block.text} if block.type == "text" else {"type": "image", "data": block.data, "mimeType": block.mime_type} for block in result.content]
        return {"content": content, "is_error": result.is_error}

    return run


class AntigravityModel(CliModel):
    """Antigravity CLI as an Agent Client Protocol server (`agy acp`, https://antigravity.google/docs/ide/extensions/acp),
    signed in with the owner's Google account (owner, 2026-11-21).

    ACP rather than `agy -p`: headless stream-json carries the model's thinking only as `thinking_tokens`
    (https://antigravity.google/docs/cli/headless), while ACP streams it as `agent_thought_chunk`, each tool call with its
    `rawInput` and `rawOutput`, takes images in a prompt, and sets the model and thinking level as session config options
    (https://agentclientprotocol.com/protocol/prompt-turn, /protocol/tool-calls, /protocol/session-config-options).
    `--dangerously-skip-permissions` "Auto-approve[s] all tool permission requests". `agy` has no system prompt flag, so
    a new conversation's first message starts with the instructions. Its MCP config is per workspace
    (`<workspace>/.agents/mcp_config.json`, https://antigravity.google/docs/mcp.md), so each process's session opens in
    its own folder, with `--add-dir` for the shared workspace. Its tools load as Antigravity loads them by default
    (lazily: the model reads a tool's schema when it wants it and calls it through `call_mcp_tool`, or as
    `mcp_ren_<tool>` where it falls back to eager loading), and `timeoutSeconds: -1` "turns off both limits" (a Strands
    tool, such as a subagent, may run past the 180 s default).
    """

    bridge_prefix = "mcp_ren_"

    async def open(self, session: Session, conversation: str | None, instructions: str | None) -> None:
        config = {"mcpServers": {"ren": {**session.mcp_server(), "timeoutSeconds": -1}}}
        (session.folder / ".agents").mkdir(parents=True, exist_ok=True)
        (session.folder / ".agents" / "mcp_config.json").write_text(json.dumps(config))
        await session.start(["agy", "acp", "--dangerously-skip-permissions", "--add-dir", WORKSPACE], str(session.folder))
        opened = await acp_session(session, conversation)
        model = None if self.config["model_id"] == DEFAULT_MODEL else self.config["model_id"]
        for category, wanted in (("model", model), ("thought_level", self.config["effort"])):
            if wanted and (option := config_option(opened, category)):
                values = {text_at(choice, "value"): text_at(choice, "name") for choice in choices(option)}
                value = next((value for value, name in values.items() if wanted.lower() in (value.lower(), name.lower())), None)
                if value is None:
                    raise ValueError(f"Antigravity offers no {category} {wanted!r}: {', '.join(values)}")
                await acp_request(session, "session/set_config_option", {"sessionId": session.id, "configId": text_at(option, "id"), "value": value})

    async def send(self, session: Session, blocks: list[ContentBlock], instructions: str | None) -> None:
        prompt: list[Json] = [{"type": "text", "text": instructions}] if instructions else []
        for block in blocks:
            if "text" in block:
                prompt.append({"type": "text", "text": block["text"]})
            elif "image" in block and "bytes" in block["image"]["source"]:
                prompt.append({"type": "image", "mimeType": f"image/{block['image']['format']}", "data": encoded(block)})
        await session.send({"jsonrpc": "2.0", "id": next(session.ids), "method": "session/prompt", "params": {"sessionId": session.id, "prompt": prompt}})

    def translate(self, line: Json | Message, session: Session, state: dict[str, object], turn: Turn) -> Iterator[CliStreamEvent]:
        if not isinstance(line, (dict, list, str, int, float, bool)) and line is not None:
            return
        if at(line, "method") is None:
            # The answer to `session/prompt`, the only request open during a turn: the turn has ended.
            turn.done = True
            if (error := at(line, "error")) is not None:
                raise RuntimeError(text_at(error, "message") or flat(error))
            yield from turn.close()
            reason = text_at(line, "result", "stopReason")
            yield {"messageStop": {"stopReason": "max_tokens" if reason == "max_tokens" else "content_filtered" if reason == "refusal" else "end_turn"}}
            return
        update = at(line, "params", "update")
        match kind := text_at(update, "sessionUpdate"):
            case "agent_message_chunk" | "agent_thought_chunk":
                text = text_at(update, "content", "text")
                block = "text" if kind == "agent_message_chunk" else "reasoning"
                if block not in turn.blocks.values():
                    yield from turn.close()
                    yield turn.open(0, block)
                yield {"contentBlockDelta": {"delta": {"text": text} if block == "text" else {"reasoningContent": {"text": text}}}}
            case "tool_call" | "tool_call_update":
                tool_id = text_at(update, "toolCallId")
                name = session.native.setdefault(tool_id, text_at(update, "name") or text_at(update, "title"))
                if at(update, "rawInput") is not None:
                    session.inputs[tool_id] = json.dumps(at(update, "rawInput"))
                    if text_at(update, "rawInput", "ServerName") == "ren":
                        session.native[tool_id] = name = self.bridge_prefix
                tool_input = session.inputs.get(tool_id, "")
                finished = text_at(update, "status") in ("completed", "failed")
                if finished:
                    session.native.pop(tool_id, None)
                    session.inputs.pop(tool_id, None)
                if name.startswith(self.bridge_prefix):
                    return
                if kind == "tool_call":
                    yield {"nativeTool": {"id": tool_id, "name": name, "input": tool_input, "status": "running"}}
                if finished:
                    contents = [at(item, "content") if text_at(item, "type") == "content" else item for item in items_at(update, "content")]
                    output = flat(at(update, "rawOutput")) or flat(contents)
                    status: Literal["success", "error"] = "error" if text_at(update, "status") == "failed" else "success"
                    yield {"nativeTool": {"id": tool_id, "name": name, "input": tool_input, "output": output, "status": status}}


async def acp_request(session: Session, method: str, params: dict[str, Json]) -> Json:
    """One JSON-RPC request to `agy acp` before a turn starts, and its result."""
    number = next(session.ids)
    await session.send({"jsonrpc": "2.0", "id": number, "method": method, "params": params})
    while True:
        item = await session.inbox.get()
        if isinstance(item, Exited):
            raise RuntimeError(f"agy acp exited with code {item.code}: {item.stderr.strip()}")
        if isinstance(item, (dict, list)) and at(item, "id") == number and at(item, "method") is None:
            if (error := at(item, "error")) is not None:
                raise RuntimeError(f"agy acp {method}: {text_at(error, 'message') or flat(error)}")
            return at(item, "result")


async def acp_session(session: Session, conversation: str | None) -> Json:
    """Opens the ACP session (`session/resume` for an existing conversation) in the process's folder."""
    await acp_request(session, "initialize", {"protocolVersion": 1, "clientCapabilities": {}, "clientInfo": {"name": "ren", "version": "1"}})
    where: dict[str, Json] = {"cwd": str(session.folder), "mcpServers": []}
    if conversation:
        session.id = conversation
        return await acp_request(session, "session/resume", {"sessionId": conversation, **where})
    opened = await acp_request(session, "session/new", where)
    session.id = text_at(opened, "sessionId")
    return opened


def config_option(opened: Json, category: str) -> Json:
    """The session's config option in `category` (`model`, `thought_level`), if it has one."""
    return next((option for option in items_at(opened, "configOptions") if text_at(option, "category") == category), None)


def choices(option: Json) -> list[Json]:
    """A select option's values, its groups' included."""
    return [nested for item in items_at(option, "options") for nested in (items_at(item, "options") if at(item, "group") is not None else [item])]


def cli_model(name: CliName, model_id: str, effort: str | None) -> CliModel:
    if name == "claude-code":
        return ClaudeCodeModel(model_id, effort if effort in CLAUDE_EFFORTS else None)
    return AntigravityModel(model_id, effort if effort in ANTIGRAVITY_EFFORTS else None)


async def cli_models(name: CliName) -> list[dict[str, str]]:
    """The CLI's models: Claude Code's aliases, or the values of the model option `agy acp` gives a new session."""
    if name == "claude-code":
        return [{"id": model_id, "owned_by": "anthropic", "provider": name} for model_id in CLAUDE_MODELS]
    session = Session([])
    session.folder.mkdir(parents=True, exist_ok=True)
    try:
        await session.start(["agy", "acp"], str(session.folder))
        option = config_option(await acp_session(session, None), "model")
    finally:
        await session.close()
    return [{"id": model_id, "owned_by": "google", "provider": name} for model_id in [DEFAULT_MODEL, *(text_at(choice, "value") for choice in choices(option))]]
