"""Emrys's Strands agents for the phone: the harness for text and a BidiAgent for voice, behind FastAPI WebSockets.

The text agent runs on Gemini, NVIDIA NIM, Mistral, Telnyx or xAI, the phone's choice in Settings › Agents (`provider`,
`model`, the provider's default when left out, and `effort`, the reasoning level or `auto`). NIM and Mistral models are
built the way ren builds them, as is xAI's on ren's own `RonXAIModel`, and Telnyx's the way ren builds NIM's, on Telnyx's
OpenAI-compatible API (`providers.py`);
`GET /gemini/models` and `GET /gemini/efforts` list each provider's live models and each model's reasoning levels.
Voice is always Gemini Live; its helpers run on the chosen text model.

Text: the phone opens `/gemini/text` with the conversation's id, the provider, text model and effort, `user_id` and the
eight `perm_*` as query parameters, then sends each message as text. Every message runs the harness's `stream_async`; the phone gets the
model's reasoning as it streams (`{"reasoning": …}`, Gemini's thoughts included), the text as it streams (`{"data": …}`), each finished message (`{"message": …}`), and how the turn ended (`{"stop_reason": …,
"interrupts": […]}`). The reply after a `handoff_to_user` goes back as its `interruptResponse`.

Voice: the phone opens `/gemini/voice` with the same parameters and streams its microphone as 16 kHz PCM frames; it
gets Strands' bidi events as they are (`bidi_audio_delta` at 24 kHz, transcripts, `bidi_barge_in`, tool uses). The
subagent tool and `web_fetch` run their children on the text model, since the Live model only talks.

The instructions are the ElevenLabs agents' own prompts, read when the conversation opens and filled in the way
ElevenLabs fills its dynamic variables.

Work mode: with `work` (a folder on the user's own computer) the instructions end with `work_prompt`, and the agent
works there through the hub's `local_*` tools, Desktop Commander on that computer (`deploy/local/computer.py`), as a
coding agent works in a terminal. Every agent has those tools whenever the computer is attached; work mode says to use
them.

Ren's voice, chosen in Vibe: Live talks in it (`voice`) when it is one of Google's studio voices; any other (a designed
Gemini voice, an ElevenLabs voice) reads each typed reply aloud (`voice` and `voice_provider` on `/gemini/text`, sent as
`{"speech": <base64>, "mime": …}` after the turn), since Live speaks only the studio voices.

Vibe: `/gemini/vibe/*` is Vibe's studio (`studio.py`) for the app's Vibe page: the user's library, what to make in it,
the voices, and Lyria RealTime on ren's own music session. Both agents have the same studio as tools.

Memory is ren desktop's own (`agent/api/core/dreaming.py` in the image's copy of ren): both agents search the user's
second brain and keep a journal in the folder `phone`, and the dream files the journal into the brain once the user
has been idle. `user_id` is the signed-in ren account's (the install's when signed out) and the storage is the `ren`
service's bucket, so the brain is the one ren's pages and the Context widget use. The text agent's turns are journaled
and what it remembers is injected before each turn, as the SDK does both; the voice agent, a `BidiAgent`, has the two
tools.
"""

import asyncio
import base64
import os
import re
from collections.abc import AsyncIterator, Mapping
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from elevenlabs import AsyncElevenLabs
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel
from pydantic_core import to_jsonable_python
from strands import tool
from strands.bidi import BidiAgent
from strands.bidi.models.google import GoogleGeminiLiveModel
from strands.bidi.types.io import InputStream, OutputStream
from strands.bidi.types.agent import BidiAgentInput
from strands.bidi.types.events import BidiOutputEvent
from strands.bidi.types.media import AudioDelta
from strands.interrupt import Interrupt
from strands.memory import MemoryManager
from strands.models import Model
from strands.models.gemini import GeminiModel
from strands.models.routing import ModelRouter, RoutingCandidate
from strands.multiagent.spec import Fixed
from strands.tools.mcp import MCPClient, MCPServerConfig, ToolFilters
from strands.types.agent import AgentInput
from strands.types.interrupt import InterruptResponse, InterruptResponseContent
from strands.types.tools import AgentTool, ToolContext
from strands.vended_tools import (
    edit,
    graph,
    handoff_to_user,
    http_request,
    make_mcp_router,
    make_subagent,
    make_web_fetch,
    notebook,
    python_repl,
    read,
    shell,
    sleep,
    stop,
    swarm,
    write,
)
from strands_harness import BuiltinToolsConfig, Effort, create_harness
from strands_harness.models import EFFORT_LEVELS
from strands_harness.tools.programmatic_tool_caller import programmatic_tool_caller

