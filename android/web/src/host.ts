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
import type { PhoneAction, PhoneActivity, PhoneApp } from "@/lib/context-api"
import { useAuthStore } from "@/stores/authStore"
import type { Favorite } from "@/stores/favoritesStore"
import { useOnboardingStore } from "@/stores/onboardingStore"

/** What the app answers: `{"type":"token","token":…}` or `{"type":"favorites","favorites":[…]}`. */
type Answer = { type: "token"; token: string } | { type: "favorites"; favorites: Favorite[] }

/**
 * What the app tells the Context widget's page unasked: a page the phone's browser loaded, what the user did on the
 * phone since the last report, and the panel's Back and Settings' Sign out.
 */
export type AppEvent =
  | { type: "visit"; url: string; title: string }
  | { type: "phone"; activity: PhoneActivity }
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
  return app !== null && typeof app.name === "string" && typeof app.minutes === "number" ? [{ name: app.name, minutes: app.minutes }] : []
}

function phoneAction(value: unknown): PhoneAction[] {
  const action = record(value)
  return action !== null && typeof action.text === "string" && typeof action.at === "number" ? [{ text: action.text, at: action.at }] : []
}

function eventOf(data: string): AppEvent | null {
  const message = record(JSON.parse(data))
  if (message === null) return null
  if (message.type === "visit" && typeof message.url === "string" && typeof message.title === "string") {
    return { type: "visit", url: message.url, title: message.title }
  }
  const activity = record(message.activity)
  if (message.type === "phone" && activity !== null && typeof activity.since === "number" && typeof activity.until === "number"
    && Array.isArray(activity.apps) && Array.isArray(activity.actions)) {
    const apps: unknown[] = activity.apps
    const actions: unknown[] = activity.actions
    return { type: "phone", activity: { since: activity.since, until: activity.until, apps: apps.flatMap(phoneApp), actions: actions.flatMap(phoneAction) } }
  }
  if (message.type === "close" || message.type === "sign-out") return { type: message.type }
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
  return null
}

const host = window.RenHost
if (host !== undefined) {
  host.onmessage = (event) => {
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

/** Gives the app the favorite links as the page left them. */
export function saveFavorites(list: Favorite[]) {
  host?.postMessage(JSON.stringify({ type: "favorites", favorites: list }))
}

/** The app's token for the backend, asked for once; null outside the app, where there is none to add. */
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
    const report = JSON.stringify({ type: "account", signedIn: isAuthenticated && user !== null, email: user?.email ?? "", surveyComplete: useOnboardingStore.getState().isComplete })
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
