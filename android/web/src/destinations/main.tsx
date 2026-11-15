/*
 * ren desktop's destinations on the phone: ren://discover, ren://research, ren://execute, ren://documents,
 * ren://calendar, ren://vibe and ren://build (the app loads this page with `?tab=` naming one). Each is drawn the way
 * ren desktop's App.tsx draws it, by HomePage on that tab or, for Research, Documents and the pages Execute opens, by
 * the page its tab's address names (MainContent in App.tsx), with the sign-in page behind `#signin` and the interests
 * interview behind `#onboarding` or, as App.tsx does, for a signed-in account that hasn't finished it. The app hears
 * which account is signed in (`reportAccount`), for the Context widget; the interview and interest graph follow that
 * account (`useAccountProfileSync`), so one taken on the desktop counts here.
 *
 * On the desktop these live among ren's tabs. Here they are one tab of the phone's browser, so ren's tab store holds
 * just this page, and what they ask of it becomes the browser's: a story opens in this tab (the article itself, where
 * the desktop shows its reader), a web page opened in a new tab opens in a new browser tab, and a page of ren's own
 * (a project, a document) opens here, in the tab store, as ren desktop's openInternalUrl does without Electron.
 */
import { favorites, reportAccount, saveFavorites } from "../host"
import "../page.css"
import "@/index.css"
import { StrictMode, useEffect, useState } from "react"
import { createRoot } from "react-dom/client"
import { DESTINATIONS, type Destination } from "@/components/navigation/destinations"
import { AgentUiProvider } from "@/context/AgentUiContext"
import { AuthPageLayout } from "@/layouts/AuthPageLayout"
import { parseResearchUrl, researchUrl } from "@/lib/deep-research-api"
import { documentsLibraryUrl, parseDocumentsLibraryUrl } from "@/lib/project-documents"
import { SIGN_IN_HASH, closeSignIn, markLaunched } from "@/lib/sign-in"
import { DeepResearchPage } from "@/pages/DeepResearchPage"
import { DocumentsPage } from "@/pages/DocumentsPage"
import { HomePage } from "@/pages/HomePage"
import { OnboardingPage } from "@/pages/OnboardingPage"
import { ProjectHomePage } from "@/pages/ProjectHomePage"
import { ProjectsIndexPage } from "@/pages/ProjectsIndexPage"
import { SignInPage } from "@/pages/SignInPage"
import { useAuthStore } from "@/stores/authStore"
import { useFavoritesStore } from "@/stores/favoritesStore"
import { useNavigationStore } from "@/stores/navigationStore"
import { useOnboardingStore } from "@/stores/onboardingStore"
import { useAccountProfileReady, useAccountProfileSync } from "@/stores/accountProfileStore"
import { useTabStore } from "@/stores/tabStore"
import { cn } from "@/utils/cn"

/** Home is the app's own page (ren://home); the rest are drawn here. */
const PAGES = DESTINATIONS.filter((it) => it.id !== "home")
const DISCOVER = PAGES.find((it) => it.id === "discover")
if (DISCOVER === undefined) throw new Error("ren desktop lists no Discover destination.")

/** ren desktop's reader address for a story (`readStoryUrl` in src/lib/articles.ts). */
const READ = "ron://read?"

const tab: Destination = PAGES.find((it) => it.id === new URLSearchParams(window.location.search).get("tab")) ?? DISCOVER

const dark = window.matchMedia("(prefers-color-scheme: dark)")
const applyTheme = () => document.documentElement.classList.toggle("dark", dark.matches)
applyTheme()
dark.addEventListener("change", applyTheme)

