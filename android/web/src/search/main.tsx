/*
 * ren://search on the phone: ren desktop's search results page, mounted as it is under what BrowserLayout lays over
 * ren's pages (`Shell`). The tab's address carries the query and the level, as it does on the desktop; Search deeper
 * loads the deeper search in this tab, and a result opens in a new one.
 */
import "../host"
import { Shell, followTabs } from "../shell"
import { applyTheme } from "../theme"
import "../page.css"
import "@/index.css"
import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { getSearchLevelFromRonUrl, getSearchQueryFromRonUrl } from "@/components/chrome/UrlBar"
import { DEFAULT_SEARCH_LEVEL } from "@/lib/search-api"
import { SearchResultsPage } from "@/pages/SearchResultsPage"
import { useTabStore } from "@/stores/tabStore"

/** This page's address the way ren desktop writes a search. */
const address = `ron://search${window.location.search}`
const query = getSearchQueryFromRonUrl(address) ?? ""
const level = getSearchLevelFromRonUrl(address) ?? DEFAULT_SEARCH_LEVEL

applyTheme()

// ren's tab store holds this search, so a deeper one replaces it here (ren's navigateTabByIdOrActive).
useTabStore.getState().setTabs([], null)
useTabStore.getState().createTab(address, true)
followTabs(() => false)

document.title = query

const root = document.getElementById("root")
if (root === null) throw new Error("The search page has no root element.")

createRoot(root).render(
  <StrictMode>
    <Shell>
      <div className="h-full overflow-y-auto">
        <SearchResultsPage query={query} level={level} onBack={() => window.location.assign("ren://home")} />
      </div>
    </Shell>
  </StrictMode>,
)
