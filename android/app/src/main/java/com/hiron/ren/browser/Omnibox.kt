package com.hiron.ren.browser

/** The source of an omnibox suggestion row. */
internal enum class SuggestionKind {
    Clipboard,
    OpenTab,
    Favorite,
    History,
}

/** One suggestion offered below the focused address bar. */
internal data class OmniboxSuggestion(
    val kind: SuggestionKind,
    val title: String,
    val url: String,
    val tabId: String? = null,
)

/**
 * Ranks local suggestions for the focused address bar without sending keystrokes over the network:
 * clipboard and recent history when blank; open tabs, favorites, and history as the user types.
 */
internal object Omnibox {
    private const val MAX_CLIPBOARD_LENGTH = 2048

    internal fun suggest(
        query: String,
        tabs: List<BrowserTab>,
        favorites: List<Favorite>,
        history: List<HistoryEntry>,
        clipboardText: String? = null,
        currentTabId: String? = null,
        limit: Int = 8,
    ): List<OmniboxSuggestion> {
        if (limit <= 0) return emptyList()
        val results = ArrayList<OmniboxSuggestion>(limit)
        val seenUrls = HashSet<String>()
        val trimmedQuery = query.trim()

        if (trimmedQuery.isEmpty()) {
            val currentUrl = currentTabId?.let { id -> tabs.firstOrNull { it.id == id }?.url }
            val clip = clipboardText?.trim().orEmpty()
            if (clip.isNotEmpty() && clip.length <= MAX_CLIPBOARD_LENGTH) {
                val isUrl = Addresses.looksLikeUrl(clip)
                val target = if (isUrl) Addresses.toNavigableUrl(clip) else clip
                val sameAsCurrent = currentUrl != null && (
                    clip == currentUrl ||
                        target == currentUrl ||
                        (isUrl && canonicalUrlKey(target) == canonicalUrlKey(currentUrl))
                    )
                if (!sameAsCurrent) {
                    val label = if (isUrl) "Paste & go" else "Paste & search"
                    results.add(OmniboxSuggestion(kind = SuggestionKind.Clipboard, title = label, url = target))
                    if (isUrl) seenUrls.add(canonicalUrlKey(target))
                }
            }
            for (entry in history) {
                if (results.size >= limit) break
                if (seenUrls.add(canonicalUrlKey(entry.url))) {
                    val title = entry.title.ifBlank { Addresses.resting(entry.url).ifEmpty { entry.url } }
                    results.add(OmniboxSuggestion(kind = SuggestionKind.History, title = title, url = entry.url))
                }
            }
            return results
        }

        for (tab in tabs) {
            if (results.size >= limit) break
            if (tab.home || tab.id == currentTabId || tab.url.isBlank() || tab.url.equals("about:blank", ignoreCase = true)) continue
            val matches = tab.title.contains(trimmedQuery, ignoreCase = true) ||
                tab.url.contains(trimmedQuery, ignoreCase = true) ||
                tab.label.contains(trimmedQuery, ignoreCase = true)
            if (matches && seenUrls.add(canonicalUrlKey(tab.url))) {
                results.add(
                    OmniboxSuggestion(
                        kind = SuggestionKind.OpenTab,
                        title = tab.label,
                        url = tab.url,
                        tabId = tab.id,
                    ),
                )
            }
        }

        for (favorite in favorites) {
            if (results.size >= limit) break
            val matches = favorite.title.contains(trimmedQuery, ignoreCase = true) ||
                favorite.url.contains(trimmedQuery, ignoreCase = true)
            if (matches && seenUrls.add(canonicalUrlKey(favorite.url))) {
                results.add(
                    OmniboxSuggestion(
                        kind = SuggestionKind.Favorite,
                        title = favorite.title.ifBlank { favorite.url },
                        url = favorite.url,
                    ),
                )
            }
        }

        for (entry in history) {
            if (results.size >= limit) break
            val matches = entry.title.contains(trimmedQuery, ignoreCase = true) ||
                entry.url.contains(trimmedQuery, ignoreCase = true)
            if (matches && seenUrls.add(canonicalUrlKey(entry.url))) {
                val title = entry.title.ifBlank { Addresses.resting(entry.url).ifEmpty { entry.url } }
                results.add(
                    OmniboxSuggestion(
                        kind = SuggestionKind.History,
                        title = title,
                        url = entry.url,
                    ),
                )
            }
        }

        return results
    }

    /** Treats bare origins with and without a trailing slash (`https://github.com` vs `https://github.com/`) as one URL. */
    private fun canonicalUrlKey(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.endsWith('/') && trimmed.count { it == '/' } == 3) trimmed.dropLast(1) else trimmed
    }
}
