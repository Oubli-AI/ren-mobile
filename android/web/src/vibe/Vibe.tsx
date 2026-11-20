/*
 * Vibe on the phone: the studio, where the user makes music, songs, sound effects, speech and voices with Gemini and
 * ElevenLabs (deploy/vm/gemini/studio.py on the workspace), and ren desktop's Vibe agent beside it. Everything made is
 * kept in the user's Vibe library, the agents' Vibe tools included, and a voice from the studio can become Ren's voice.
 *
 * Live music is Lyria RealTime on ren's own session (`/gemini/vibe/music`), played by ren's music sink; the rest is one
 * request each (`/gemini/vibe/library`). The prompt is ren's PromptInput, as on ren's other pages; the music, voices and
 * library draw with ElevenLabs UI's components (src/elevenlabs).
 */
import type { ElevenLabs } from "@elevenlabs/elevenlabs-js"
import { Play, Trash2 } from "lucide-react"
import { useCallback, useEffect, useRef, useState } from "react"
import { useMusicPlayback } from "@/components/agent-panel/useMusicPlayback"
import {
  PromptInput,
  PromptInputBody,
  PromptInputFooter,
  PromptInputSubmit,
  PromptInputTextarea,
  PromptInputTools,
  type PromptInputMessage,
} from "@/components/ai-elements/prompt-input"
import { SuperAgentInterface } from "@/components/superagent/SuperAgentInterface"
import {
  AudioPlayerButton,
  AudioPlayerDuration,
  AudioPlayerProgress,
  AudioPlayerProvider,
  AudioPlayerTime,
  useAudioPlayer,
} from "@/components/ui/audio-player"
import { Button } from "@/components/ui/button"
import { Orb } from "@/components/ui/orb"
import { VoicePicker } from "@/components/ui/voice-picker"
import { Waveform } from "@/components/ui/waveform"
import { API_BASE_URL } from "@/lib/api-base"
import { EMPTY_MUSIC_STATE, reduceMusicEvent, type MusicState } from "@/lib/voice-events"
import { cn } from "@/utils/cn"
import { setRenVoice, vibe, type RenVoice } from "../host"

type Provider = "gemini" | "elevenlabs"
type Kind = "music" | "song" | "sound" | "speech" | "voice"

/** One thing made in Vibe (studio.Item). */
interface Item { id: string; kind: Kind; provider: Provider; title: string; prompt: string; mime: string; voice: string | null }

const MODES: { id: Kind | "agent"; label: string; providers: Provider[]; placeholder: string }[] = [
  { id: "music", label: "Live music", providers: ["gemini"], placeholder: "Describe the music, then steer it as it plays…" },
  { id: "song", label: "Song", providers: ["gemini", "elevenlabs"], placeholder: "A song: genre, mood, instruments, lyrics…" },
  { id: "sound", label: "Sound", providers: ["elevenlabs"], placeholder: "A sound effect: rain on a tin roof, a door creaking…" },
  { id: "speech", label: "Speech", providers: ["gemini", "elevenlabs"], placeholder: "What to say…" },
  { id: "voice", label: "Voice", providers: ["gemini", "elevenlabs"], placeholder: "A voice: age, timbre, accent, how it speaks…" },
  { id: "agent", label: "Agent", providers: [], placeholder: "" },
]

const PROVIDERS: Record<Provider, string> = { gemini: "Gemini", elevenlabs: "ElevenLabs" }
const STUDIO = `${API_BASE_URL}/gemini/vibe`

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? Object.fromEntries(Object.entries(value)) : null
}

function field(value: Record<string, unknown>, key: string): string {
  const it = value[key]
  return typeof it === "string" ? it : ""
}

function itemOf(value: unknown): Item[] {
  const it = record(value)
  const kind = it?.["kind"]
  const provider = it?.["provider"]
  if (it === null || (kind !== "music" && kind !== "song" && kind !== "sound" && kind !== "speech" && kind !== "voice")) return []
  if (provider !== "gemini" && provider !== "elevenlabs") return []
  return [{ id: field(it, "id"), kind, provider, title: field(it, "title"), prompt: field(it, "prompt"), mime: field(it, "mime"), voice: field(it, "voice") || null }]
}

