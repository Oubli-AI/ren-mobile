"""Claude Code and Antigravity CLI as Strands model providers, so each one is a text agent `create_harness` makes.

Each is a custom model provider (Strands' docs, "Custom Model Providers"): a `Model` whose `stream` drives the CLI in its
headless stream-json mode (Claude Code: https://code.claude.com/docs/en/headless; Antigravity:
https://antigravity.google/docs/cli/headless.md) and yields Strands' stream events.

- The CLI keeps the conversation itself (its session, resumed by id), so the model is stateful: Strands sets
  `capabilities.stateful` "only for a provider that keeps state server-side" (`strands/models/model.py`), sends only the
  new turn, and saves the CLI's ids in `model_state` with the session, as it saves OpenAIResponsesModel's `response_id`.
- The CLI's native tools (Claude Code's Bash, Read, Edit, WebSearch, Agent and the rest; Antigravity's run_command,
  write_to_file and the rest) run inside the CLI, as a provider's server-side tools run at the provider. Strands gives
  those no toolUse/toolResult ("They have no toolUse/toolResult equivalent (the agent never runs them)",
  `strands/models/anthropic.py`), so each goes out as a raw chunk `{"nativeTool": …}`, which Strands' `process_stream`
  passes on as `{"event": chunk}`, the way ren's xAI model sends xAI's server-side tools (`{"xai": …}`).
- The agent's own tools, the ones `create_harness` gives it, reach the CLI as one MCP server, `ren` (`cli_bridge.py`, a
  stdio relay to `/bridge/<token>` here). A call there ends the message with that `toolUse`, as the custom provider docs'
  Tool Support says, Strands runs the tool, and the `toolResult` in the next `stream` call is the CLI's answer.
"""

from __future__ import annotations

import abc
import asyncio
import base64
import json
import secrets
import shutil
import threading
import uuid
from collections.abc import AsyncGenerator, Iterator
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal, TypedDict, TypeVar

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
#: Where each conversation's CLI keeps its files here: Antigravity's workspace MCP config and Claude Code's instructions.
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
#: `--effort`: "Options: low, medium, high, xhigh, max, or ultracode. Available levels depend on the model."
#: (https://code.claude.com/docs/en/cli-reference).
CLAUDE_EFFORTS = ("low", "medium", "high", "xhigh", "max", "ultracode")


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


