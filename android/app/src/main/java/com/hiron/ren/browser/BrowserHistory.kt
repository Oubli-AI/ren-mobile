package com.hiron.ren.browser

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One visited web page in the browser's persistent history, ordered most-recent first. */
internal data class HistoryEntry(
    val id: String,
    val url: String,
    val title: String,
    val host: String,
    val visitedAt: Long,
)

/** A recently closed non-incognito tab that can be reopened in LIFO order. */
internal data class ClosedTab(
    val url: String,
    val title: String,
)

/**
 * Persistent web browsing history and in-memory closed-tab stack for normal browsing tabs.
 * Internal `ren://` pages, `about:`/`data:`/`javascript:` URLs, and bundled `appassets.androidplatform.net` assets are
 * never recorded.
 */
internal class BrowserHistory(read: () -> String?, private val write: (String) -> Unit) {
    private val _entries = MutableStateFlow(read()?.let(::parse).orEmpty())
    internal val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private val _closedTabs = MutableStateFlow<List<ClosedTab>>(emptyList())
    internal val closedTabs: StateFlow<List<ClosedTab>> = _closedTabs.asStateFlow()

    internal fun record(url: String, title: String, now: Long = System.currentTimeMillis()) {
        val cleanUrl = url.trim()
        if (!isRecordableUrl(cleanUrl)) return
        val host = Uri.parse(cleanUrl).host?.lowercase()?.trimEnd('.').orEmpty()
        val cleanTitle = title.trim()
        val current = _entries.value
        val existing = current.firstOrNull { it.url == cleanUrl }
        val head = if (existing != null) {
            existing.copy(
                title = cleanTitle.ifEmpty { existing.title },
                host = host,
                visitedAt = now,
            )
        } else {
            HistoryEntry(
                id = "hist-${now.toString(36)}-${UUID.randomUUID().toString().take(6)}",
                url = cleanUrl,
                title = cleanTitle,
                host = host,
                visitedAt = now,
            )
        }
        val next = buildList {
            add(head)
            for (item in current) {
                if (item.url != cleanUrl) {
                    add(item)
                    if (size >= MAX_ENTRIES) break
                }
            }
        }
        save(next)
    }

    internal fun search(query: String, limit: Int = 30): List<HistoryEntry> {
        if (limit <= 0) return emptyList()
        val needle = query.trim()
        if (needle.isEmpty()) return _entries.value.take(limit)
        return _entries.value
            .asSequence()
            .filter { it.title.contains(needle, ignoreCase = true) || it.url.contains(needle, ignoreCase = true) }
            .take(limit)
            .toList()
    }

    internal fun remove(id: String) {
        save(_entries.value.filterNot { it.id == id })
    }

    internal fun clearSince(sinceEpochMs: Long) {
        save(_entries.value.filter { it.visitedAt < sinceEpochMs })
    }

    internal fun clearAll() {
        save(emptyList())
    }

    internal fun pushClosedTab(url: String, title: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || cleanUrl == Addresses.HOME || cleanUrl.equals("about:blank", ignoreCase = true)) return
        _closedTabs.value = (listOf(ClosedTab(url = cleanUrl, title = title.trim())) + _closedTabs.value).take(MAX_CLOSED_TABS)
    }

    internal fun popClosedTab(): ClosedTab? {
        val current = _closedTabs.value
        val first = current.firstOrNull() ?: return null
        _closedTabs.value = current.drop(1)
        return first
    }

    internal fun clearClosedTabs() {
        _closedTabs.value = emptyList()
    }

    private fun save(next: List<HistoryEntry>) {
        _entries.value = next
        write(json(next))
    }

    internal companion object {
        internal const val MAX_ENTRIES: Int = 2000
        internal const val MAX_CLOSED_TABS: Int = 20
        private const val INTERNAL_ASSET_HOST = "appassets.androidplatform.net"

        internal fun isRecordableUrl(raw: String): Boolean {
            val url = raw.trim()
            if (url.isEmpty() || url == Addresses.HOME) return false
            val parsed = Uri.parse(url)
            val scheme = parsed.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            val host = parsed.host?.lowercase()?.trimEnd('.') ?: return false
            return host.isNotEmpty() && host != INTERNAL_ASSET_HOST
        }

        internal fun json(list: List<HistoryEntry>): String = JSONArray(
            list.map { entry ->
                JSONObject()
                    .put("id", entry.id)
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("host", entry.host)
                    .put("visitedAt", entry.visitedAt)
            },
        ).toString()

        internal fun parse(raw: String): List<HistoryEntry>? {
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
            return (0 until array.length())
                .mapNotNull { array.optJSONObject(it) }
                .mapNotNull { item ->
                    val id = item.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    val url = item.optString("url").takeIf(::isRecordableUrl) ?: return@mapNotNull null
                    val host = item.optString("host").ifEmpty { Uri.parse(url).host?.lowercase()?.trimEnd('.').orEmpty() }
                    val title = item.optString("title")
                    val visitedAt = item.optLong("visitedAt", 0L)
                    HistoryEntry(id = id, url = url, title = title, host = host, visitedAt = visitedAt)
                }
                .take(MAX_ENTRIES)
        }
    }
}
