package com.hiron.ren.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * In-memory third-party ad, tracker, analytics, telemetry, and fingerprinting blocker plus URL tracking-parameter
 * sanitizer. Keeps zero per-host metrics, URLs, or logs on disk so incognito tabs and service workers leave no trace.
 */
internal object ContentBlocker {
    private const val APP_ASSETS_HOST = "appassets.androidplatform.net"

    /** Curated root and service domains for third-party ads, trackers, analytics, telemetry, and fingerprinting. */
    internal val blockedDomains: Set<String> = setOf(
        "2mdn.net",
        "33across.com",
        "a-ads.com",
        "adcolony.com",
        "addthis.com",
        "adform.net",
        "adhigh.net",
        "adition.com",
        "adjust.com",
        "adkernel.com",
        "admixer.net",
        "adnxs.com",
        "adocean.pl",
        "adriver.ru",
        "adroll.com",
        "ads-twitter.com",
        "adsafeprotected.com",
        "adscale.de",
        "adservice.google.com",
        "adspirit.de",
        "adsrvr.org",
        "adtelligent.com",
        "adzerk.net",
        "agkn.com",
        "amazon-adsystem.com",
        "amplitude.com",
        "analytics.tiktok.com",
        "applovin.com",
        "appsflyer.com",
        "bat.bing.com",
        "bidr.io",
        "bidswitch.net",
        "bluekai.com",
        "branch.io",
        "carbonads.com",
        "casalemedia.com",
        "chartbeat.com",
        "clarity.ms",
        "clickagy.com",
        "comscore.com",
        "connatix.com",
        "connect.facebook.net",
        "crazyegg.com",
        "creativecdn.com",
        "criteo.com",
        "criteo.net",
        "crwdcntrl.net",
        "demdex.net",
        "dotomi.com",
        "doubleclick.net",
        "doubleverify.com",
        "everesttech.net",
        "exelator.com",
        "exoclick.com",
        "flashtalking.com",
        "fpjs.io",
        "fullstory.com",
        "fwmrm.net",
        "google-analytics.com",
        "googleadservices.com",
        "googletagmanager.com",
        "googletagservices.com",
        "googlesyndication.com",
        "gumgum.com",
        "heapanalytics.com",
        "hilltopads.net",
        "histats.com",
        "hotjar.com",
        "imrworldwide.com",
        "indexexchange.com",
        "inmobi.com",
        "innovid.com",
        "inspectlet.com",
        "juicyads.com",
        "kargo.com",
        "kissmetrics.com",
        "kochava.com",
        "krxd.net",
        "lijit.com",
        "logrocket.io",
        "lotame.com",
        "luckyorange.com",
        "mathtag.com",
        "mc.yandex.ru",
        "media.net",
        "mediamath.com",
        "mgid.com",
        "mixpanel.com",
        "moatads.com",
        "mouseflow.com",
        "nativo.com",
        "nr-data.net",
        "omtrdc.net",
        "onclickads.net",
        "openx.net",
        "outbrain.com",
        "parsely.com",
        "pippio.com",
        "popads.net",
        "popcash.net",
        "primis.tech",
        "propellerads.com",
        "pubmatic.com",
        "quantserve.com",
        "revcontent.com",
        "rfihub.com",
        "rlcdn.com",
        "rtbhouse.com",
        "rubiconproject.com",
        "scorecardresearch.com",
        "segment.io",
        "serving-sys.com",
        "sharethis.com",
        "sharethrough.com",
        "singular.net",
        "sizmek.com",
        "smartadserver.com",
        "sonobi.com",
        "sovrn.com",
        "spotx.tv",
        "spotxchange.com",
        "statcounter.com",
        "taboola.com",
        "tapad.com",
        "teads.tv",
        "tr.snapchat.com",
        "trafficjunky.net",
        "triplelift.com",
        "turn.com",
        "unityads.unity3d.com",
        "vungle.com",
        "yieldmo.com",
        "zemanta.com",
    )

    private val TRACKING_PARAMS: Set<String> = setOf(
        "_hsenc",
        "_hsmi",
        "dclid",
        "fbclid",
        "gbraid",
        "gclid",
        "igshid",
        "mc_eid",
        "mkt_tok",
        "msclkid",
        "twclid",
        "utm_campaign",
        "utm_content",
        "utm_id",
        "utm_medium",
        "utm_source",
        "utm_term",
        "wbraid",
        "yclid",
    )

    /** Common two-label country-code second-level suffixes used for fast registrable-domain comparison. */
    private val TWO_PART_SLDS: Set<String> = setOf(
        "ac.in",
        "ac.jp",
        "ac.kr",
        "ac.nz",
        "ac.uk",
        "ac.za",
        "co.id",
        "co.il",
        "co.in",
        "co.jp",
        "co.ke",
        "co.kr",
        "co.nz",
        "co.th",
        "co.uk",
        "co.za",
        "com.ar",
        "com.au",
        "com.br",
        "com.hk",
        "com.mx",
        "com.my",
        "com.ng",
        "com.ph",
        "com.sg",
        "com.tr",
        "com.tw",
        "com.vn",
        "ed.jp",
        "edu.au",
        "edu.br",
        "edu.hk",
        "edu.sg",
        "firm.in",
        "gen.in",
        "go.jp",
        "go.kr",
        "gob.mx",
        "gov.au",
        "gov.br",
        "gov.in",
        "gov.sg",
        "gov.uk",
        "gov.za",
        "govt.nz",
        "gr.jp",
        "in.th",
        "ind.in",
        "me.uk",
        "ne.jp",
        "ne.kr",
        "net.au",
        "net.br",
        "net.in",
        "net.nz",
        "net.uk",
        "net.za",
        "or.jp",
        "or.kr",
        "org.au",
        "org.br",
        "org.il",
        "org.in",
        "org.nz",
        "org.tw",
        "org.uk",
        "org.za",
    )