def flat(value: Json) -> str:
    """A tool's output as text: text as it is, a list of text blocks joined, anything else as JSON."""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(text_at(item, "text") if text_at(item, "type") == "text" else json.dumps(item) for item in value)
    return "" if value is None else json.dumps(value)


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
    """One running CLI process for one conversation, and the bridge its `ren` MCP server calls."""

    def __init__(self, tools: list[ToolSpec]) -> None:
        self.token = secrets.token_urlsafe(24)
        #: Claude Code's id for a new session (`--session-id` takes a UUID).
        self.id = str(uuid.uuid4())
        self.tools = tools
        self.inbox: asyncio.Queue[Json | Call | Exited] = asyncio.Queue()
        self.waiting: dict[str, Call] = {}
        #: The native tool calls under way, id to name.
        self.native: dict[str, str] = {}
        #: Antigravity's usage is cumulative for the session ("num_turns, usage, and duration_seconds are cumulative").
        self.used = (0, 0)
        self.busy = False
        self.folder = HOME / self.token
        self.process: asyncio.subprocess.Process | None = None
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

    @property
    def running(self) -> bool:
        return self.process is not None and self.process.returncode is None

    async def send(self, line: dict[str, Json]) -> None:
        assert self.process is not None and self.process.stdin is not None
        self.process.stdin.write(json.dumps(line).encode() + b"\n")
        await self.process.stdin.drain()
        self.busy = True

    async def close(self) -> None:
        BRIDGES.pop(self.token, None)
        for call in self.waiting.values():
            call.answer.cancel()
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
    session = bridge(token, request)
    answer: asyncio.Future[BridgeResult] = asyncio.get_running_loop().create_future()
    await session.inbox.put(Call(call.name, call.arguments, answer))
    return await answer


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
    def argv(self, session: Session, conversation: str | None, instructions: str | None) -> tuple[list[str], str]:
        """The CLI's command line and working folder, resuming `conversation` when there is one."""

    @abc.abstractmethod
    def user_line(self, blocks: list[ContentBlock], instructions: str | None) -> dict[str, Json]:
        """A user turn as the CLI reads it on stdin, `instructions` first on a new conversation."""

    @abc.abstractmethod
    def translate(self, line: Json, session: Session, state: dict[str, object], turn: Turn) -> Iterator[CliStreamEvent]:
        """The Strands events for one line the CLI printed."""

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
                argv, cwd = self.argv(session, conversation if isinstance(conversation, str) else None, system_prompt)
                await session.start(argv, cwd)
                key = session.token
                self.sessions[key] = session
                state["process"] = key
            await session.send(self.user_line(last, None if "conversation" in state else system_prompt))
            self.started(session, state)
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
                raise RuntimeError(f"{type(self).__name__} exited with code {item.code}: {item.stderr.strip()}")
            for event in self.translate(item, session, state, turn):
                yield event
            if turn.done:
                session.busy = False
                return

    def started(self, session: Session, state: dict[str, object]) -> None:
        """What the model keeps once a turn is sent (Claude Code's session id, which it chooses itself)."""

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
    """Claude Code (`claude -p`), signed in with the owner's Claude account (`CLAUDE_CODE_OAUTH_TOKEN`).

    The flags are the ones claude-agent-sdk 0.2.187 passes to the CLI it bundles (2.1.330): stream-json both ways with
    `--verbose`, `--include-partial-messages` for the deltas, and `--thinking adaptive --thinking-display summarized`,
    since Claude's thinking arrives as a signature only unless a display is asked for. The agent's instructions follow
    Claude Code's own system prompt (`--append-system-prompt-file`). Its tools are its own default set plus the agent's,
    as the MCP server `ren` (`--strict-mcp-config`: no other).
    """

    bridge_prefix = "mcp__ren__"

    def argv(self, session: Session, conversation: str | None, instructions: str | None) -> tuple[list[str], str]:
        mcp = {"mcpServers": {"ren": {"type": "stdio", **session.mcp_server()}}}
        prompt = session.folder / "instructions.md"
        prompt.write_text(instructions or "")
        argv = [
            "claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--permission-mode", "bypassPermissions", "--thinking", "adaptive", "--thinking-display", "summarized",
            "--mcp-config", json.dumps(mcp), "--strict-mcp-config",
            "--append-system-prompt-file", str(prompt),
            *(["--resume", conversation] if conversation else ["--session-id", session.id]),
        ]
        if self.config["model_id"] != DEFAULT_MODEL:
            argv += ["--model", self.config["model_id"]]
        if self.config["effort"]:
            argv += ["--effort", self.config["effort"]]
        return argv, WORKSPACE

    def user_line(self, blocks: list[ContentBlock], instructions: str | None) -> dict[str, Json]:
        # The instructions are the system prompt's (`--append-system-prompt-file`).
        content: list[Json] = []
        for block in blocks:
            if "text" in block:
                content.append({"type": "text", "text": block["text"]})
            elif "image" in block and "bytes" in block["image"]["source"]:
                image = block["image"]
                content.append({"type": "image", "source": {"type": "base64", "media_type": f"image/{image['format']}", "data": base64.b64encode(image["source"]["bytes"]).decode("ascii")}})
        return {"type": "user", "message": {"role": "user", "content": content}, "parent_tool_use_id": None, "session_id": ""}

    def started(self, session: Session, state: dict[str, object]) -> None:
        state.setdefault("conversation", session.id)

    def translate(self, line: Json, session: Session, state: dict[str, object], turn: Turn) -> Iterator[CliStreamEvent]:
        kind = text_at(line, "type")
        if kind == "stream_event" and at(line, "parent_tool_use_id") is None:
            event = at(line, "event")
            index = int_at(event, "index")
            match text_at(event, "type"):
                case "content_block_start" if text_at(event, "content_block", "type") in ("text", "thinking"):
                    yield turn.open(index, text_at(event, "content_block", "type"))
                case "content_block_delta" if index in turn.blocks:
                    delta = at(event, "delta")
                    match text_at(delta, "type"):
                        case "text_delta":
                            yield {"contentBlockDelta": {"delta": {"text": text_at(delta, "text")}}}
                        case "thinking_delta":
                            yield {"contentBlockDelta": {"delta": {"reasoningContent": {"text": text_at(delta, "thinking")}}}}
                        case "signature_delta":
                            yield {"contentBlockDelta": {"delta": {"reasoningContent": {"signature": text_at(delta, "signature")}}}}
                case "content_block_stop" if turn.blocks.pop(index, ""):
                    yield {"contentBlockStop": {}}
        elif kind == "assistant":
            for block in items_at(line, "message", "content"):
                name = text_at(block, "name")
                if text_at(block, "type") == "tool_use" and not name.startswith(self.bridge_prefix):
                    session.native[text_at(block, "id")] = name
                    yield {"nativeTool": {"id": text_at(block, "id"), "name": name, "input": json.dumps(at(block, "input")), "status": "running"}}
        elif kind == "user":
            for block in items_at(line, "message", "content"):
                if text_at(block, "type") == "tool_result" and (name := session.native.pop(text_at(block, "tool_use_id"), "")):
                    status: Literal["success", "error"] = "error" if at(block, "is_error") is True else "success"
                    yield {"nativeTool": {"id": text_at(block, "tool_use_id"), "name": name, "output": flat(at(block, "content")), "status": status}}
        elif kind == "result":
            turn.done = True
            if text_at(line, "subtype") != "success" or at(line, "is_error") is True:
                raise RuntimeError(text_at(line, "result") or text_at(line, "subtype"))
            yield from turn.close()
            read, written = int_at(line, "usage", "cache_read_input_tokens"), int_at(line, "usage", "cache_creation_input_tokens")
            used_in, used_out = int_at(line, "usage", "input_tokens") + read + written, int_at(line, "usage", "output_tokens")
            yield {"metadata": {"usage": {"inputTokens": used_in, "outputTokens": used_out, "totalTokens": used_in + used_out, "cacheReadInputTokens": read, "cacheWriteInputTokens": written}, "metrics": {"latencyMs": int_at(line, "duration_ms")}}}
            yield {"messageStop": {"stopReason": "end_turn"}}


