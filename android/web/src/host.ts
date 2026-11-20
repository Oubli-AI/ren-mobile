/*
 * What Electron supplies to ren's pages on the desktop, supplied by the app on the phone.
 *
 * ren desktop reaches its backend on loopback. The phone reaches the same backend on the workspace VM, behind a token
 * the app holds: the page asks for it through the RenHost web message listener (browser/Browser.kt, which answers only
 * the app's bundled pages) and adds it to its requests to that backend as X-Ren-Token, which leaves Authorization to
 * ren's own code (a ren account's session). The same listener carries the
 * favorite links, which the app keeps for its home page and Discover alike.
 */
import { API_BASE_URL } from "@/lib/api-base"
import type { PhoneAction, PhoneActivity, PhoneApp, PhoneCall, PhoneNotification, PhoneSms } from "@/lib/context-api"
import type { ContextAddPayload } from "@/pages/types/electron"
import type { PromptAttachment } from "@/lib/prompt-attachments"
import { THEME_OPTIONS, type ThemeName } from "@/lib/theme"
import { useAuthStore } from "@/stores/authStore"
import type { Favorite } from "@/stores/favoritesStore"
import { useOnboardingStore } from "@/stores/onboardingStore"

/** The voice Ren speaks in, chosen in Vibe and kept by the app. */
export interface RenVoice { provider: "gemini" | "elevenlabs"; id: string; name: string }

/** What the app answers: `{"type":"token","token":…}`, `{"type":"favorites","favorites":[…]}` or `{"type":"theme","theme":…}`. */
type Answer =
  | { type: "token"; token: string }
  | { type: "vibe"; user: string; voice: RenVoice | null }
  | { type: "favorites"; favorites: Favorite[] }
  | { type: "theme"; theme: ThemeName }
  | { type: "oauth-redirect"; url: string }

/**
 * What the app tells the Context widget's page unasked: a page the phone's browser loaded, a search there, a chat
 * with Ren on the phone, a notification another app posted, what happened on the phone since the last report, and the
 * panel's Back and Settings' Sign out, and what the user added to Ren's context from the browser (a selection, a link, an
 * image, a video, or a screenshot of the page, which comes as a data URL in `text`), as ren desktop's context menu does.
 */
export type AppEvent =
  | { type: "visit"; url: string; title: string }
  | { type: "search"; query: string }
  | { type: "chat"; channel: string; user: string; assistant: string }
  | { type: "notification"; notification: PhoneNotification }
  | { type: "phone"; activity: PhoneActivity }
  | { type: "context-add"; payload: ContextAddPayload }
  | { type: "close" }
  | { type: "sign-out" }

const waiting = new Map<Answer["type"], (answer: Answer) => void>()

function isFavorite(value: unknown): value is Favorite {
  return (
    typeof value === "object" &&
    value !== null &&
    "id" in value &&
    typeof value.id === "string" &&
    "title" in value &&
    typeof value.title === "string" &&
    "url" in value &&
    typeof value.url === "string"
  )
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? Object.fromEntries(Object.entries(value)) : null
}

function phoneApp(value: unknown): PhoneApp[] {
  const app = record(value)
  return app !== null && typeof app["name"] === "string" && typeof app["minutes"] === "number"
    ? [{ name: app["name"], minutes: app["minutes"] }]
    : []
}

function phoneAction(value: unknown): PhoneAction[] {
  const action = record(value)
  return action !== null && typeof action["text"] === "string" && typeof action["at"] === "number"
    ? [{ text: action["text"], at: action["at"] }]
    : []
}

function text(value: Record<string, unknown>, key: string): string {
  const field = value[key]
  return typeof field === "string" ? field : ""
}

function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function phoneCall(value: unknown): PhoneCall[] {
  const call = record(value)
  const seconds = call?.["seconds"]
  return call !== null && typeof seconds === "number"
    ? [{ kind: text(call, "kind"), number: text(call, "number"), name: text(call, "name") || null, seconds, at: text(call, "at") }]
    : []
}

function phoneSms(value: unknown): PhoneSms[] {
  const sms = record(value)
  return sms === null ? [] : [{
    from: text(sms, "from"), number: text(sms, "number"), contact: text(sms, "contact") || null, text: text(sms, "text"),
    at: text(sms, "at"), unread: sms["unread"] === true,
  }]
}