/** A studio voice as ElevenLabs UI's VoicePicker takes one. */
function voiceOf(value: unknown): ElevenLabs.Voice[] {
  const it = record(value)
  if (it === null || !field(it, "id")) return []
  const labels = Object.fromEntries(Object.entries({ accent: field(it, "accent"), gender: field(it, "gender"), description: field(it, "description") }).filter(([, label]) => label))
  const preview = field(it, "preview_url")
  return [{ voiceId: field(it, "id"), name: field(it, "name"), labels, description: field(it, "description"), ...(preview ? { previewUrl: preview } : {}) }]
}

async function studio(path: string, init?: RequestInit): Promise<unknown> {
  const response = await fetch(`${STUDIO}${path}`, init)
  const body: unknown = await response.json().catch(() => null)
  if (!response.ok) throw new Error(field(record(body) ?? {}, "detail") || `The studio answered ${response.status}`)
  return body
}

export function Vibe() {
  const [mode, setMode] = useState<Kind | "agent">("music")
  const [provider, setProvider] = useState<Provider>("gemini")
  const [user, setUser] = useState<string | null>(null)
  const [renVoice, setRenVoiceState] = useState<RenVoice | null>(null)
  const [items, setItems] = useState<Item[]>([])
  const [error, setError] = useState<string | null>(null)
  const [making, setMaking] = useState(false)
  const [voices, setVoices] = useState<ElevenLabs.Voice[]>([])
  const [voice, setVoice] = useState("")
  const shown = MODES.find((it) => it.id === mode) ?? MODES[0]
  const providers = shown?.providers ?? []
  const using = providers.includes(provider) ? provider : providers[0] ?? "gemini"

  useEffect(() => {
    void vibe().then((answer) => {
      if (answer === null) return
      setUser(answer.user)
      setRenVoiceState(answer.voice)
    })
  }, [])

  useEffect(() => {
    if (user === null) return
    studio(`/library?user_id=${encodeURIComponent(user)}`)
      .then((body) => setItems(Array.isArray(body) ? body.flatMap(itemOf) : []))
      .catch((failure: unknown) => setError(String(failure)))
  }, [user])

  // Speech and Voice pick from the provider's voices, the user's designed ones first.
  useEffect(() => {
    if (user === null || (mode !== "speech" && mode !== "voice")) return
    setVoice("")
    studio(`/voices?user_id=${encodeURIComponent(user)}&provider=${using}`)
      .then((body) => setVoices(Array.isArray(body) ? body.flatMap(voiceOf) : []))
      .catch((failure: unknown) => setError(String(failure)))
  }, [user, mode, using, items.length])

  const kept = useCallback((item: Item) => setItems((list) => [item, ...list]), [])

  const make = async ({ text }: PromptInputMessage) => {
    if (user === null || mode === "agent" || mode === "music" || !text.trim()) return
    setMaking(true)
    setError(null)
    try {
      const body = await studio("/library", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ user_id: user, kind: mode, provider: using, prompt: text, voice }),
      })
      itemOf(body).forEach(kept)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setMaking(false)
    }
  }

  const choose = (chosen: RenVoice) => {
    setRenVoice(chosen)
    setRenVoiceState(chosen)
  }

  const forget = async (item: Item) => {
    if (user === null) return
    await studio(`/library/${item.id}?user_id=${encodeURIComponent(user)}`, { method: "DELETE" }).catch(() => null)
    setItems((list) => list.filter((it) => it.id !== item.id))
  }

  const picked = voices.find((it) => it.voiceId === voice)

  return (
    <div className="h-full overflow-y-auto bg-surface-0 dark:bg-surface-900">
      <nav aria-label="Vibe" className="overflow-x-auto px-4 pt-3">
        <div className="flex w-max gap-1">
          {MODES.map((it) => (
            <button
              key={it.id}
              type="button"
              onClick={() => setMode(it.id)}
              className={cn(
                "h-8 rounded-lg px-3 text-[13px] font-semibold transition-colors",
                it.id === mode ? "bg-blurple-500 text-white dark:bg-blurple-400 dark:text-ink" : "text-ink-secondary dark:text-ink-inverse-secondary",
              )}
            >
              {it.label}
            </button>
          ))}
        </div>
      </nav>
      {mode === "agent" ? (
        <div className="h-[calc(100%-44px)]">
          <SuperAgentInterface />
        </div>
      ) : (
        <AudioPlayerProvider>
          <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-4">
            {providers.length > 1 && (
              <div className="flex gap-2">
                {providers.map((it) => (
                  <Button key={it} size="sm" variant={it === using ? "default" : "outline"} onClick={() => setProvider(it)}>
                    {PROVIDERS[it]}
                  </Button>
                ))}
              </div>
            )}
            {mode === "voice" && (
              <div className="mx-auto size-40">
                <Orb colors={["#8D98FE", "#5543FF"]} agentState={making ? "thinking" : null} />
              </div>
            )}
            {(mode === "speech" || mode === "voice") && (
              <div className="flex flex-col gap-2">
                <VoicePicker voices={voices} value={voice} onValueChange={setVoice} placeholder={mode === "speech" ? "Pick a voice to speak in" : "Pick a voice for Ren"} />
                {picked !== undefined && (
                  <Button size="sm" variant="outline" onClick={() => choose({ provider: using, id: picked.voiceId, name: picked.name ?? picked.voiceId })}>
                    Make {picked.name} Ren's voice
                  </Button>
                )}
                <p className="text-xs text-ink-muted dark:text-ink-inverse-muted">
                  Ren's voice: {renVoice === null ? "the default" : `${renVoice.name} (${PROVIDERS[renVoice.provider]})`}
                </p>
              </div>
            )}
            {mode === "music" ? (
              <LiveMusic user={user ?? ""} onKept={kept} />
            ) : (
              <Prompt placeholder={shown?.placeholder ?? ""} busy={making} disabled={user === null || (mode === "speech" && !voice)} onSubmit={make} />
            )}
            {error !== null && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}
            {user !== null && <Library user={user} items={items} onForget={forget} onChoose={choose} />}
          </div>
        </AudioPlayerProvider>
      )}
    </div>
  )
}

