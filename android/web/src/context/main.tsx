/*
 * ren desktop's Context widget on the phone: its orb and panel (InterestsWidget) and what feeds its Context Agent
 * (ContextBridge), unmodified, on a page the app lays over the browser. As on the desktop the agent learns from the
 * pages and searches the user visits, here the phone's browser (`visit`, `search`), and from the phone itself: chats
 * with Ren there (`chat`), every notification another app posts (`notification`, the Context Agent's [notification]
 * turn), and the apps the user used, their calls and texts and what Ren did there (`phone`, its [phone] turn). What the
 * user adds to Ren's context from the browser (`context-add`) is saved as ren desktop's context menu saves it.
 *
 * It shows only for a signed-in ren account that finished the interview, on either device: the same account, context,
 * interview and interest graph as ren desktop's. The page tells the app what to make room for (`layout`): nothing, the orb, the orb with a finished
 * run's or a dream's notice, or the open panel, which the dream's review checklist takes too.
 */
import { askRen, onApp, reportAccount, tell } from "../host"
import { applyTheme } from "../theme"
import "../page.css"
import "@/index.css"
import "./context.css"
import { StrictMode, useEffect } from "react"
import { createRoot } from "react-dom/client"
import { ContextBridge } from "@/components/interests/ContextBridge"
import { InterestsWidget } from "@/components/interests/InterestsWidget"
import { AgentUiProvider, useAgentUi } from "@/context/AgentUiContext"
import { runNotifications } from "@/lib/context-api"
import { useAuthStore } from "@/stores/authStore"
import { useContextAgentStore } from "@/stores/contextAgentStore"
import { useInterestsStore } from "@/stores/interestsStore"
import { useOnboardingStore } from "@/stores/onboardingStore"
import { useAccountProfileReady, useAccountProfileSync } from "@/stores/accountProfileStore"

applyTheme()

reportAccount()

onApp((event) => {
  const store = useContextAgentStore.getState()
  if (event.type === "visit") store.observeVisit(event.url, event.title)
  else if (event.type === "search") store.observeSearch(event.query)
  else if (event.type === "chat") store.observeChat(event.channel, event.user, event.assistant)
  else if (event.type === "notification") void store.alertNotification(event.notification)
  else if (event.type === "phone") void store.reportPhone(event.activity)
  else if (event.type === "context-add") {
    // As ContextBridge's onContextAdd: the widget opens on its Context tab, where the item lands.
    const { payload } = event
    useInterestsStore.getState().setExpanded(true)
    store.setTab("context")
    if (payload.kind === "screenshot") {
      void fetch(payload.text ?? "").then((response) => response.blob()).then((blob) => store.uploadContext({
        kind: "screenshot", blob, name: `screenshot-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-")}.png`,
        title: payload.title ? `Screenshot: ${payload.title}` : "Screenshot", source: payload.url,
      }))
    } else {
      void store.addContext({ kind: payload.kind, title: payload.title, url: payload.url, text: payload.text ?? "" })
    }
  }
  else if (event.type === "close") {
    useInterestsStore.getState().setExpanded(false)
    store.setReviewingDream(false)
  }
  else void useAuthStore.getState().logout()
})

/**
 * What the widget opens in Ren (Ask Ren on saved context, a suggested agent) goes to the app's agent panel: the
 * attachments as cards in its composer, a prompt sent with them. ren desktop's own panel reads the same queues.
 */
function AskRenBridge() {
  const { agentPanelQueuedPrompts, agentPanelQueuedAttachments, consumeAgentPanelPrompt, consumeAgentPanelAttachments } = useAgentUi()
  useEffect(() => {
    if (!agentPanelQueuedPrompts.length && !agentPanelQueuedAttachments.length) return
    askRen(consumeAgentPanelPrompt() ?? "", consumeAgentPanelAttachments())
  }, [agentPanelQueuedPrompts, agentPanelQueuedAttachments, consumeAgentPanelPrompt, consumeAgentPanelAttachments])
  return null
}

function Widget() {
  const signedIn = useAuthStore((state) => state.isAuthenticated && state.user !== null)
  const surveyComplete = useOnboardingStore((state) => state.isComplete)
  const expanded = useInterestsStore((state) => state.isExpanded)
  const notice = useContextAgentStore((state) => runNotifications(state.runs).length > 0 || state.dream !== null)
  const reviewing = useContextAgentStore((state) => state.dream !== null && state.reviewingDream)
  // The interview and interest graph follow the account, so an interview taken on the desktop counts here.
  useAccountProfileSync()
  const profileReady = useAccountProfileReady()
  const ready = signedIn && surveyComplete && profileReady
  const layout = !ready ? "none" : expanded || reviewing ? "panel" : notice ? "notice" : "orb"
  useEffect(() => tell("layout", { layout }), [layout])
  if (!ready) return null
  // ren desktop's BrowserLayout gives the widget the agent UI's context: a finished run's Review opens the panel.
  return (
    <AgentUiProvider>
      <ContextBridge />
      <AskRenBridge />
      <InterestsWidget />
    </AgentUiProvider>
  )
}

const root = document.getElementById("root")
if (root === null) throw new Error("The page has no root element.")

createRoot(root).render(
  <StrictMode>
    <Widget />
  </StrictMode>,
)