useNavigationStore.getState().setActiveTab(tab.id)
useTabStore.getState().setTabs([], null)
useTabStore.getState().createTab(tab.id === "documents" ? documentsLibraryUrl() : tab.id === "research" ? researchUrl() : "ron://home", true)
reportAccount()
useTabStore.subscribe((state, previous) => {
  for (const it of state.tabs) {
    const before = previous.tabs.find((other) => other.id === it.id)
    if (before === undefined) {
      if (/^https?:\/\//.test(it.url)) window.open(it.url)
      useTabStore.getState().closeTab(it.id)
    } else if (it.url !== before.url && it.url.startsWith(READ)) {
      const article = new URLSearchParams(it.url.slice(READ.length)).get("url")
      if (article !== null) window.location.assign(article)
    }
  }
})

// The favorites are the app's, the same list its home page shows under the search box.
void favorites().then((list) => {
  if (list === null) return
  useFavoritesStore.setState({ favorites: list })
  useFavoritesStore.subscribe((state, previous) => {
    if (state.favorites !== previous.favorites) saveFavorites(state.favorites)
  })
})

/** ren desktop's destinations side by side (Home is the app's page), the one on show lit. */
function Destinations() {
  const item = "inline-flex h-9 shrink-0 items-center gap-2 rounded-lg px-3.5 text-[13px] font-semibold transition-colors duration-150 ease-out"
  const other = cn(item, "text-ink-secondary dark:text-ink-inverse-secondary")
  return (
    <nav aria-label="Destinations" className="overflow-x-auto px-4 pt-4">
      <div className="mx-auto flex w-max gap-1 rounded-xl bg-surface-100 p-1 dark:bg-surface-850">
        {DESTINATIONS.map(({ id, label, icon: Icon }) =>
          id === tab.id ? (
            <span key={id} aria-current="page" className={cn(item, "bg-surface-0 text-ink shadow-sm dark:bg-surface-900 dark:text-ink-inverse")}>
              <Icon className="h-4 w-4" />
              {label}
            </span>
          ) : (
            <a key={id} href={`ren://${id}`} className={other}>
              <Icon className="h-4 w-4" />
              {label}
            </a>
          ),
        )}
      </div>
    </nav>
  )
}

/** What App.tsx's MainContent draws for the tab's address. */
function Content() {
  const url = useTabStore((state) => state.tabs.find((it) => it.id === state.activeTabId)?.url)
  const userId = useAuthStore((state) => state.user?.id)
  if (url === "ron://projects" || url === "ron://projects/") return <ProjectsIndexPage key={userId} />
  const project = url?.match(/^ron:\/\/project\/([^/?#]+)(?:\/docs\/([^/?#]+))?\/?$/)
  const projectKey = project?.[1]
  const documentId = project?.[2]
  if (projectKey !== undefined) {
    return documentId === undefined
      ? <ProjectHomePage key={`${userId}:${projectKey}`} projectKey={projectKey} />
      : <ProjectHomePage key={`${userId}:${projectKey}`} projectKey={projectKey} documentId={documentId} />
  }
  const research = parseResearchUrl(url)
  if (research) return <DeepResearchPage key={userId} sessionId={research.sessionId} />
  const documents = parseDocumentsLibraryUrl(url)
  if (documents) return <DocumentsPage key={userId} documentId={documents.documentId} />
  return <HomePage />
}

function Page() {
  const [hash, setHash] = useState(window.location.hash)
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated)
  const surveyComplete = useOnboardingStore((state) => state.isComplete)
  // As App.tsx does: the interview and interest graph follow the account, and the interview waits for them.
  useAccountProfileSync()
  const profileReady = useAccountProfileReady()
  useEffect(() => {
    const update = () => setHash(window.location.hash)
    window.addEventListener("hashchange", update)
    return () => window.removeEventListener("hashchange", update)
  }, [])
  // As App.tsx does: a sign-in while the sign-in page is up returns to the destination.
  useEffect(() => {
    if (!isAuthenticated) return
    markLaunched()
    closeSignIn()
  }, [isAuthenticated])
  if (hash === SIGN_IN_HASH && !isAuthenticated) {
    return (
      <AuthPageLayout>
        <SignInPage onContinueAsGuest={() => { markLaunched(); closeSignIn() }} />
      </AuthPageLayout>
    )
  }
  if (!profileReady) return null
  if ((isAuthenticated && !surveyComplete) || hash === "#onboarding") return <OnboardingPage />
  // ren desktop's BrowserLayout gives its pages the agent UI's context (Build's composer uses it).
  return (
    <AgentUiProvider>
      <div className="flex h-full flex-col bg-surface-0 dark:bg-surface-900">
        <Destinations />
        <div className="min-h-0 flex-1">
          <Content />
        </div>
      </div>
    </AgentUiProvider>
  )
}

document.title = tab.label

const root = document.getElementById("root")
if (root === null) throw new Error("The page has no root element.")

createRoot(root).render(
  <StrictMode>
    <Page />
  </StrictMode>,
)