function phoneNotification(value: unknown): PhoneNotification | null {
  const note = record(value)
  if (note === null || !text(note, "key")) return null
  return {
    key: text(note, "key"), app: text(note, "app"), package: text(note, "package"), title: text(note, "title"),
    text: text(note, "text"), at: text(note, "at"), can_reply: note["can_reply"] === true, category: text(note, "category") || null,
    messages: list(note["messages"]).flatMap((entry) => {
      const message = record(entry)
      return message === null ? [] : [{ from: text(message, "from"), text: text(message, "text"), at: text(message, "at") }]
    }),
  }
}

function eventOf(data: string): AppEvent | null {
  const message = record(JSON.parse(data))
  if (message === null) return null
  if (message["type"] === "visit" && typeof message["url"] === "string" && typeof message["title"] === "string") {
    return { type: "visit", url: message["url"], title: message["title"] }
  }
  if (message["type"] === "search" && typeof message["query"] === "string") return { type: "search", query: message["query"] }
  if (message["type"] === "chat") {
    return { type: "chat", channel: text(message, "channel"), user: text(message, "user"), assistant: text(message, "assistant") }
  }
  const notification = message["type"] === "notification" ? phoneNotification(message["notification"]) : null
  if (notification !== null) return { type: "notification", notification }
  const activity = record(message["activity"])
  if (
    message["type"] === "phone" &&
    activity !== null &&
    typeof activity["since"] === "number" &&
    typeof activity["until"] === "number" &&
    Array.isArray(activity["apps"]) &&
    Array.isArray(activity["actions"])
  ) {
    const apps: unknown[] = activity["apps"]
    const actions: unknown[] = activity["actions"]
    return {
      type: "phone",
      activity: {
        since: activity["since"],
        until: activity["until"],
        apps: apps.flatMap(phoneApp),
        actions: actions.flatMap(phoneAction),
        calls: list(activity["calls"]).flatMap(phoneCall),
        sms: list(activity["sms"]).flatMap(phoneSms),
      },
    }
  }
  const add = message["type"] === "context-add" ? record(message["payload"]) : null
  if (add !== null) {
    const kind = add["kind"]
    if (kind === "selection" || kind === "link" || kind === "image" || kind === "video" || kind === "screenshot") {
      return { type: "context-add", payload: { kind, title: text(add, "title"), url: text(add, "url"), text: text(add, "text") } }
    }
  }
  if (message["type"] === "close" || message["type"] === "sign-out") return { type: message["type"] }
  return null
}

let listener: ((event: AppEvent) => void) | null = null

/** Hears what the app tells the page unasked. */
export function onApp(handler: (event: AppEvent) => void) {
  listener = handler
}

/** Tells the app something about the page (`type` names what), for the pages that report to it. */
export function tell(type: string, fields: Record<string, string | boolean>) {
  host?.postMessage(JSON.stringify({ ...fields, type }))
}

function answerOf(data: string): Answer | null {
  const message: unknown = JSON.parse(data)
  if (typeof message !== "object" || message === null || !("type" in message)) return null
  if (message.type === "token" && "token" in message && typeof message.token === "string") return { type: "token", token: message.token }
  if (message.type === "favorites" && "favorites" in message && Array.isArray(message.favorites)) {
    const favorites: unknown[] = message.favorites
    return { type: "favorites", favorites: favorites.filter(isFavorite) }
  }
  if (message.type === "vibe" && "user" in message && typeof message.user === "string") {
    const voice = "voice" in message ? record(message.voice) : null
    const provider = voice?.["provider"]
    return {
      type: "vibe",
      user: message.user,
      voice: voice !== null && (provider === "gemini" || provider === "elevenlabs") ? { provider, id: text(voice, "id"), name: text(voice, "name") } : null,
    }
  }
  if (message.type === "oauth-redirect" && "url" in message && typeof message.url === "string") return { type: "oauth-redirect", url: message.url }
  if (message.type === "theme" && "theme" in message) {
    const theme = THEME_OPTIONS.find((it) => it.id === message.theme)
    if (theme !== undefined) return { type: "theme", theme: theme.id }
  }
  return null
}

let oauthCallback: ((url: string) => void) | null = null

/** Hears the provider's redirect from the app's loopback listener, for ren's social sign-in (`src/oauth.ts`). */
export function onOAuthCallback(handler: (url: string) => void) {
  oauthCallback = handler
}

function oauthCallbackOf(data: string): string | null {
  const message = record(JSON.parse(data))
  return message !== null && message["type"] === "oauth-callback" && typeof message["url"] === "string" ? message["url"] : null
}

