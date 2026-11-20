"""Vibe's studio: songs, sounds, speech and voices made with Gemini and ElevenLabs, kept in the user's library.

Gemini, on the Gemini API key (`GOOGLE_API_KEY`) with google-genai as its docs show it:
- Lyria RealTime is ren's own `LiveMusicSession` (`agent/api/core/live_music.py` in the image's copy of ren), which the
  Vibe page drives over `/gemini/vibe/music` (app.py); a take is kept from its recorder.
- Lyria 3.5 songs are ren's `lyria_generate` (`agent/api/core/music_generation.py`).
- Voice design is `voices.create` with a description (https://ai.google.dev/gemini-api/docs/voice-design), and speech
  is `interactions.create` on `gemini-3.8-flash-tts` with a built-in or designed voice
  (https://ai.google.dev/gemini-api/docs/speech-generation).

ElevenLabs, on `ELEVENLABS_TOOLS_API_KEY` (its tools only; the ElevenLabs agents aren't involved): voice design
(`text_to_voice.design`, then `text_to_voice.create` from its first preview), speech (`text_to_speech`), music
(`music.compose`) and sound effects (`text_to_sound_effects`).

What is made is kept under `<HOME>/vibe/<user>/`: the audio and a JSON record beside it. The agents get the same
studio as tools (`VibeTools`), so what they make shows in Vibe too.
"""

import base64
import json
import os
import re
import uuid
from collections.abc import AsyncIterator
from datetime import datetime, timezone
from functools import cache
from pathlib import Path
from typing import Literal

from elevenlabs import AsyncElevenLabs
from google import genai
from google.genai.interactions import Interaction
from pydantic import BaseModel
from strands import tool

from agent.api.core.music_generation import lyria_generate

Kind = Literal["music", "song", "sound", "speech", "voice"]
Provider = Literal["gemini", "elevenlabs"]

LIBRARY = Path(os.environ.get("VIBE_DIR", "/config/emrys-gemini/vibe"))
TTS_MODEL = "gemini-3.8-flash-tts"
ELEVENLABS_TTS_MODEL = "eleven_v3"
ELEVENLABS_VOICE_MODEL = "eleven_ttv_v3"
#: What ElevenLabs returns, as ren's batch music keeps it.
ELEVENLABS_FORMAT = "mp3_44100_128"
USER = re.compile(r"[A-Za-z0-9_-]{1,64}")


class Item(BaseModel):
    """One thing made in Vibe."""

    id: str
    kind: Kind
    provider: Provider
    title: str
    prompt: str
    mime: str
    created_at: str
    #: The voice a voice item made, or the one speech was spoken in.
    voice: str | None = None


class Voice(BaseModel):
    """A voice the studio can speak in."""

    id: str
    name: str
    provider: Provider
    description: str = ""
    gender: str = ""
    accent: str = ""
    #: True for a voice the user designed; Gemini Live speaks only Google's built-in voices.
    designed: bool = False
    preview_url: str | None = None


@cache
def gemini() -> genai.Client:
    """One client for the service: a client closes its connections when it is collected, mid-request for a temporary one."""
    return genai.Client(http_options={"api_version": "v1beta"})


@cache
def elevenlabs() -> AsyncElevenLabs:
    key = os.environ.get("ELEVENLABS_TOOLS_API_KEY")
    if not key:
        raise RuntimeError("ElevenLabs isn't set up on the server: ELEVENLABS_TOOLS_API_KEY is missing.")
    return AsyncElevenLabs(api_key=key)


def folder(user: str) -> Path:
    if not USER.fullmatch(user):
        raise ValueError("Unknown user")
    path = LIBRARY / user
    path.mkdir(parents=True, exist_ok=True)
    return path


def keep(user: str, kind: Kind, provider: Provider, prompt: str, title: str, audio: bytes, mime: str, voice: str | None = None) -> Item:
    """Adds [audio] to the user's library."""
    item = Item(
        id=uuid.uuid4().hex,
        kind=kind,
        provider=provider,
        title=title.strip() or prompt.strip()[:60] or kind.capitalize(),
        prompt=prompt,
        mime=mime,
        created_at=datetime.now(timezone.utc).isoformat(),
        voice=voice,
    )
    path = folder(user)
    (path / item.id).write_bytes(audio)
    (path / f"{item.id}.json").write_text(item.model_dump_json())
    return item


