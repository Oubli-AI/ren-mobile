package com.hiron.ren.browser

import android.net.Uri
import mozilla.components.lib.publicsuffixlist.PublicSuffixList

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

/** Which search engine answers phrases typed in the address bar. */
internal enum class SearchEngine(val wire: String, val label: String) {
    Ren("ren", "Ren"),
    Brave("brave", "Brave"),
    DuckDuckGo("ddg", "DuckDuckGo"),
    Google("google", "Google"),
    Startpage("startpage", "Startpage"),
    ;

    fun url(query: String, level: SearchLevel): String = when (this) {
        Ren -> Addresses.searchUrl(query, level)
        Brave -> "https://search.brave.com/search?q=${Uri.encode(query)}"
        DuckDuckGo -> "https://duckduckgo.com/?q=${Uri.encode(query)}"
        Google -> "https://www.google.com/search?q=${Uri.encode(query)}"
        Startpage -> "https://www.startpage.com/sp/search?query=${Uri.encode(query)}"
    }

    companion object {
        val Default: SearchEngine = Ren

        fun of(wire: String?): SearchEngine? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * ren desktop's destinations after Home, as its sidebar lists them (`DESTINATIONS` in `navigation/destinations.ts`),
 * then [Agent], ren desktop's agent panel (`AgentPanel.tsx`: its threads, Memory, agent picker, voice, Teach Ren and
 * Live Music) on a page of its own; each is Ren's page at [address].
 */
public enum class Destination {
    Discover,
    Research,
    Execute,
    Documents,
    Calendar,
    Vibe,
    Build,
    Agent,
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
    private const val IPV4_OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"

    private val HAS_SCHEME = Regex("^[a-z][a-z\\d+.-]*://", RegexOption.IGNORE_CASE)
    private val SCHEME_WITHOUT_SLASHES = Regex("^(about|data|mailto):", RegexOption.IGNORE_CASE)
    private val LOCAL_HOST = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3}|\\[[\\da-f:.]+])(:\\d+)?([/?#]|$)", RegexOption.IGNORE_CASE)
    private val NAMED_SCHEME = Regex("^(https?://|ren://|file://|about:)", RegexOption.IGNORE_CASE)
    private val LOCALHOST_ADDRESS = Regex("^localhost(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)
    private val IP_ADDRESS = Regex("^$IPV4_OCTET(?:\\.$IPV4_OCTET){3}(:\\d+)?([/?#].*)?$")
    private val IPV6_ADDRESS = Regex("^\\[[\\da-f:.]+](:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)
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
    public fun looksLikeUrl(value: String): Boolean {
        if (value.isEmpty() || WHITESPACE.containsMatchIn(value)) return false
        if (NAMED_SCHEME.containsMatchIn(value)) return true
        if (LOCALHOST_ADDRESS.matches(value)) return true
        if (IP_ADDRESS.matches(value)) return true
        if (IPV6_ADDRESS.matches(value)) return true
        val (host, port) = parseSchemeLessHost(value) ?: return false
        return (host.contains('.') || port != null) && isValidHostLabels(host)
    }

    public fun searchUrl(query: String, level: SearchLevel): String = "$SEARCH?q=${Uri.encode(query)}&level=${level.wire}"

    /** The query of a `ren://search` address; null for any other address. */
    public fun searchQuery(url: String): String? = if (url.startsWith(SEARCH)) Uri.parse(url).getQueryParameter("q").orEmpty() else null

    public fun searchLevel(url: String): SearchLevel? = if (url.startsWith(SEARCH)) SearchLevel.of(Uri.parse(url).getQueryParameter("level")) else null

    /** Where typed text goes: an address as typed (made loadable), or the search page for a phrase. */
    public fun resolve(typed: String, level: SearchLevel): String = resolve(typed, level, SearchEngine.Ren)

    /** Where typed text goes for the selected [engine]: an address as typed, or a search URL for a phrase. */
    internal fun resolve(typed: String, level: SearchLevel, engine: SearchEngine): String {
        val text = typed.trim()
        return when {
            text.isEmpty() -> ""
            searchQuery(text) != null -> text
            Destination.of(text.substringBefore('#')) != null -> text
            looksLikeUrl(text) -> toNavigableUrl(text)
            else -> engine.url(text, level)
        }
    }

    /**
     * Resolves [typed] with Mozilla's [publicSuffixList] so dotted tokens whose suffix isn't a real public TLD
     * (`node.js`, `Dr.Smith`, `foo.invalidtld`) search instead of failing to resolve a host.
     */
    internal suspend fun resolve(
        typed: String,
        level: SearchLevel,
        engine: SearchEngine,
        publicSuffixList: PublicSuffixList,
    ): String {
        val text = typed.trim()
        if (text.isEmpty()) return ""
        if (searchQuery(text) != null) return text
        if (Destination.of(text.substringBefore('#')) != null) return text
        if (WHITESPACE.containsMatchIn(text)) return engine.url(text, level)
        if (
            HAS_SCHEME.containsMatchIn(text) ||
            SCHEME_WITHOUT_SLASHES.containsMatchIn(text) ||
            (text.startsWith("//") && looksLikeUrl(text)) ||
            LOCALHOST_ADDRESS.matches(text) ||
            IP_ADDRESS.matches(text) ||
            IPV6_ADDRESS.matches(text) ||
            hasExplicitPort(text)
        ) {
            return toNavigableUrl(text)
        }
        if (!looksLikeUrl(text)) return engine.url(text, level)
        val candidate = toNavigableUrl(text)
        val host = Uri.parse(candidate).host?.lowercase()?.trimEnd('.') ?: return engine.url(text, level)
        val suffix = publicSuffixList.getPublicSuffix(host).await()
        val validTld = !suffix.isNullOrEmpty() && !suffix.equals(host, ignoreCase = true) && publicSuffixList.isPublicSuffix(suffix).await()
        return if (validTld) candidate else engine.url(text, level)
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

    private fun hasExplicitPort(value: String): Boolean {
        val (host, port) = parseSchemeLessHost(value) ?: return false
        return port != null && isValidHostLabels(host)
    }

    private fun parseSchemeLessHost(raw: String): Pair<String, Int?>? {
        val rest = if (raw.startsWith("//")) raw.substring(2) else raw
        val end = rest.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (end >= 0) rest.substring(0, end) else rest
        if (authority.isEmpty()) return null
        val colon = authority.indexOf(':')
        if (colon < 0) return authority to null
        if (authority.indexOf(':', colon + 1) >= 0) return null
        val host = authority.substring(0, colon)
        val portText = authority.substring(colon + 1)
        if (host.isEmpty() || portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) return null
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
    }

    private fun isValidHostLabels(host: String): Boolean {
        if (host.isEmpty() || host.startsWith('.') || host.endsWith('.') || host.contains("..")) return false
        val labels = host.split('.')
        if (!labels.all(::isValidLabel)) return false
        return !labels.last().all { it in '0'..'9' }
    }

    private fun isValidLabel(label: String): Boolean {
        if (label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-')) return false
        return label.all { ch ->
            ch in 'a'..'z' ||
                ch in 'A'..'Z' ||
                ch in '0'..'9' ||
                ch == '-' ||
                (ch.code > 127 && (Character.isLetterOrDigit(ch) || isCombiningMark(ch)))
        }
    }

    private fun isCombiningMark(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        -> true
        else -> false
    }
}