function Prompt({ placeholder, busy, disabled, onSubmit }: { placeholder: string; busy: boolean; disabled: boolean; onSubmit: (message: PromptInputMessage) => void }) {
  return (
    <PromptInput
      onSubmit={onSubmit}
      className="ron-field rounded-xl border border-surface-200 bg-surface-0 transition-colors duration-150 focus-within:border-blurple-500 dark:border-surface-800 dark:bg-surface-900 dark:focus-within:border-blurple-400 [&>[data-slot=input-group]]:rounded-xl [&>[data-slot=input-group]]:border-0 [&>[data-slot=input-group]]:shadow-none dark:[&>[data-slot=input-group]]:bg-transparent"
    >
      <PromptInputBody>
        <PromptInputTextarea
          placeholder={placeholder}
          disabled={disabled || busy}
          className="min-h-12 px-4 pt-3.5 text-[14px] text-ink placeholder:text-ink-muted dark:text-ink-inverse dark:placeholder:text-ink-inverse-muted"
        />
      </PromptInputBody>
      <PromptInputFooter className="px-3 pb-2.5 pt-1">
        <PromptInputTools />
        <PromptInputSubmit className="rounded-lg" status={busy ? "submitted" : "ready"} disabled={disabled || busy} />
      </PromptInputFooter>
    </PromptInput>
  )
}

/** Lyria RealTime: each prompt steers the stream, and a take is kept in the library. */
function LiveMusic({ user, onKept }: { user: string; onKept: (item: Item) => void }) {
  const socket = useRef<WebSocket | null>(null)
  const [music, setMusic] = useState<MusicState>(EMPTY_MUSIC_STATE)
  const [bars, setBars] = useState<number[]>([])
  const [prompt, setPrompt] = useState("")
  const { start, stop, close, play, frequencyData } = useMusicPlayback(() => {})

  useEffect(() => {
    let frame = 0
    const draw = () => {
      const heard = frequencyData()
      setBars((shown) => (heard === null ? (shown.length === 0 ? shown : []) : Array.from(heard.data.slice(0, 64), (level) => level / 255)))
      frame = requestAnimationFrame(draw)
    }
    frame = requestAnimationFrame(draw)
    return () => {
      cancelAnimationFrame(frame)
      socket.current?.close()
      close()
    }
  }, [frequencyData, close])

  const send = (message: Record<string, unknown>) => {
    const open = socket.current
    if (open !== null && open.readyState <= WebSocket.OPEN) {
      if (open.readyState === WebSocket.OPEN) open.send(JSON.stringify(message))
      else open.addEventListener("open", () => open.send(JSON.stringify(message)), { once: true })
      return
    }
    // The app keeps the workspace token as a cookie on the backend, which a WebSocket carries.
    const opened = new WebSocket(`${STUDIO.replace(/^http/, "ws")}/music?user_id=${encodeURIComponent(user)}`)
    opened.onmessage = (event: MessageEvent<string>) => {
      const data = record(JSON.parse(event.data))
      if (data === null) return
      if (data["type"] === "music_audio_delta") play(data)
      if (data["type"] === "vibe_saved") itemOf(data["item"]).forEach(onKept)
      setMusic((state) => reduceMusicEvent(state, data))
    }
    opened.onclose = () => setMusic((state) => ({ ...state, connected: false, playback: "idle" }))
    opened.addEventListener("open", () => opened.send(JSON.stringify(message)), { once: true })
    socket.current = opened
  }

  const steer = async ({ text }: PromptInputMessage) => {
    if (!text.trim()) return
    await start()
    setPrompt(text)
    send({ prompts: [{ text, weight: 1 }] })
  }

  const playing = music.playback === "playing"
  return (
    <div className="flex flex-col gap-3">
      <Waveform data={bars} height={96} barWidth={3} barGap={2} className="rounded-xl bg-surface-100 dark:bg-surface-850" />
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="outline" disabled={!music.connected} onClick={() => send({ action: playing ? "pause" : "play" })}>
          {playing ? "Pause" : "Play"}
        </Button>
        <Button size="sm" variant="outline" disabled={!music.connected} onClick={() => { send({ action: "stop" }); stop() }}>
          Stop
        </Button>
        <Button size="sm" disabled={music.take.seconds <= 0} onClick={() => send({ save: prompt })}>
          Keep take ({Math.round(music.take.seconds)} s)
        </Button>
        {music.error !== undefined && <span className="text-sm text-red-600 dark:text-red-400">{music.error}</span>}
      </div>
      <Prompt placeholder={MODES[0]?.placeholder ?? ""} busy={false} disabled={false} onSubmit={(message) => void steer(message)} />
    </div>
  )
}