from agent.api.core import dreaming
from agent.api.core.live_music import LiveMusicSession, validate_config, validate_prompts
from agent.api.core.brave_tools import brave_tools
from agent.api.core.web_tools import web_tools
from agent.api.core.xai_model import XAIStatePruner

import studio
from providers import ProviderName, build_model, list_provider_models, provider_api_key, provider_default_model, reasoning_efforts

VOICE_AGENT = "agent_2901m620nzwrev2r7ekqpydcxe25"
TEXT_AGENT = "agent_9601m623qa72ffs9cyy3ezc0p6tb"
VOICE_MODEL = "gemini-3.8-live"
# Google's prebuilt voice described as "Smooth", like the ElevenLabs voice agent's "Eric - Smooth, Trustworthy".
VOICE = "Algieba"
PERMISSIONS = (
    "perm_use_apps",
    "perm_send_messages",
    "perm_read_messages",
    "perm_purchases",
    "perm_install_apps",
    "perm_cloud_coding",
    "perm_deploy",
    "perm_create_music",
)
HOME = "/config/emrys-gemini"
# The phone's Strands agents' folder in the user's second brain and journal.
MEMORY_FOLDER = "phone"
# Every agent here searches and reads the web with ren desktop's web tools (`web_tools()`: strands_tools' Tavily, Exa and
# Bright Data tools and Olostep's MCP server, each where its key is set), whatever its model. ren's Brave tools
# (`brave_tools()`) join only with `RON_BRAVE_TOOLS=1`.
# The harness's built-ins for Gemini. Its native web search is Google Search grounding, which a Gemini API key on the
# free tier is refused (429 on every request that carries it); `GEMINI_WEB_SEARCH=0` leaves it out for such a key.
GEMINI_BUILTINS: BuiltinToolsConfig = {"web_search": os.getenv("GEMINI_WEB_SEARCH", "1") != "0"}

# The ElevenLabs agents' MCP servers plus Ren's hub, unprefixed so the tool names match the prompt. The hub serves
# the phone's tools under their own names, ren desktop's under `desktop_` and the user's computer's under `local_`,
# whichever is attached.
SERVERS: dict[str, MCPServerConfig] = {
    "ren-hub": {"url": "http://hub:8300/mcp", "prefix": ""},
    "emrys-computer": {"url": "http://computer:8080/computer/mcp", "prefix": ""},
    "oubliai-telnyx": {
        "url": "https://oubli-catolabs.fastmcp.app/mcp",
        "headers": {"Authorization": "${TELNYX_MCP_AUTHORIZATION}"},
        "prefix": "",
    },
}

# Strands' Gemini Live model validates each tool's input schema as a genai `Schema` (`_format_tools_for_live_api`):
# `$schema`, `$ref` and `$defs` fail there and `additionalProperties` reaches the Live API, which refuses the session
# with 1007. Tools whose schema carries them (swarm, graph, some MCP tools) stay off voice.
UNDECLARABLE_SCHEMA_KEYS = frozenset({"additionalProperties", "$schema", "$ref", "$defs"})


def gemini_live_undeclarable(tool: AgentTool, **_: object) -> bool:
    """Whether Strands cannot declare this tool to Gemini Live (also an MCPClient ``tool_filters`` callable)."""

    def clean(value: object) -> bool:
        if isinstance(value, dict):
            return not UNDECLARABLE_SCHEMA_KEYS & value.keys() and all(clean(item) for item in value.values())
        if isinstance(value, list):
            return all(clean(item) for item in value)
        return True

    return not clean(tool.tool_spec["inputSchema"]["json"])


#: A server config's tool_filters are regexes; MCPClient's own also take a callable.
VOICE_TOOL_FILTERS: ToolFilters = {"rejected": [gemini_live_undeclarable]}


#: The agents' prompts come from their ElevenLabs agents when ELEVENLABS_API_KEY is set; without it, from
#: PROMPTS_DIR/<agent id>.md, a copy of each agent's system prompt (owner, 2026-11-20: ElevenLabs is not in use).
elevenlabs = AsyncElevenLabs(api_key=key) if (key := os.environ.get("ELEVENLABS_API_KEY")) else None
PROMPTS_DIR = Path(os.environ.get("PROMPTS_DIR", "/opt/gemini/prompts"))


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    """Restart the dream countdowns a restart cut, as ren's own API does at startup."""
    await dreaming.resume_dreams()
    yield


