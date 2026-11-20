/*
 * What ren desktop's BrowserLayout lays over every page, for the phone's mounts of ren's pages: the agent UI's
 * context, ren's agent panel (a page's Ask Ren and agent actions open it), the Live Music deck, the frame shown while
 * Ren views the screen, and the repair review's bridge. On the desktop they share one window with every tab; here each
 * bundled page carries them.
 *
 * ren's pages open tabs through its tab store, where Electron makes them browser tabs. Here a web page or a search
 * opens in a new tab of the phone's browser (`window.open`, which the browser turns into a tab), and one of ren's own
 * pages (a project, Build, Documents) stays in this page, where the agent UI it was handed lives.
 */
import { useEffect, type ReactNode } from "react"
import { AgentPanel } from "@/components/agent-panel/AgentPanel"
import { MusicSidePanel } from "@/components/agent-panel/MusicSidePanel"
import { RepairBridge } from "@/components/agent-panel/RepairBridge"
import { ScreenVisionOverlay } from "@/components/agent-panel/ScreenVisionOverlay"
import { AgentUiProvider } from "@/context/AgentUiContext"
import { useAuthStore } from "@/stores/authStore"
import { useContextAgentStore } from "@/stores/contextAgentStore"
import { useTabStore } from "@/stores/tabStore"

/** ren desktop's addresses for its own pages, and the phone's for the ones the phone's browser opens. */
const RON = "ron://"
const SEARCH = "ron://search"

/** The phone's address for one of ren's: `ron://search?…` is `ren://search?…`. */
export function phoneAddress(url: string): string {
  return url.startsWith(RON) ? `ren://${url.slice(RON.length)}` : url
}

/**
 * Follows ren's tab store as the phone's browser: a new tab for a web page or a search opens in the browser, and a
 * search the page moves to (Search deeper) loads in this tab. `stays` says which of ren's own addresses this page
 * draws itself; any other opens in the browser.
 */
export function followTabs(stays: (url: string) => boolean) {
  useTabStore.subscribe((state, previous) => {
    for (const it of state.tabs) {
      const before = previous.tabs.find((other) => other.id === it.id)
      if (before === undefined) {
        if (/^https?:\/\//.test(it.url) || (it.url.startsWith(RON) && !stays(it.url))) {
          window.open(phoneAddress(it.url))
          useTabStore.getState().closeTab(it.id)
        } else if (state.activeTabId !== it.id) {
          useTabStore.getState().setActiveTab(it.id)
        }
      } else if (it.url !== before.url && it.url.startsWith(SEARCH) && !stays(it.url)) {
        window.location.assign(phoneAddress(it.url))
      }
    }
  })
}

export function Shell({ children }: { children: ReactNode }) {
  // What a page adds to Ren's context goes to the signed-in account's, as ContextBridge sets it on the desktop.
  const userId = useAuthStore((state) => state.user?.id || "default")
  const setUser = useContextAgentStore((state) => state.setUser)
  useEffect(() => setUser(userId), [userId, setUser])
  return (
    <AgentUiProvider>
      <div className="relative h-full">
        {children}
        <AgentPanel />
        <MusicSidePanel />
        <ScreenVisionOverlay />
        <RepairBridge />
      </div>
    </AgentUiProvider>
  )
}