def library(user: str) -> list[Item]:
    """The user's library, newest first."""
    items = [Item.model_validate_json(path.read_text()) for path in folder(user).glob("*.json")]
    return sorted(items, key=lambda item: item.created_at, reverse=True)


def audio(user: str, item: str) -> tuple[Path, Item]:
    record = folder(user) / f"{item}.json"
    if not USER.fullmatch(item) or not record.exists():
        raise FileNotFoundError(item)
    return folder(user) / item, Item.model_validate_json(record.read_text())


def forget(user: str, item: str) -> None:
    path, _ = audio(user, item)
    path.unlink()
    (folder(user) / f"{item}.json").unlink()


async def joined(chunks: AsyncIterator[bytes]) -> bytes:
    return b"".join([chunk async for chunk in chunks])


async def speech(provider: Provider, text: str, voice: str, style: str = "") -> tuple[bytes, str]:
    """[text] spoken in [voice]: a WAV from Gemini, an MP3 from ElevenLabs."""
    if provider == "elevenlabs":
        spoken = elevenlabs().text_to_speech.convert(voice_id=voice, text=text, model_id=ELEVENLABS_TTS_MODEL, output_format=ELEVENLABS_FORMAT)
        return await joined(spoken), "audio/mpeg"
    interaction = await gemini().aio.interactions.create(
        model=TTS_MODEL,
        input=[{"type": "user_input", "content": [{"type": "text", "text": text, "annotations": [{"type": "speech_metadata", "style": style}] if style else []}]}],
        response_format={"type": "audio"},
        generation_config={"speech_config": [{"voice": voice}]},
        stream=False,
    )
    output = interaction.output_audio if isinstance(interaction, Interaction) else None
    if output is None or not output.data:
        raise RuntimeError("Gemini returned no audio.")
    return base64.b64decode(output.data), output.mime_type or "audio/wav"


async def create(user: str, kind: Kind, provider: Provider, prompt: str, title: str = "", voice: str = "", style: str = "", seconds: float | None = None) -> Item:
    """Makes [kind] from [prompt] with [provider] and keeps it. Speech speaks [prompt] in [voice]."""
    if not prompt.strip():
        raise ValueError("Describe what to make.")
    if kind == "speech":
        if not voice:
            raise ValueError("Pick a voice to speak in.")
        spoken, mime = await speech(provider, prompt, voice, style)
        return keep(user, kind, provider, prompt, title, spoken, mime, voice)
    if kind == "voice" and provider == "gemini":
        made = await gemini().aio.voices.create(
            store=True,
            voice={"type": "prompted", "model": TTS_MODEL, "display_name": title.strip() or prompt.strip()[:40], "prompted": {"input": prompt}},
        )
        if not made.id or made.sample_audio is None:
            raise RuntimeError("Gemini designed no voice.")
        return keep(user, kind, provider, prompt, made.display_name or title, base64.b64decode(made.sample_audio.data), made.sample_audio.mime_type, made.id)
    if kind == "voice":
        client = elevenlabs()
        design = await client.text_to_voice.design(voice_description=prompt, model_id=ELEVENLABS_VOICE_MODEL, auto_generate_text=True)
        preview = design.previews[0]
        name = title.strip() or prompt.strip()[:40]
        saved = await client.text_to_voice.create(voice_name=name, voice_description=prompt, generated_voice_id=preview.generated_voice_id)
        return keep(user, kind, provider, prompt, name, base64.b64decode(preview.audio_base_64), preview.media_type, saved.voice_id)
    if kind == "song" and provider == "gemini":
        song = await lyria_generate(prompt)
        return keep(user, kind, provider, prompt, title, song["audio"], song["mime_type"])
    if kind == "song":
        length = None if seconds is None else int(seconds * 1000)
        composed = elevenlabs().music.compose(prompt=prompt, music_length_ms=length, output_format=ELEVENLABS_FORMAT)
        return keep(user, kind, provider, prompt, title, await joined(composed), "audio/mpeg")
    if kind == "sound":
        sound = elevenlabs().text_to_sound_effects.convert(text=prompt, duration_seconds=seconds, output_format=ELEVENLABS_FORMAT)
        return keep(user, kind, "elevenlabs", prompt, title, await joined(sound), "audio/mpeg")
    raise ValueError("Live music is made on the music stream, not here.")