app = FastAPI(lifespan=lifespan)
# Vibe (ren://vibe) is one of the app's bundled pages, which call from that origin with the workspace token.
app.add_middleware(CORSMiddleware, allow_origins=[os.environ.get("CORS_ORIGINS", "https://appassets.androidplatform.net")], allow_methods=["*"], allow_headers=["*"])


def dream_model() -> ModelRouter:
    """The dream's models in ren's order, built as this service builds NIM and Mistral."""
    return ModelRouter(
        [RoutingCandidate(build_model(provider, model), name=f"{provider}:{model}") for provider, model in dreaming.DREAM_MODELS if provider_api_key(provider)]
    )


# ren builds the dream's models with its panel, which this image doesn't carry.
dreaming.dream_model = dream_model


async def instructions(agent_id: str, values: Mapping[str, str]) -> str:
    """The agent's prompt (from ElevenLabs, or else its copy in PROMPTS_DIR) with its `{{variables}}` filled in."""
    if elevenlabs is None:
        prompt = (PROMPTS_DIR / f"{agent_id}.md").read_text(encoding="utf-8")
    else:
        config = (await elevenlabs.conversational_ai.agents.get(agent_id)).conversation_config.agent
        if config is None or config.prompt is None or config.prompt.prompt is None:
            raise RuntimeError(f"{agent_id} has no prompt")
        prompt = config.prompt.prompt
    return re.sub(r"\{\{(\w+)\}\}", lambda found: values.get(found.group(1), found.group(0)), prompt)


def work_prompt(folder: str | None) -> str:
    """Work mode's instructions: work in [folder] on the user's computer through its `local_*` tools; none when off."""
    if not folder:
        return ""
    return f"""

## Work mode
You are working on the user's own computer, in {folder}, the way a coding agent works in a terminal. The computer's
files, terminal and processes are your `local_*` tools (Desktop Commander on that computer); your own shell and file
tools run on Ren's workspace computer, not the user's, so use the `local_*` tools for this work. If you have no
`local_*` tools, the user's computer isn't connected to Ren: say so and stop.
- Look before you change: `local_list_directory`, `local_start_search`, `local_read_file`.
- Change files with `local_edit_block` (an exact, small replacement) and `local_write_file` (new files).
- Run commands, builds and tests with `local_start_process`; follow long ones with `local_read_process_output` and
  `local_interact_with_process`.
- Finish the task, check it works, then say what you changed and what you ran."""


def variables(websocket: WebSocket, text_only: bool) -> dict[str, str]:
    """The dynamic variables the prompt names, as ElevenLabs sets them for a conversation in the Emrys app."""
    query = websocket.query_params
    return {
        "system__channel": "unknown",
        "system__is_text_only": "true" if text_only else "false",
        "user_id": query.get("user_id", ""),
        **{name: query.get(name, "prompt") for name in PERMISSIONS},
    }


@tool(context=True)
def end_conversation(tool_context: ToolContext[BidiAgent]) -> str:
    """End the conversation when the user asks to stop or says goodbye."""
    tool_context.agent.cancel()
    return "Ending conversation."


class PhoneAudio(InputStream):
    """The phone's microphone, one WebSocket frame of 16 kHz PCM at a time."""

    def __init__(self, websocket: WebSocket) -> None:
        self.websocket = websocket

    async def __call__(self) -> BidiAgentInput:
        return AudioDelta(format="pcm", source={"bytes": await self.websocket.receive_bytes()})


class Phone(OutputStream):
    """Every bidi event to the phone, as JSON."""

    def __init__(self, websocket: WebSocket) -> None:
        self.websocket = websocket

    async def __call__(self, event: BidiOutputEvent) -> None:
        await self.websocket.send_json(to_jsonable_python(event, fallback=str))


def text_model(provider: ProviderName, model: str, effort: str) -> Model:
    """The text model the phone chose: Gemini as Strands builds it, NIM, Mistral, Telnyx and xAI as `providers.py` does.

    A level the model doesn't list (one the phone kept from an earlier model) runs on Auto.
    """
    if provider == "gemini":
        return GeminiModel(model_id=model)
    return build_model(provider, model, effort if effort in reasoning_efforts(provider, model) else None)


def harness_effort(effort: str) -> Effort:
    """The phone's reasoning level as the harness names it; it checks the level against the Gemini model itself."""
    for level in EFFORT_LEVELS:
        if level == effort:
            return level
    raise ValueError(f"Unknown effort {effort!r}")


@app.get("/gemini/models")
async def models(provider: ProviderName) -> dict[str, object]:
    """The provider's live model catalogue and its default model, as ren's `GET /models` lists them."""
    return {"models": await asyncio.to_thread(list_provider_models, provider), "default": provider_default_model(provider)}


