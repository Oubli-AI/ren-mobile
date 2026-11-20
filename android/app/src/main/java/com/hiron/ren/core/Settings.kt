package com.hiron.ren.core

import android.content.Context
import com.hiron.ren.BuildConfig
import com.hiron.ren.browser.ContentBlocker
import com.hiron.ren.browser.DnsMode
import com.hiron.ren.browser.SearchEngine
import com.hiron.ren.browser.SearchLevel
import com.hiron.ren.device.CarrierStyle
import com.hiron.ren.device.ForwardMode
import com.hiron.ren.ui.ThemeChoice
import com.hiron.ren.voice.Provider
import java.util.UUID

/** This phone's settings, and the permissions the user granted the agents ("Always allow"). */
public class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("ren", Context.MODE_PRIVATE)

    /** Who this phone's user is to the agents (`user_id`): made once, then kept. */
    public val userId: String
        get() = prefs.getString("user_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("user_id", it).apply() }

    /** Ren's Telnyx answering line, where call forwarding points. */
    public var answeringNumber: String
        get() = prefs.getString("answering_number", null) ?: BuildConfig.DEFAULT_ANSWERING_NUMBER
        set(value) = prefs.edit().putString("answering_number", value.trim()).apply()

    public var answeringMode: ForwardMode
        get() = runCatching { ForwardMode.valueOf(prefs.getString("answering_mode", null).orEmpty()) }.getOrDefault(ForwardMode.Unanswered)
        set(value) = prefs.edit().putString("answering_mode", value.name).apply()

    /** Null means detect from the SIM. */
    public var carrierStyle: CarrierStyle?
        get() = prefs.getString("carrier_style", null)?.let { runCatching { CarrierStyle.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit().putString("carrier_style", value?.name).apply()

    /** The number Ren last asked the carrier to forward to ("" after turning it off); the carrier is the truth. */
    public var forwardedTo: String
        get() = prefs.getString("forwarded_to", "").orEmpty()
        set(value) = prefs.edit().putString("forwarded_to", value.trim()).apply()

    /** The Brave Search plan's key, for the agents' search tools (web, news, images, videos, places, page text). */
    /** The open tabs, as [com.hiron.ren.browser.BrowserStore] writes them. */
    public var browserTabs: String?
        get() = prefs.getString("browser_tabs", null)
        set(value) = prefs.edit().putString("browser_tabs", value).apply()

    /** The favorite links, as [com.hiron.ren.browser.Favorites] writes them. */
    public var favorites: String?
        get() = prefs.getString("favorites", null)
        set(value) = prefs.edit().putString("favorites", value).apply()

    /** When the phone's activity last went to ren's Context Agent (epoch ms); 0 until the account is ready for it. */
    public var phoneReportedAt: Long
        get() = prefs.getLong("phone_reported_at", 0L)
        set(value) = prefs.edit().putLong("phone_reported_at", value).apply()

    /** How hard a search from the address bar works. */
    public var searchLevel: SearchLevel
        get() = SearchLevel.of(prefs.getString("search_level", null)) ?: SearchLevel.Default
        set(value) = prefs.edit().putString("search_level", value.wire).apply()

    /** Which search engine answers phrases typed in the address bar. */
    internal var searchEngine: SearchEngine
        get() = SearchEngine.of(prefs.getString("search_engine", null)) ?: SearchEngine.Default
        set(value) = prefs.edit().putString("search_engine", value.wire).apply()

    /** How hostnames are resolved for OkHttp and WebView traffic. */
    internal var dnsMode: DnsMode
        get() = DnsMode.of(prefs.getString("dns_mode", null)) ?: DnsMode.Default
        set(value) = prefs.edit().putString("dns_mode", value.wire).apply()

    /** Custom `https://` DNS-over-HTTPS endpoint used when [dnsMode] is [DnsMode.Custom]. */
    internal var customDohUrl: String
        get() = prefs.getString("custom_doh_url", "").orEmpty()
        set(value) = prefs.edit().putString("custom_doh_url", value.trim()).apply()

    /** Whether third-party ad, tracker and telemetry blocking is on across tabs. */
    internal var shieldsEnabled: Boolean
        get() = prefs.getBoolean("shields_enabled", true)
        set(value) = prefs.edit().putBoolean("shields_enabled", value).apply()

    /** Whether known tracking query parameters are stripped on navigation. */
    internal var stripTrackingParams: Boolean
        get() = prefs.getBoolean("strip_tracking_params", true)
        set(value) = prefs.edit().putBoolean("strip_tracking_params", value).apply()

    /** Normalized lowercase hostnames where Shields blocking is turned off by the user. */
    internal var shieldsDisabledHosts: Set<String>
        get() = prefs.getStringSet("shields_disabled_hosts", null)
            ?.mapNotNull { host -> host.trim().lowercase().trimEnd('.').takeIf(String::isNotEmpty) }
            ?.toSet()
            ?: emptySet()
        set(value) {
            val normalized: Set<String> = value
                .mapNotNull { host -> host.trim().lowercase().trimEnd('.').takeIf(String::isNotEmpty) }
                .toSet()
            prefs.edit().putStringSet("shields_disabled_hosts", HashSet(normalized)).apply()
        }

    internal fun isShieldsEnabledFor(host: String): Boolean =
        shieldsEnabled && !ContentBlocker.isHostAllowlisted(host, shieldsDisabledHosts)

    internal fun setSiteShields(host: String, enabled: Boolean) {
        val normalized = host.trim().lowercase().trimEnd('.')
        if (normalized.isEmpty()) return
        shieldsDisabledHosts = if (enabled) shieldsDisabledHosts - normalized else shieldsDisabledHosts + normalized
    }

    /** Whether bare and cleartext `http://` navigations upgrade to `https://` first. */
    internal var httpsFirst: Boolean
        get() = prefs.getBoolean("https_first", true)
        set(value) = prefs.edit().putBoolean("https_first", value).apply()

    /** The theme chosen in Settings › Browser: Light, Dark, Glass or System (ren desktop's four). */
    public var theme: ThemeChoice
        get() = ThemeChoice.of(prefs.getString("theme", null))
        set(value) = prefs.edit().putString("theme", value.wire).apply()

    /** Whether WebView applies algorithmic darkening to web pages. */
    internal var darkWebPages: Boolean
        get() = prefs.getBoolean("dark_web_pages", false)
        set(value) = prefs.edit().putBoolean("dark_web_pages", value).apply()

    /** Whether web pages keep playing audio and media when Ren moves to the background. */
    internal var backgroundMedia: Boolean
        get() = prefs.getBoolean("background_media", false)
        set(value) = prefs.edit().putBoolean("background_media", value).apply()

    /** Visited web pages in the browser's persistent history, as [com.hiron.ren.browser.BrowserHistory] writes them. */
    internal var browserHistory: String?
        get() = prefs.getString("browser_history", null)
        set(value) = prefs.edit().putString("browser_history", value).apply()

    public var braveSearchKey: String
        get() = prefs.getString("brave_search_key", null) ?: BuildConfig.DEFAULT_BRAVE_SEARCH_KEY
        set(value) = prefs.edit().putString("brave_search_key", value.trim()).apply()

    /** The Brave Answers plan's key, for the `answers` tool. */
    public var braveAnswersKey: String
        get() = prefs.getString("brave_answers_key", null) ?: BuildConfig.DEFAULT_BRAVE_ANSWERS_KEY
        set(value) = prefs.edit().putString("brave_answers_key", value.trim()).apply()

    /** Whether the one-time permission request has been shown. */
    public var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    /** Talk to Ren out loud (the voice agent). Off: Home shows the keyboard and send key only. */
    public var voiceModel: Boolean
        get() = prefs.getBoolean("voice_model", true)
        set(value) = prefs.edit().putBoolean("voice_model", value).apply()

    /**
     * Who runs Ren's agents: the ElevenLabs agents (the default), or Gemini, NVIDIA NIM, Mistral or Telnyx on the
     * workspace VM.
     */
    public var provider: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("provider", null).orEmpty()) }.getOrDefault(Provider.ElevenLabs)
        set(value) = prefs.edit().putString("provider", value.name).apply()

    /** The [provider]'s text model, which also runs the voice agent's helpers; null is the provider's default. */
    public fun textModel(provider: Provider): String? = prefs.getString("text_model_${provider.name}", null)

    public fun setTextModel(provider: Provider, model: String?) {
        prefs.edit().putString("text_model_${provider.name}", model).putString("effort_${provider.name}", AUTO).apply()
    }

    /** The reasoning level for the [provider]'s text model, one the model lists, or [AUTO] for the provider's own. */
    public fun effort(provider: Provider): String = prefs.getString("effort_${provider.name}", null) ?: AUTO

    public fun setEffort(provider: Provider, effort: String): Unit = prefs.edit().putString("effort_${provider.name}", effort).apply()

    /**
     * Work mode: the folder on the user's own computer that the Strands agents work in, through Desktop Commander on
     * Ren's hub (its `local_*` tools, `deploy/local/computer.py`); null is off.
     */
    public var workFolder: String?
        get() = prefs.getString("work_folder", null)
        set(value) = prefs.edit().putString("work_folder", value?.trim()?.ifEmpty { null }).apply()

    /** The bearer token for the workspace VM: the Strands agents, Ren's hub and Ren's search. */
    public var geminiToken: String
        get() = prefs.getString("gemini_token", null) ?: BuildConfig.DEFAULT_GEMINI_TOKEN
        set(value) = prefs.edit().putString("gemini_token", value.trim()).apply()

    /** Whether the phone stays attached to Ren's hub (Settings › Remote tools). */
    public var hubEnabled: Boolean
        get() = prefs.getBoolean("hub_enabled", false)
        set(value) = prefs.edit().putBoolean("hub_enabled", value).apply()

    /** Ren's hub on the workspace VM, as a WebSocket base. */
    public var hubUrl: String
        get() = prefs.getString("hub_url", null) ?: DEFAULT_HUB_URL
        set(value) = prefs.edit().putString("hub_url", value.trim()).apply()

    public fun granted(action: PermAction): Boolean = prefs.getString(action.variable, PROMPT) == GRANTED

    public fun grant(action: PermAction, granted: Boolean): Unit = prefs.edit().putString(action.variable, if (granted) GRANTED else PROMPT).apply()

    /** Every `perm_*` dynamic variable, "granted" or "prompt". */
    public fun permissionVariables(): Map<String, String> = PermAction.entries.associate { it.variable to if (granted(it)) GRANTED else PROMPT }

    public companion object {
        public const val GRANTED: String = "granted"
        public const val PROMPT: String = "prompt"
        public const val AUTO: String = "auto"

        /** Ren's hub on the owner's workspace VM. */
        public val DEFAULT_HUB_URL: String = "wss://${BuildConfig.WORKSPACE_HOST}/hub"
    }
}