class AntigravityModel(CliModel):
    """Antigravity CLI (`agy -p`), signed in with the owner's Google account (owner, 2026-11-21).

    `agy` has no system prompt flag, so a new conversation's first message starts with the instructions. Its MCP config
    is per workspace (`<workspace>/.agents/mcp_config.json`, https://antigravity.google/docs/mcp.md), so each process runs
    in its own folder with `--add-dir` for the shared workspace; every `ren` tool is eager, so it is declared to the model
    as `mcp_ren_<tool>`, and `timeoutSeconds: -1` "turns off both limits" (a Strands tool may run past the 180 s default).
    A headless run "soft-denies" any tool that needs approval, so `--dangerously-skip-permissions` lets its tools run.
    No thinking text is streamed: the stream carries only thinking-token counts.
    """

    bridge_prefix = "mcp_ren_"

    def argv(self, session: Session, conversation: str | None, instructions: str | None) -> tuple[list[str], str]:
        config = {"mcpServers": {"ren": {**session.mcp_server(), "timeoutSeconds": -1, "tools": {name: {"eager": True} for name in session.tool_names}}}}
        (session.folder / ".agents").mkdir(parents=True, exist_ok=True)
        (session.folder / ".agents" / "mcp_config.json").write_text(json.dumps(config))
        argv = [
            "agy", "-p", "", "--input-format", "stream-json", "--output-format", "stream-json", "--dangerously-skip-permissions",
            "--add-dir", WORKSPACE, *(["--conversation", conversation] if conversation else []),
        ]
        if self.config["model_id"] != DEFAULT_MODEL:
            argv += ["--model", self.config["model_id"]]
        return argv, str(session.folder)

    def user_line(self, blocks: list[ContentBlock], instructions: str | None) -> dict[str, Json]:
        # "`text` is the only supported block type."
        texts = [block["text"] for block in blocks if "text" in block]
        content: list[Json] = [{"type": "text", "text": text} for text in ([instructions] if instructions else []) + texts]
        return {"event": "user", "message": {"content": content}}

    def translate(self, line: Json, session: Session, state: dict[str, object], turn: Turn) -> Iterator[CliStreamEvent]:
        kind = text_at(line, "event")
        if conversation := text_at(line, "conversation_id") or text_at(line, kind, "conversation_id"):
            state["conversation"] = conversation
        if kind == "step_update":
            step = at(line, "step_update")
            index, done = int_at(step, "step_index"), text_at(step, "state") == "DONE"
            if text_at(step, "step_type") == "agent_response":
                if delta := text_at(step, "text_delta"):
                    if index not in turn.blocks:
                        yield turn.open(index, "text")
                    yield {"contentBlockDelta": {"delta": {"text": delta}}}
                if done and turn.blocks.pop(index, ""):
                    yield {"contentBlockStop": {}}
            elif text_at(step, "step_type") == "tool":
                name = text_at(step, "tool_info", "name") or text_at(step, "tool_name")
                tool_id = f"{state.get('conversation')}:{index}"
                if name.startswith(self.bridge_prefix):
                    return
                if tool_id not in session.native:
                    session.native[tool_id] = name
                    yield {"nativeTool": {"id": tool_id, "name": name, "input": json.dumps(at(step, "tool_info", "parameters")), "status": "running"}}
                if done:
                    session.native.pop(tool_id, None)
                    error = at(step, "tool_info", "error")
                    output = flat(at(step, "tool_info", "output")) if error is None else text_at(error, "message") or flat(error)
                    yield {"nativeTool": {"id": tool_id, "name": name, "output": output, "status": "success" if error is None else "error"}}
        elif kind == "result":
            result = at(line, "result")
            turn.done = True
            if text_at(result, "status") != "SUCCESS":
                raise RuntimeError(text_at(result, "error", "message") or flat(at(result, "error")) or text_at(result, "status"))
            yield from turn.close()
            if not turn.streamed and (response := text_at(result, "response")):
                yield turn.open(-1, "text")
                yield {"contentBlockDelta": {"delta": {"text": response}}}
                yield from turn.close()
            total_in, total_out = int_at(result, "usage", "input_tokens"), int_at(result, "usage", "output_tokens") + int_at(result, "usage", "thinking_tokens")
            used_in, used_out = total_in - session.used[0], total_out - session.used[1]
            session.used = (total_in, total_out)
            yield {"metadata": {"usage": {"inputTokens": used_in, "outputTokens": used_out, "totalTokens": used_in + used_out}, "metrics": {"latencyMs": 0}}}
            yield {"messageStop": {"stopReason": "end_turn"}}


def cli_model(name: CliName, model_id: str, effort: str | None) -> CliModel:
    if name == "claude-code":
        return ClaudeCodeModel(model_id, effort if effort in CLAUDE_EFFORTS else None)
    return AntigravityModel(model_id, None)


async def cli_models(name: CliName) -> list[dict[str, str]]:
    """The CLI's models: Claude Code's aliases, or what `agy models` lists (a slug, then its name, on each line)."""
    if name == "claude-code":
        return [{"id": model_id, "owned_by": "anthropic", "provider": name} for model_id in CLAUDE_MODELS]
    process = await asyncio.create_subprocess_exec("agy", "models", stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    out, _ = await process.communicate()
    slugs = [line.split(maxsplit=1)[0] for line in out.decode(errors="replace").splitlines() if line.strip() and not line.startswith(" ")]
    return [{"id": model_id, "owned_by": "google", "provider": name} for model_id in [DEFAULT_MODEL, *slugs]]