async def voices(provider: Provider, user: str) -> list[Voice]:
    """The voices [provider] speaks in, the user's designed ones first."""
    designed = {item.voice: item for item in library(user) if item.kind == "voice" and item.provider == provider and item.voice}
    if provider == "elevenlabs":
        found = await elevenlabs().voices.search(page_size=100)
        listed = [
            Voice(
                id=voice.voice_id,
                name=voice.name or voice.voice_id,
                provider=provider,
                description=voice.description or "",
                gender=(voice.labels or {}).get("gender") or "",
                accent=(voice.labels or {}).get("accent") or "",
                designed=voice.voice_id in designed,
                preview_url=voice.preview_url,
            )
            for voice in found.voices
        ]
    else:
        # The 30 studio voices Gemini Live also speaks are the catalogue's voices with a bare name for an id ("kore");
        # the Extended Voice Library's ids carry their language ("en-us-arlo"). A designed voice is listed from the
        # user's library, since the API lists every voice designed on the project.
        catalogue = await gemini().aio.voices.list(page_size=1000, language_code=["en-US"], type_=["prebuilt"])
        listed = [
            Voice(id=item.voice, name=item.title, provider=provider, description=item.prompt, designed=True)
            for item in designed.values()
            if item.voice
        ] + [
            # Named as the speech and Live docs name them ("Kore"), which is what Live's voice_name takes.
            Voice(
                id=voice.display_name or voice.id,
                name=voice.display_name or voice.id,
                provider=provider,
                description=voice.description or "",
                gender=voice.gender or "",
                accent=voice.accent or "",
            )
            for voice in catalogue.voices or []
            if voice.id and "-" not in voice.id
        ]
    return sorted(listed, key=lambda voice: not voice.designed)


class VibeTools:
    """The studio as the agents' tools, for one user; what they make is kept in that user's Vibe library."""

    def __init__(self, user: str) -> None:
        self.user = user

    @property
    def tools(self) -> list[object]:
        return [self.vibe_create, self.vibe_voices, self.vibe_library]

    @tool
    async def vibe_create(
        self,
        kind: Literal["song", "sound", "speech", "voice"],
        prompt: str,
        provider: Literal["gemini", "elevenlabs"] = "gemini",
        title: str = "",
        voice: str = "",
        style: str = "",
        seconds: float = 0,
    ) -> str:
        """Make audio for the user and keep it in their Vibe library, where they can play it.

        Args:
            kind: song (music with vocals or instrumental: Gemini's Lyria 3.5 or ElevenLabs Music), sound (a sound
                effect, ElevenLabs), speech (prompt spoken aloud in a voice), or voice (design a new voice from a
                description of its age, timbre, accent and delivery).
            prompt: What to make; for speech, the words to speak.
            provider: gemini or elevenlabs. Sound is always elevenlabs.
            title: A title for the library.
            voice: For speech, a voice id from vibe_voices of the same provider.
            style: For Gemini speech, how to say it, such as "whispered" or "cheerful".
            seconds: For an ElevenLabs song or sound, its length; 0 lets the model choose.
        """
        item = await create(self.user, kind, provider, prompt, title, voice, style, seconds or None)
        return f"Made and kept in Vibe: {item.model_dump_json()}"

    @tool
    async def vibe_voices(self, provider: Literal["gemini", "elevenlabs"] = "gemini") -> str:
        """The voices speech can use with a provider, the user's designed voices first.

        Args:
            provider: gemini or elevenlabs.
        """
        return json.dumps([voice.model_dump() for voice in await voices(provider, self.user)])

    @tool
    async def vibe_library(self) -> str:
        """What the user has made in Vibe, newest first."""
        return json.dumps([item.model_dump() for item in library(self.user)])