const host = window.RenHost
if (host !== undefined) {
  host.onmessage = (event) => {
    const callback = oauthCallbackOf(event.data)
    if (callback !== null) {
      oauthCallback?.(callback)
      return
    }
    const happened = eventOf(event.data)
    if (happened !== null) {
      listener?.(happened)
      return
    }
    const answer = answerOf(event.data)
    if (answer === null) return
    waiting.get(answer.type)?.(answer)
    waiting.delete(answer.type)
  }
}

/** Asks the app for one thing; null outside the app, which has nothing to give. */
function ask(type: Answer["type"]): Promise<Answer | null> {
  if (host === undefined) return Promise.resolve(null)
  return new Promise((resolve) => {
    waiting.set(type, resolve)
    host.postMessage(JSON.stringify({ type }))
  })
}

/** The favorite links the app keeps; null outside the app. */
export async function favorites(): Promise<Favorite[] | null> {
  const answer = await ask("favorites")
  return answer?.type === "favorites" ? answer.favorites : null
}

/** The theme chosen in the app's Settings (Light, Dark, Glass or System); null outside the app. */
export async function theme(): Promise<ThemeName | null> {
  const answer = await ask("theme")
  return answer?.type === "theme" ? answer.theme : null
}

/** The loopback address the app listens on for a social sign-in's redirect; null outside the app. */
export async function oauthRedirect(): Promise<string | null> {
  const answer = await ask("oauth-redirect")
  return answer?.type === "oauth-redirect" ? answer.url : null
}

/** Who Vibe's library belongs to (the app's install id, as the agents know the user) and Ren's voice; null outside the app. */
export async function vibe(): Promise<{ user: string; voice: RenVoice | null } | null> {
  const answer = await ask("vibe")
  return answer?.type === "vibe" ? { user: answer.user, voice: answer.voice } : null
}

/** Makes [voice] Ren's voice: the app keeps it for the agents' next conversation. */
export function setRenVoice(voice: RenVoice) {
  host?.postMessage(JSON.stringify({ type: "ren-voice", voice }))
}

/** Opens a social sign-in's provider page in the phone's browser. */
export function openOAuth(url: string) {
  host?.postMessage(JSON.stringify({ type: "oauth", url }))
}

/** Ask Ren from a page: the app opens its agent panel with [attachments] as cards in the composer and sends [text] when there is any. */
export function askRen(text: string, attachments: PromptAttachment[]) {
  host?.postMessage(JSON.stringify({ type: "ask", text, attachments }))
}

/** Gives the app the favorite links as the page left them. */
export function saveFavorites(list: Favorite[]) {
  host?.postMessage(JSON.stringify({ type: "favorites", favorites: list }))
}

/**
 * The app's token for the backend, asked for once; null outside the app, where there is none to add. The app also
 * keeps it as the cookie `ren_token` on the backend, for what can't carry a header: ren's WebSockets
 * (voice, Teach Ren, Live Music) and iframes (Build's live preview).
 */
const token = ask("token").then((answer) => (answer?.type === "token" ? answer.token : null))

const send = window.fetch.bind(window)

window.fetch = async (input, init) => {
  const request = new Request(input, init)
  if (new URL(request.url).origin !== API_BASE_URL) return send(request)
  const key = await token
  if (key !== null) request.headers.set("X-Ren-Token", key)
  return send(request)
}

/**
 * Keeps the app told which ren account the pages are signed in to and whether its interview is done, as ren desktop's
 * App.tsx reads them (`useAuthStore`, `useOnboardingStore`): the app shows the Context widget only for a signed-in
 * account that finished the interview. Starts ren's sign-in check as App.tsx does.
 */
export function reportAccount() {
  let last = ""
  const send = () => {
    if (!useAuthStore.persist.hasHydrated() || !useOnboardingStore.persist.hasHydrated()) return
    const { isAuthenticated, isInitialized, user } = useAuthStore.getState()
    if (!isInitialized) return
    const report = JSON.stringify({ type: "account", signedIn: isAuthenticated && user !== null, email: user?.email ?? "", userId: user?.id ?? "", surveyComplete: useOnboardingStore.getState().isComplete })
    if (report === last) return
    last = report
    host?.postMessage(report)
  }
  useAuthStore.subscribe(send)
  useOnboardingStore.subscribe(send)
  useAuthStore.persist.onFinishHydration(send)
  useOnboardingStore.persist.onFinishHydration(send)
  void useAuthStore.getState().initialize().then(send)
  send()
}