/** The user's Vibe library, newest first: played with ElevenLabs UI's audio player. */
function Library({ user, items, onForget, onChoose }: { user: string; items: Item[]; onForget: (item: Item) => void; onChoose: (voice: RenVoice) => void }) {
  const player = useAudioPlayer()
  if (items.length === 0) return null
  return (
    <section aria-label="Library" className="flex flex-col gap-2">
      <h2 className="text-sm font-semibold text-ink dark:text-ink-inverse">Library</h2>
      {player.activeItem !== null && (
        <div className="flex items-center gap-3">
          <AudioPlayerTime className="text-xs" />
          <AudioPlayerProgress className="flex-1" />
          <AudioPlayerDuration className="text-xs" />
        </div>
      )}
      {items.map((item) => (
        <Track key={item.id} user={user} item={item} onForget={onForget} onChoose={onChoose} />
      ))}
    </section>
  )
}

/**
 * One kept item. Its audio is behind the workspace token, which ElevenLabs UI's player (an anonymous-CORS audio
 * element) can't send, so it's fetched with the token and played from a blob the first time it's asked for.
 */
function Track({ user, item, onForget, onChoose }: { user: string; item: Item; onForget: (item: Item) => void; onChoose: (voice: RenVoice) => void }) {
  const player = useAudioPlayer()
  const [src, setSrc] = useState<string | null>(null)
  useEffect(() => () => { if (src !== null) URL.revokeObjectURL(src) }, [src])

  const load = async () => {
    const response = await fetch(`${STUDIO}/library/${item.id}?user_id=${encodeURIComponent(user)}`)
    if (!response.ok) return
    const url = URL.createObjectURL(await response.blob())
    setSrc(url)
    await player.play({ id: item.id, src: url })
  }

  return (
    <div className="flex items-center gap-3 rounded-xl border border-surface-200 px-3 py-2 dark:border-surface-800">
      {src === null ? (
        <Button size="icon-sm" variant="outline" aria-label="Play" onClick={() => void load()}>
          <Play />
        </Button>
      ) : (
        <AudioPlayerButton size="icon-sm" variant="outline" item={{ id: item.id, src }} />
      )}
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-ink dark:text-ink-inverse">{item.title}</p>
        <p className="text-xs text-ink-muted dark:text-ink-inverse-muted">
          {MODES.find((it) => it.id === item.kind)?.label} · {PROVIDERS[item.provider]}
        </p>
      </div>
      {item.kind === "voice" && item.voice !== null && (
        <Button size="sm" variant="ghost" onClick={() => onChoose({ provider: item.provider, id: item.voice ?? "", name: item.title })}>
          Ren's voice
        </Button>
      )}
      <Button size="icon-sm" variant="ghost" aria-label="Delete" onClick={() => onForget(item)}>
        <Trash2 />
      </Button>
    </div>
  )
}
