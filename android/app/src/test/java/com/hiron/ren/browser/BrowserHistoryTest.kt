package com.hiron.ren.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit tests for persistent [BrowserHistory], the closed-tab stack, and [Omnibox] local suggestions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserHistoryTest {
    private var saved: String? = null
    private fun createHistory() = BrowserHistory({ saved }, { saved = it })

    @Test
    fun recordsHttpAndHttpsAndPersistsAcrossInstances() {
        val history = createHistory()
        history.record("https://example.com/guide", "Example Guide", now = 1_000L)
        history.record("http://localhost:3000/app", "Local Dev", now = 2_000L)

        assertEquals(2, history.entries.value.size)
        assertEquals("http://localhost:3000/app", history.entries.value[0].url)
        assertEquals("localhost", history.entries.value[0].host)
        assertEquals("https://example.com/guide", history.entries.value[1].url)
        assertEquals("example.com", history.entries.value[1].host)

        val reloaded = createHistory()
        assertEquals(history.entries.value, reloaded.entries.value)
    }

    @Test
    fun deduplicatesRepeatedVisitsAndPreservesExistingTitleWhenBlank() {
        val history = createHistory()
        history.record("https://example.com/a", "Original Title", now = 1_000L)
        val originalId = history.entries.value.single().id
        history.record("https://oubliai.com", "Oubli", now = 2_000L)
        history.record("https://example.com/a", "   ", now = 3_000L)

        assertEquals(2, history.entries.value.size)
        val top = history.entries.value.first()
        assertEquals(originalId, top.id)
        assertEquals("https://example.com/a", top.url)
        assertEquals("Original Title", top.title)
        assertEquals(3_000L, top.visitedAt)

        history.record("https://example.com/a", "Updated Title", now = 4_000L)
        assertEquals("Updated Title", history.entries.value.first().title)
        assertEquals(4_000L, history.entries.value.first().visitedAt)
    }

    @Test
    fun ignoresInternalSchemesBlankUrlsAndBundledAppAssets() {
        val history = createHistory()
        listOf(
            "",
            "   ",
            Addresses.HOME,
            "ren://discover",
            "ren://search?q=test&level=low",
            "about:blank",
            "data:text/html,<p>hi</p>",
            "javascript:alert(1)",
            "https://appassets.androidplatform.net/assets/ren/web/search/index.html",
            "http://appassets.androidplatform.net/assets/ren/web/destinations/index.html",
        ).forEach { ignored ->
            history.record(ignored, "Ignored", now = 5_000L)
        }
        assertTrue(history.entries.value.isEmpty())
        assertNull(saved)
    }

    @Test
    fun searchesCaseInsensitivelyRemovesAndClearsSinceTimestamp() {
        val history = createHistory()
        history.record("https://kotlinlang.org/docs", "Kotlin Docs", now = 1_000L)
        history.record("https://github.com/anthropics/claude-code", "Claude Code", now = 2_000L)
        history.record("https://developer.android.com/jetpack", "Android Jetpack", now = 3_000L)

        assertEquals(2, history.search("", limit = 2).size)
        assertEquals(listOf("https://github.com/anthropics/claude-code"), history.search("ANTHR").map { it.url })
        assertEquals(listOf("https://kotlinlang.org/docs"), history.search("kotlin docs").map { it.url })

        history.clearSince(2_000L)
        assertEquals(listOf("https://kotlinlang.org/docs"), history.entries.value.map { it.url })

        val remainingId = history.entries.value.single().id
        history.remove(remainingId)
        assertTrue(history.entries.value.isEmpty())

        history.record("https://example.org", "Example Org", now = 9_000L)
        history.clearAll()
        assertTrue(history.entries.value.isEmpty())
        assertTrue(createHistory().entries.value.isEmpty())
    }

    @Test
    fun closedTabStackCapsAtTwentyAndIgnoresHomeAndAboutBlank() {
        val history = createHistory()
        history.pushClosedTab("", "Empty")
        history.pushClosedTab(Addresses.HOME, "Home")
        history.pushClosedTab("about:blank", "Blank")
        assertTrue(history.closedTabs.value.isEmpty())
        assertNull(history.popClosedTab())

        for (index in 1..25) {
            history.pushClosedTab("https://example.com/$index", "Tab $index")
        }
        assertEquals(BrowserHistory.MAX_CLOSED_TABS, history.closedTabs.value.size)
        assertEquals(ClosedTab("https://example.com/25", "Tab 25"), history.popClosedTab())
        assertEquals(19, history.closedTabs.value.size)

        history.clearClosedTabs()
        assertTrue(history.closedTabs.value.isEmpty())
    }

    @Test
    fun omniboxSuggestsClipboardAndRecentHistoryWhenBlank() {
        val history = listOf(
            HistoryEntry("h1", "https://example.com/docs", "Example Docs", "example.com", 2_000L),
            HistoryEntry("h2", "https://news.ycombinator.com", "Hacker News", "news.ycombinator.com", 1_000L),
        )
        val tabs = listOf(BrowserTab(id = "t1", url = "https://current.example.com", title = "Current"))

        val withUrlClipboard = Omnibox.suggest(
            query = "",
            tabs = tabs,
            favorites = emptyList(),
            history = history,
            clipboardText = "example.com/docs",
            currentTabId = "t1",
        )
        assertEquals(SuggestionKind.Clipboard, withUrlClipboard[0].kind)
        assertEquals("Paste & go", withUrlClipboard[0].title)
        assertEquals("https://example.com/docs", withUrlClipboard[0].url)
        assertEquals(listOf("https://news.ycombinator.com"), withUrlClipboard.drop(1).map { it.url })

        val withPhraseClipboard = Omnibox.suggest(
            query = "   ",
            tabs = tabs,
            favorites = emptyList(),
            history = history,
            clipboardText = "best espresso grinder",
            currentTabId = "t1",
        )
        assertEquals(SuggestionKind.Clipboard, withPhraseClipboard[0].kind)
        assertEquals("Paste & search", withPhraseClipboard[0].title)
        assertEquals("best espresso grinder", withPhraseClipboard[0].url)

        val sameAsCurrentTab = Omnibox.suggest(
            query = "",
            tabs = tabs,
            favorites = emptyList(),
            history = history,
            clipboardText = "https://current.example.com",
            currentTabId = "t1",
        )
        assertTrue(sameAsCurrentTab.none { it.kind == SuggestionKind.Clipboard })
    }

    @Test
    fun omniboxRanksOpenTabsFavoritesAndHistoryAndDeduplicatesUrls() {
        val tabs = listOf(
            BrowserTab(id = "home", url = Addresses.HOME, title = ""),
            BrowserTab(id = "current", url = "https://github.com/current", title = "GitHub Current"),
            BrowserTab(id = "open-gh", url = "https://github.com/", title = "GitHub Home"),
            BrowserTab(id = "open-pr", url = "https://github.com/pulls", title = "Pull Requests"),
        )
        val favorites = listOf(
            Favorite(id = "fav-gh", title = "GitHub", url = "https://github.com"),
            Favorite(id = "fav-issues", title = "GitHub Issues", url = "https://github.com/issues"),
        )
        val history = listOf(
            HistoryEntry("h1", "https://github.com/issues", "GitHub Issues", "github.com", 3_000L),
            HistoryEntry("h2", "https://github.com/explore", "GitHub Explore", "github.com", 2_000L),
        )

        val suggestions = Omnibox.suggest(
            query = "github",
            tabs = tabs,
            favorites = favorites,
            history = history,
            currentTabId = "current",
            limit = 8,
        )
        assertEquals(
            listOf(
                OmniboxSuggestion(SuggestionKind.OpenTab, "GitHub Home", "https://github.com/", tabId = "open-gh"),
                OmniboxSuggestion(SuggestionKind.OpenTab, "Pull Requests", "https://github.com/pulls", tabId = "open-pr"),
                OmniboxSuggestion(SuggestionKind.Favorite, "GitHub Issues", "https://github.com/issues"),
                OmniboxSuggestion(SuggestionKind.History, "GitHub Explore", "https://github.com/explore"),
            ),
            suggestions,
        )
    }
}