@app.get("/gemini/efforts")
async def efforts(provider: ProviderName, model: str) -> dict[str, list[str]]:
    """The model's reasoning levels, lowest first, as ren's `GET /models/{id}/efforts` answers them."""
    return {"efforts": list(reasoning_efforts(provider, model))}


@app.websocket("/gemini/voice")
async def voice(
    websocket: WebSocket,
    user_id: str,
    provider: ProviderName = "gemini",
    model: str | None = None,
    effort: str = "auto",
    work: str | None = None,
    voice: str = VOICE,
) -> None:
    await websocket.accept()
    helper = text_model(provider, model or provider_default_model(provider), effort)
    agent = BidiAgent(
        model=GoogleGeminiLiveModel(model_id=VOICE_MODEL, voice=voice),
        system_prompt=await instructions(VOICE_AGENT, variables(websocket, text_only=False)) + work_prompt(work) + dreaming.JOURNAL_DIRECTIVE,
        tools=[
            *(MCPClient(
                url=config["url"],
                headers={key: os.path.expandvars(value) for key, value in config.get("headers", {}).items()},
                prefix=config["prefix"],
                tool_filters=VOICE_TOOL_FILTERS,
                continue_on_error=True,
            ) for config in SERVERS.values()),
            # swarm and graph are left out: their schemas carry `additionalProperties` (see gemini_live_undeclarable).
            *(value for value in (
                programmatic_tool_caller,
                shell,
                read,
                write,
                edit,
                make_web_fetch(model=helper),
                python_repl,
                http_request,
                notebook,
                sleep,
                make_subagent(model=Fixed(helper)),
                make_mcp_router(servers=SERVERS),
                *brave_tools(),
                *web_tools(),
                *MemoryManager(stores=dreaming.agent_memory(user_id, MEMORY_FOLDER), injection=False).tools,
                *studio.VibeTools(user_id).tools,
                end_conversation,
            ) if not gemini_live_undeclarable(value)),
        ],
    )
    try:
        await agent.run(inputs=[PhoneAudio(websocket)], outputs=[Phone(websocket)])
    except WebSocketDisconnect:
        pass


@app.websocket("/gemini/text")
async def text(
    websocket: WebSocket,
    conversation: str,
    user_id: str,
    provider: ProviderName = "gemini",
    model: str | None = None,
    effort: str = "auto",
    work: str | None = None,
    voice: str | None = None,
    voice_provider: studio.Provider = "gemini",
) -> None:
    await websocket.accept()
    chosen = model or provider_default_model(provider)
    agent = create_harness(
        model=f"google/{chosen}" if provider == "gemini" else text_model(provider, chosen, effort),
        effort=harness_effort(effort) if provider == "gemini" else "auto",
        instructions=await instructions(TEXT_AGENT, variables(websocket, text_only=True)) + work_prompt(work) + dreaming.JOURNAL_DIRECTIVE,
        tools=[python_repl, http_request, notebook, sleep, stop, handoff_to_user, swarm, graph, make_mcp_router(servers=SERVERS), *brave_tools(), *web_tools(), *studio.VibeTools(user_id).tools],
        builtin_tools=GEMINI_BUILTINS if provider == "gemini" else None,
        mcp_servers=SERVERS,
        session={"id": conversation, "dir": f"{HOME}/sessions"},
        memory=MemoryManager(stores=dreaming.agent_memory(user_id, MEMORY_FOLDER)),
    )
    if isinstance(agent.model, GeminiModel):
        # Gemini thinks either way but sends its thoughts only when asked to, which the harness doesn't ask.
        config = agent.model.get_config()
        params = config.get("params", {})
        thinking = params.get("thinking_config", {})
        agent.model.update_config(model_id=config["model_id"], params={**params, "thinking_config": {**thinking, "include_thoughts": True}})
    if provider == "xai":
        # As ren adds it: strands_xai appends the whole encrypted protobuf history to every assistant turn and
        # only ever reads the newest copy; keep one so history/snapshots/token estimates stay linear.
        agent.hooks.add_hook(XAIStatePruner())
    pending: list[Interrupt] = []
    try:
        async with agent:
            while True:
                said = await websocket.receive_text()
                prompt: AgentInput = (
                    [InterruptResponseContent(interruptResponse=InterruptResponse(interruptId=interrupt.id, response=said)) for interrupt in pending]
                    if pending
                    else said
                )
                try:
                    async for event in agent.stream_async(prompt):
                        if "reasoningText" in event:
                            await websocket.send_json({"reasoning": event["reasoningText"]})
                        elif "data" in event:
                            await websocket.send_json({"data": event["data"]})
                        elif "message" in event:
                            await websocket.send_json({"message": to_jsonable_python(event["message"], bytes_mode="base64")})
                        elif "result" in event:
                            pending = list(event["result"].interrupts or [])
                            await websocket.send_json(
                                {"stop_reason": event["result"].stop_reason, "interrupts": [str(interrupt.reason) for interrupt in pending]}
                            )
                            reply = str(event["result"]).strip()
                            if voice and reply:
                                # Ren's voice is one the user chose in Vibe that Gemini Live can't speak: the reply is read in it.
                                spoken, mime = await studio.speech(voice_provider, reply, voice)
                                await websocket.send_json({"speech": base64.b64encode(spoken).decode("ascii"), "mime": mime})
                except WebSocketDisconnect:
                    raise
                except Exception as error:
                    # A turn the provider failed (no quota, a model it has retired) is told to the phone as it is,
                    # and the conversation stays open for the next message.
                    await websocket.send_json({"error": f"{type(error).__name__}: {error}"})
    except WebSocketDisconnect:
        pass