    /** Whether [host] or any of its parent domain suffixes matches [blockedDomains]. */
    internal fun isBlockedHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty() || isExemptHost(normalized)) return false
        var cursor = normalized
        while (true) {
            if (cursor in blockedDomains) return true
            val dot = cursor.indexOf('.')
            if (dot <= 0 || dot >= cursor.length - 1) return false
            cursor = cursor.substring(dot + 1)
        }
    }

    /** Whether [host] or any of its parent domain suffixes appears in [disabledHosts]. */
    internal fun isHostAllowlisted(host: String, disabledHosts: Set<String>): Boolean {
        if (disabledHosts.isEmpty()) return false
        val normalized = normalizeHost(host)
        if (normalized.isEmpty()) return false
        val allowlisted = HashSet<String>(disabledHosts.size)
        for (entry in disabledHosts) {
            val clean = normalizeHost(entry)
            if (clean.isNotEmpty()) allowlisted.add(clean)
        }
        if (allowlisted.isEmpty()) return false
        var cursor = normalized
        while (true) {
            if (cursor in allowlisted) return true
            val dot = cursor.indexOf('.')
            if (dot <= 0 || dot >= cursor.length - 1) return false
            cursor = cursor.substring(dot + 1)
        }
    }

    /**
     * Whether a subresource or navigation to [requestUrl] on [pageUrl] should be blocked when [shieldsEnabled] is on
     * and the page host is not in [disabledHosts]. First-party requests (`sameSite`) are never blocked.
     */
    internal fun shouldBlockRequest(
        requestUrl: Uri,
        pageUrl: String?,
        shieldsEnabled: Boolean,
        disabledHosts: Set<String> = emptySet(),
    ): Boolean {
        if (!shieldsEnabled) return false
        val reqHost = requestUrl.host?.let(::normalizeHost)?.takeIf { it.isNotEmpty() } ?: return false
        if (isExemptHost(reqHost)) return false
        if (pageUrl != null) {
            val pageHost = Uri.parse(pageUrl).host?.let(::normalizeHost)?.takeIf { it.isNotEmpty() }
            if (pageHost != null) {
                if (isHostAllowlisted(pageHost, disabledHosts)) return false
                if (sameSite(reqHost, pageHost)) return false
            }
        }
        return isBlockedHost(reqHost)
    }

    /** Empty `204 No Content` response with permissive CORS header for intercepted subresources. */
    internal fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        204,
        "No Content",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0)),
    )

    /**
     * Strips known tracking query parameters from `http://` and `https://` URLs while preserving the exact order and
     * raw percent-encoding of remaining query parameters and any `#fragment`.
     */
    internal fun stripTrackingParameters(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return url
        }
        val hashIndex = url.indexOf('#')
        val fragment = if (hashIndex >= 0) url.substring(hashIndex) else ""
        val beforeFragment = if (hashIndex >= 0) url.substring(0, hashIndex) else url
        val queryIndex = beforeFragment.indexOf('?')
        if (queryIndex < 0) return url
        val rawQuery = beforeFragment.substring(queryIndex + 1)
        if (rawQuery.isEmpty()) return url

        val parts = rawQuery.split('&')
        val kept = ArrayList<String>(parts.size)
        var stripped = false
        for (part in parts) {
            val rawName = part.substringBefore('=')
            val decodedName = if ('%' in rawName) Uri.decode(rawName) else rawName
            if (decodedName.isNotEmpty() && decodedName.lowercase() in TRACKING_PARAMS) {
                stripped = true
            } else {
                kept.add(part)
            }
        }
        if (!stripped) return url
        val base = beforeFragment.substring(0, queryIndex)
        return if (kept.isEmpty()) "$base$fragment" else "$base?${kept.joinToString("&")}$fragment"
    }

    internal fun sameSite(firstHost: String, secondHost: String): Boolean {
        val a = normalizeHost(firstHost)
        val b = normalizeHost(secondHost)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (isIpLiteral(a) || isIpLiteral(b) || a == "localhost" || b == "localhost") return false
        return registrableDomain(a) == registrableDomain(b)
    }

    internal fun registrableDomain(normalizedHost: String): String {
        val labels = normalizedHost.split('.')
        if (labels.size <= 2) return normalizedHost
        val lastTwo = "${labels[labels.size - 2]}.${labels[labels.size - 1]}"
        return if (lastTwo in TWO_PART_SLDS) "${labels[labels.size - 3]}.$lastTwo" else lastTwo
    }

    internal fun normalizeHost(raw: String): String =
        raw.trim().removeSurrounding("[", "]").lowercase().trimEnd('.')

    internal fun isExemptHost(normalizedHost: String): Boolean =
        normalizedHost == APP_ASSETS_HOST ||
            normalizedHost.endsWith(".$APP_ASSETS_HOST") ||
            normalizedHost == "localhost" ||
            normalizedHost.endsWith(".localhost") ||
            normalizedHost.endsWith(".local") ||
            isIpLiteral(normalizedHost)

    internal fun isIpLiteral(rawHost: String): Boolean {
        val trimmed = rawHost.trim().removeSurrounding("[", "]")
        if (trimmed.isEmpty()) return false
        if (isIpv4Literal(trimmed)) return true
        val withoutZone = trimmed.substringBefore('%')
        if (':' !in withoutZone) return false
        return withoutZone.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
            if (part.length > 1 && part.startsWith('0')) return false
            val octet = part.toIntOrNull() ?: return false
            if (octet !in 0..255) return false
        }
        return true
    }
}
