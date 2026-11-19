package com.hiron.ren.browser

import android.net.Uri

/** How hard a search works, as ren's search offers it (its `SEARCH_LEVELS`); [wire] is the level's name. */
public enum class SearchLevel(public val wire: String, public val label: String) {
    Fast("fast", "Fast"),
    Balanced("low", "Balanced"),
    Deep("medium", "Deep"),
    Research("high", "Research"),
    Max("xhigh", "Max"),
    ;

    public companion object {
        public val Default: SearchLevel = Balanced

        public fun of(wire: String?): SearchLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * ren desktop's destinations after Home, as its sidebar lists them (`DESTINATIONS` in `navigation/destinations.ts`);
 * each is Ren's page at [address].
 */
public enum class Destination {
    Discover,
    Research,
    Execute,
    Documents,
    Calendar,
    Vibe,
    Build,
    ;

    public val id: String = name.lowercase()
    public val address: String = "ren://$id"

    public companion object {
        public fun of(url: String): Destination? = entries.firstOrNull { it.address == url }
    }
}

/**
 * What the address bar does with what was typed, the way ren desktop's does (its `UrlBar.tsx` and
 * `navigable-url.ts`): an address loads, anything else is a search, and Ren's own pages are `ren://` addresses.
 */
public object Addresses {
    public const val HOME: String = "ren://home"
    private const val SEARCH = "ren://search"

    private val HAS_SCHEME = Regex("^[a-z][a-z\\d+.-]*://", RegexOption.IGNORE_CASE)
    private val SCHEME_WITHOUT_SLASHES = Regex("^(about|data|mailto):", RegexOption.IGNORE_CASE)
    private val LOCAL_HOST = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3}|\\[[\\da-f:.]+])(:\\d+)?([/?#]|$)", RegexOption.IGNORE_CASE)
    private val NAMED_SCHEME = Regex("^(https?://|ren://|file://|about:)", RegexOption.IGNORE_CASE)
    private val LOCALHOST_ADDRESS = Regex("^localhost(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
    private val IP_ADDRESS = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")
    private val WHITESPACE = Regex("\\s")

    /**
     * The URL to load for an address typed without a scheme: `http://` for localhost and IP literals, `https://` for
     * everything else. An address that already names a scheme comes back unchanged.
     */
    public fun toNavigableUrl(input: String): String {
        val address = input.trim()
        if (address.isEmpty() || HAS_SCHEME.containsMatchIn(address) || SCHEME_WITHOUT_SLASHES.containsMatchIn(address)) return address
        // A scheme-relative address (`//example.com/a`) only lacks the scheme itself.
        if (address.startsWith("//")) return "https:$address"
        return "${if (LOCAL_HOST.containsMatchIn(address)) "http" else "https"}://$address"
    }

    /** Whether what was typed is an address rather than a search phrase. */
    public fun looksLikeUrl(value: String): Boolean = when {
        NAMED_SCHEME.containsMatchIn(value) -> true
        LOCALHOST_ADDRESS.matches(value) -> true
        IP_ADDRESS.matches(value) -> true
        else -> value.contains('.') && !WHITESPACE.containsMatchIn(value)
    }

    public fun searchUrl(query: String, level: SearchLevel): String = "$SEARCH?q=${Uri.encode(query)}&level=${level.wire}"

    /** The query of a `ren://search` address; null for any other address. */
    public fun searchQuery(url: String): String? = if (url.startsWith(SEARCH)) Uri.parse(url).getQueryParameter("q").orEmpty() else null

    public fun searchLevel(url: String): SearchLevel? = if (url.startsWith(SEARCH)) SearchLevel.of(Uri.parse(url).getQueryParameter("level")) else null

    /** Where typed text goes: an address as typed (made loadable), or the search page for a phrase. */
    public fun resolve(typed: String, level: SearchLevel): String {
        val text = typed.trim()
        return when {
            text.isEmpty() -> ""
            searchQuery(text) != null -> text
            looksLikeUrl(text) -> toNavigableUrl(text)
            else -> searchUrl(text, level)
        }
    }

    /**
     * What the address bar shows at rest: the page's identity rather than its plumbing. A search reads as its query
     * and an https page as its host and path; focus shows the exact address.
     */
    public fun resting(url: String): String {
        val query = searchQuery(url)
        if (!query.isNullOrEmpty()) return query
        if (url == HOME) return ""
        Destination.of(url)?.let { return it.name }
        if (!url.startsWith("https://")) return url
        val parsed = Uri.parse(url)
        val host = parsed.host?.removePrefix("www.") ?: return url
        val path = parsed.encodedPath.orEmpty().takeIf { it != "/" }.orEmpty()
        val search = parsed.encodedQuery?.let { "?$it" }.orEmpty()
        return "$host$path$search"
    }

    /** Whether the page came over TLS or is one of Ren's own. */
    public fun secure(url: String): Boolean = url.startsWith("https://") || url.startsWith("ren://")
}