class Make(BaseModel):
    """What Vibe asks the studio to make (`studio.create`)."""

    user_id: str
    kind: studio.Kind
    provider: studio.Provider
    prompt: str
    title: str = ""
    voice: str = ""
    style: str = ""
    seconds: float | None = None


@app.get("/gemini/vibe/library")
async def vibe_library(user_id: str) -> list[studio.Item]:
    return studio.library(user_id)


@app.post("/gemini/vibe/library")
async def vibe_make(make: Make) -> studio.Item:
    try:
        return await studio.create(make.user_id, make.kind, make.provider, make.prompt, make.title, make.voice, make.style, make.seconds)
    except ValueError as error:
        raise HTTPException(400, str(error)) from error
    except Exception as error:
        # The provider's own refusal (a quota, a key, a filtered prompt), as it said it.
        raise HTTPException(502, f"{type(error).__name__}: {error}") from error


@app.get("/gemini/vibe/library/{item}")
async def vibe_audio(user_id: str, item: str) -> FileResponse:
    try:
        path, record = studio.audio(user_id, item)
    except (FileNotFoundError, ValueError) as error:
        raise HTTPException(404, "Not in the library") from error
    return FileResponse(path, media_type=record.mime)


@app.delete("/gemini/vibe/library/{item}")
async def vibe_forget(user_id: str, item: str) -> None:
    try:
        studio.forget(user_id, item)
    except (FileNotFoundError, ValueError) as error:
        raise HTTPException(404, "Not in the library") from error


@app.get("/gemini/vibe/voices")
async def vibe_voices(user_id: str, provider: studio.Provider) -> list[studio.Voice]:
    try:
        return await studio.voices(provider, user_id)
    except Exception as error:
        raise HTTPException(502, f"{type(error).__name__}: {error}") from error


@app.websocket("/gemini/vibe/music")
async def vibe_music(websocket: WebSocket, user_id: str) -> None:
    """Lyria RealTime for Vibe, on ren's own session: the page sends `{"prompts": [...]}`, `{"config": {...}}`,
    `{"action": "play" | "pause" | "stop" | "reset_context"}` or `{"save": <title>}` and gets ren's `music_*` frames,
    the audio as `music_audio_delta` (48 kHz stereo PCM16), and `{"type": "vibe_saved", "item": ...}` for a kept take."""
    await websocket.accept()
    session = LiveMusicSession(websocket.send_json)
    try:
        while True:
            asked = await websocket.receive_json()
            try:
                if "prompts" in asked:
                    await session.set_weighted_prompts(validate_prompts(asked["prompts"]))
                    # As ren's music_set_weighted_prompts does: the first prompts on an idle stream play at once.
                    if session.playback == "idle":
                        await session.playback_control("play")
                elif "config" in asked:
                    await session.set_music_generation_config(validate_config(asked["config"]))
                elif "action" in asked:
                    await session.playback_control(asked["action"])
                elif "save" in asked:
                    prompt = ", ".join(prompt["text"] for prompt in session.prompts)
                    item = studio.keep(user_id, "music", "gemini", prompt, asked["save"], bytes(session.recorder.wav()), "audio/wav")
                    session.recorder.clear()
                    await websocket.send_json({"type": "vibe_saved", "item": item.model_dump()})
            except WebSocketDisconnect:
                raise
            except Exception as error:
                await websocket.send_json({"type": "music_error", "message": f"{type(error).__name__}: {error}"})
    except WebSocketDisconnect:
        pass
    finally:
        await session.close()


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8100)
