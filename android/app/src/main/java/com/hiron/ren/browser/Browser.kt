package com.hiron.ren.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.SafeBrowsingResponseCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hiron.ren.MainActivity
import com.hiron.ren.R
import com.hiron.ren.core.Settings
import com.hiron.ren.ui.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URISyntaxException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Main-frame network or DNS failure reported by WebView for a tab. */
public data class PageError(
    public val code: Int,
    public val description: String,
    public val failingUrl: String,
)

/** TLS certificate failure holding navigation until the user goes back or explicitly proceeds. */
public data class SslWarning(
    public val url: String,
    public val host: String,
    public val primaryError: Int,
)

/** HTTP Basic/Digest authentication challenge requested by an origin. */
internal data class HttpAuthPrompt(
    val id: String,
    val host: String,
    val realm: String,
)

/** Kind of modal JavaScript dialog requested by a page. */
internal enum class JsDialogKind {
    Alert,
    Confirm,
    Prompt,
    BeforeUnload,
}

/** Modal JavaScript dialog waiting for user confirmation or input in the browser chrome. */
internal data class JsDialogPrompt(
    val id: String,
    val url: String,
    val kind: JsDialogKind,
    val message: String,
    val defaultValue: String = "",
)

/** Link and/or image under a long-press gesture on a web page. */
internal data class ContextMenuTarget(
    val linkUrl: String?,
    val linkText: String?,
    val imageUrl: String?,
)

/** Find-in-page query and active match counters for the current tab. */
internal data class FindState(
    val active: Boolean = false,
    val query: String = "",
    val currentMatch: Int = 0,
    val totalMatches: Int = 0,
)

/** Categories of persistent browser state the user can wipe from Settings or the menu. */
internal data class ClearDataOptions(
    val history: Boolean = true,
    val cookiesAndStorage: Boolean = true,
    val cache: Boolean = true,
    val sitePermissions: Boolean = false,
)

/** One tab as the chrome draws it. [url] is what the address bar holds: a web address or one of Ren's `ren://` pages. */
public data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "",
    val loading: Boolean = false,
    /** How much of the page has loaded, 0 to 100. */
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val favicon: Bitmap? = null,
    val incognito: Boolean = false,
    val desktopSite: Boolean = false,
    val blockedCount: Int = 0,
    val error: PageError? = null,
    val sslWarning: SslWarning? = null,
) {
    /** The tab is on Ren's home page, which the app draws itself. */
    val home: Boolean get() = url == Addresses.HOME

    /** What a tab list calls it: the page's title, or its address until it has one. */
    val label: String
        get() = when {
            home -> "Home"
            title.isNotBlank() -> title
            else -> Addresses.resting(url)
        }
}

public data class BrowserState(val tabs: List<BrowserTab> = emptyList(), val active: String? = null) {
    val current: BrowserTab? get() = tabs.firstOrNull { it.id == active }
}

/** A file a page wants to save, waiting for the user's yes. */
public data class DownloadRequest(
    val url: String,
    val name: String,
    val host: String,
    val mime: String?,
    val bytes: Long,
    val userAgent: String?,
)

/**
 * Ren's browser: its tabs, each a real WebView once it leaves home, and everything the chrome and Ren's browser tools
 * do to them. All of it runs on the main thread, as WebView requires.
 *
 * A tab opened from home keeps home under its first page, so Back from that page returns to it. Ren's own pages
 * (`ren://search` and ren desktop's destinations, [Destination]) are the app's bundled pages, loaded from its assets;
 * only they can talk to the app, and what they can ask for is [token], the workspace VM's token their requests to Ren's
 * backend carry, and the [favorites], which Discover shows and changes as the home page does.
 */
public class Browser internal constructor(
    private val context: Context,
    private val store: BrowserStore,
    private val favorites: Favorites,
    private val token: () -> String,
    internal val history: BrowserHistory = BrowserHistory({ null }, {}),
    internal val sitePermissions: SitePermissionsManager = SitePermissionsManager(context),
    private val publicSuffixList: PublicSuffixList? = null,
    private val browserSettings: Settings? = null,
    private val dns: BrowserDns? = null,
) {
    public constructor(
        context: Context,
        store: BrowserStore,
        favorites: Favorites,
        token: () -> String,
    ) : this(
        context = context,
        store = store,
        favorites = favorites,
        token = token,
        history = BrowserHistory({ null }, {}),
        sitePermissions = SitePermissionsManager(context),
        publicSuffixList = null,
        browserSettings = null,
        dns = null,
    )

    private val mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state: MutableStateFlow<BrowserState> = MutableStateFlow(BrowserState())
    public val state: StateFlow<BrowserState> = _state.asStateFlow()

    /** A download a page asked for; the chrome shows it and answers with [download] or [dismissDownload]. */
    public val downloadRequest: MutableStateFlow<DownloadRequest?> = MutableStateFlow(null)

    /** A page's full-screen view (a video), when one is showing; the chrome lays it over everything. */
    public val fullscreen: MutableStateFlow<View?> = MutableStateFlow(null)
    private var leaveFullscreen: WebChromeClient.CustomViewCallback? = null

    private val _findState: MutableStateFlow<FindState> = MutableStateFlow(FindState())
    internal val findState: StateFlow<FindState> = _findState.asStateFlow()

    private val _contextMenu: MutableStateFlow<ContextMenuTarget?> = MutableStateFlow(null)
    internal val contextMenu: StateFlow<ContextMenuTarget?> = _contextMenu.asStateFlow()

    private val _httpAuth: MutableStateFlow<HttpAuthPrompt?> = MutableStateFlow(null)
    internal val httpAuth: StateFlow<HttpAuthPrompt?> = _httpAuth.asStateFlow()

    private val _jsDialog: MutableStateFlow<JsDialogPrompt?> = MutableStateFlow(null)
    internal val jsDialog: StateFlow<JsDialogPrompt?> = _jsDialog.asStateFlow()

    /** A web page a tab finished loading, by address and title: ren's Context Agent hears about it. */
    public var visited: (url: String, title: String) -> Unit = { _, _ -> }

    /** The ren account one of Ren's pages is signed in to, whenever it reports it. */
    public var accountReported: (RenAccount) -> Unit = {}

    /** Shows the system's file picker for a page's upload field; set by the activity while it is on screen. */
    public var fileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null

    /** Access-ordered live WebViews so oldest background views evict first above [MAX_LIVE_WEBVIEWS]. */
    private val views: LinkedHashMap<String, WebView> = LinkedHashMap(16, 0.75f, true)
    private val defaultUserAgents: HashMap<String, String> = HashMap()
    private val httpAllowedHosts: HashSet<String> = HashSet()
    private val httpsUpgradedUrls: HashMap<String, String> = HashMap()
    private val pendingSslHandlers: HashMap<String, SslErrorHandler> = HashMap()
    private var pendingHttpAuth: PendingHttpAuth? = null
    private var pendingJsDialog: PendingJsDialog? = null

    @Volatile
    private var pslPrefetch: Deferred<Unit>? = null

    /** Tabs standing on home with a page still loaded under them, for Forward. */
    private val overHome: HashSet<String> = HashSet()
    private val loader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
        .build()

    init {
        if (publicSuffixList != null) {
            ioScope.launch {
                val job = publicSuffixList.prefetch()
                pslPrefetch = job
                job.await()
            }
        }
        registerServiceWorkerBlocker()
        val saved = store.load()
        if (saved.tabs.isEmpty()) { val _ = open() } else _state.value = saved
    }

    /** Opens a tab on [url] (home by default) and returns its id. */
    public fun open(url: String = Addresses.HOME, select: Boolean = true, incognito: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        if (select && _findState.value.active) {
            clearFind()
        }
        change {
            it.copy(
                tabs = it.tabs + BrowserTab(id = id, url = Addresses.HOME, incognito = incognito),
                active = if (select) id else it.active,
            )
        }
        if (url != Addresses.HOME) load(id, url)
        return id
    }

    public fun select(id: String) {
        val previousActive = _state.value.active
        if (previousActive != id && _findState.value.active) {
            clearFind()
        }
        change { if (it.tabs.any { tab -> tab.id == id }) it.copy(active = id) else it }
    }

    /** Closes a tab; the one after it (or before it) becomes active, and the last tab closing leaves a fresh home. */
    public fun close(id: String) {
        val before = _state.value
        val index = before.tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val closingTab = before.tabs[index]
        if (!closingTab.incognito && !closingTab.home) {
            history.pushClosedTab(closingTab.url, closingTab.title)
        }
        cancelTabPrompts(id)
        if (before.active == id && _findState.value.active) {
            _findState.value = FindState()
        }
        views.remove(id)?.let(::destroy)
        defaultUserAgents.remove(id)
        httpsUpgradedUrls.remove(id)
        overHome.remove(id)
        deleteTabStateBundle(id)

        val tabs = before.tabs.filterNot { it.id == id }
        val active = if (before.active != id) before.active else (tabs.getOrNull(index) ?: tabs.lastOrNull())?.id
        change { it.copy(tabs = tabs, active = active) }

        if (closingTab.incognito && tabs.none { it.incognito }) {
            cleanupPrivateSession()
        }
        if (tabs.isEmpty()) { val _ = open() }
    }

    /**
     * Closes all tabs matching [incognitoOnly] (`null` = every tab, `true` = private tabs only, `false` = normal tabs
     * only), recording normal tabs for reopen, tearing down the private profile once all private WebViews are destroyed,
     * and leaving a fresh normal Home tab when no tabs remain.
     */
    internal fun closeAll(incognitoOnly: Boolean? = null) {
        val before = _state.value
        val closing = before.tabs.filter { tab ->
            when (incognitoOnly) {
                null -> true
                true -> tab.incognito
                false -> !tab.incognito
            }
        }
        if (closing.isEmpty()) return
        val closingIds = closing.map { it.id }.toSet()
        var closedAnyIncognito = false
        for (tab in closing) {
            if (tab.incognito) {
                closedAnyIncognito = true
            } else if (!tab.home) {
                history.pushClosedTab(tab.url, tab.title)
            }
            cancelTabPrompts(tab.id)
            views.remove(tab.id)?.let(::destroy)
            defaultUserAgents.remove(tab.id)
            httpsUpgradedUrls.remove(tab.id)
            overHome.remove(tab.id)
            deleteTabStateBundle(tab.id)
        }
        if (before.active in closingIds && _findState.value.active) {
            _findState.value = FindState()
        }
        val remaining = before.tabs.filterNot { it.id in closingIds }
        val nextActive = before.active?.takeIf { activeId -> remaining.any { it.id == activeId } }
            ?: remaining.lastOrNull()?.id
        change { it.copy(tabs = remaining, active = nextActive) }

        if (closedAnyIncognito && remaining.none { it.incognito }) {
            cleanupPrivateSession()
        }
        if (remaining.isEmpty()) {
            val _ = open()
        }
    }

    /** Reopens the most recently closed normal tab and selects it; null when none are waiting. */
    internal fun reopenClosedTab(): String? {
        val closed = history.popClosedTab() ?: return null
        return open(url = closed.url, select = true, incognito = false)
    }

    /** Takes the active tab to what was typed: an address loads and a phrase searches at [level]. */
    public fun go(typed: String, level: SearchLevel) {
        val id = _state.value.active ?: return
        val engine = browserSettings?.searchEngine ?: SearchEngine.Ren
        val psl = publicSuffixList
        val resolved = if (psl != null) {
            val prefetch = pslPrefetch ?: psl.prefetch().also { pslPrefetch = it }
            if (prefetch.isCompleted) {
                runBlocking { Addresses.resolve(typed, level, engine, psl) }
            } else {
                Addresses.resolve(typed, level, engine)
            }
        } else {
            Addresses.resolve(typed, level, engine)
        }
        if (resolved.isEmpty()) return
        val finalUrl = if (browserSettings?.stripTrackingParams != false) {
            ContentBlocker.stripTrackingParameters(resolved)
        } else {
            resolved
        }
        load(id, finalUrl)
    }

    /** Loads [url] in the tab [id]: home, one of Ren's pages, or a web address. */
    public fun load(id: String, url: String) {
        if (url == Addresses.HOME) return home(id)
        cancelTabPrompts(id)
        overHome.remove(id)
        val sanitized = if (browserSettings?.stripTrackingParams == true) {
            ContentBlocker.stripTrackingParameters(url)
        } else {
            url
        }
        val targetUrl = applyHttpsFirst(id, sanitized)
        view(id).loadUrl(internal(targetUrl) ?: targetUrl)
        update(id) {
            it.copy(
                url = targetUrl,
                title = "",
                loading = true,
                progress = 0,
                favicon = null,
                blockedCount = 0,
                error = null,
                sslWarning = null,
            )
        }
    }

    public fun back() {
        val tab = _state.value.current ?: return
        if (tab.sslWarning != null) {
            goBackFromSslWarning(tab.id)
            return
        }
        val view = views[tab.id]
        when {
            tab.home -> Unit
            view != null && view.canGoBack() -> {
                update(tab.id) { it.copy(error = null, sslWarning = null) }
                view.goBack()
            }
            else -> home(tab.id)
        }
    }

    public fun forward() {
        val tab = _state.value.current ?: return
        val view = views[tab.id] ?: return
        if (tab.home) {
            if (overHome.remove(tab.id)) sync(tab.id, view)
        } else if (view.canGoForward()) {
            update(tab.id) { it.copy(error = null, sslWarning = null) }
            view.goForward()
        }
    }

    /** Loads Ren's own pages again (the theme changed); web pages stay as they are. */
    internal fun reloadPages() {
        views.values.filter { it.url?.startsWith(PAGES) == true }.forEach(WebView::reload)
    }

    /**
     * Opens a social sign-in's provider page (https only) in another browser's Custom Tab, since Google refuses
     * sign-in inside an app's WebView; with no other browser that offers Custom Tabs it opens in a new tab here.
     */
    private fun signInPage(url: String, incognito: Boolean) {
        val page = Uri.parse(url)
        if (page.scheme != "https") return
        val browsers = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://")), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
        val custom = CustomTabsClient.getPackageName(context, browsers, true)
        if (custom == null) {
            val _ = open(url, select = true, incognito = incognito)
            return
        }
        val tab = CustomTabsIntent.Builder().build()
        tab.intent.setPackage(custom)
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(context, page)
    }

    /** The theme chosen in Settings, as ren's pages name it. */
    private fun theme(): String = (browserSettings?.theme ?: ThemeChoice.System).wire

    public fun reload() {
        val tab = _state.value.current ?: return
        if (tab.error != null) {
            retryError(tab.id)
            return
        }
        if (!tab.home) {
            update(tab.id) { it.copy(error = null, sslWarning = null, blockedCount = 0) }
            views[tab.id]?.reload()
        }
    }

    public fun stop() {
        _state.value.current?.let { current ->
            views[current.id]?.stopLoading()
            update(current.id) { it.copy(loading = false) }
        }
    }

    /** Toggles desktop user-agent mode on [id] and reloads its page. */
    internal fun toggleDesktopSite(id: String = _state.value.active.orEmpty()) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        val nextDesktop = !tab.desktopSite
        update(id) { it.copy(desktopSite = nextDesktop) }
        val targetView = views[id] ?: return
        val mobileUa = defaultUserAgents[id].orEmpty()
        targetView.settings.userAgentString = if (nextDesktop) DESKTOP_USER_AGENT else mobileUa
        targetView.settings.useWideViewPort = true
        targetView.settings.loadWithOverviewMode = true
        if (!tab.home) targetView.reload()
    }

    /** Opens the find-in-page bar for the active tab. */
    internal fun startFind() {
        _findState.value = _findState.value.copy(active = true)
    }

    /** Highlights occurrences of [query] in the active tab's WebView and streams match counts to [findState]. */
    internal fun findInPage(query: String) {
        val activeId = _state.value.active
        val tab = _state.value.tabs.firstOrNull { it.id == activeId }
        val activeView = if (activeId != null && tab != null && !tab.home) views[activeId] else null
        if (query.isEmpty()) {
            activeView?.clearMatches()
            _findState.value = FindState(active = true, query = "", currentMatch = 0, totalMatches = 0)
            return
        }
        _findState.value = _findState.value.copy(active = true, query = query)
        if (activeView != null) {
            activeView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                if (_findState.value.active && _findState.value.query.isNotEmpty()) {
                    val current = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
                    _findState.value = _findState.value.copy(
                        currentMatch = current,
                        totalMatches = numberOfMatches,
                    )
                }
            }
            activeView.findAllAsync(query)
        }
    }

    /** Steps forward or backward across highlighted matches in the active tab. */
    internal fun findNext(forward: Boolean = true) {
        val activeId = _state.value.active ?: return
        views[activeId]?.findNext(forward)
    }

    /** Clears find-in-page highlights and hides the find bar. */
    internal fun clearFind() {
        val activeId = _state.value.active
        if (activeId != null) {
            views[activeId]?.clearMatches()
        }
        _findState.value = FindState()
    }

    internal fun dismissContextMenu() {
        _contextMenu.value = null
    }

    /** Queues a user-confirmed download for a long-pressed link or image (`http`, `https`, `data`, or `blob`). */
    internal fun saveUrl(url: String, suggestedName: String? = null) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val parsed = Uri.parse(trimmed)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return
        when (scheme) {
            "http", "https" -> {
                val activeUa = _state.value.active?.let { activeId -> views[activeId]?.settings?.userAgentString }
                val fileName = suggestedName?.trim()?.takeIf(String::isNotEmpty)
                    ?: URLUtil.guessFileName(trimmed, null, null)
                downloadRequest.value = DownloadRequest(
                    url = trimmed,
                    name = fileName,
                    host = parsed.host.orEmpty(),
                    mime = null,
                    bytes = -1L,
                    userAgent = activeUa,
                )
            }
            "data" -> {
                val dataUri = parseDataUri(trimmed) ?: return
                val fileName = suggestedName?.trim()?.takeIf(String::isNotEmpty)
                    ?: URLUtil.guessFileName("download", null, dataUri.mime)
                downloadRequest.value = DownloadRequest(
                    url = trimmed,
                    name = fileName,
                    host = "data",
                    mime = dataUri.mime,
                    bytes = dataUri.bytes.size.toLong(),
                    userAgent = null,
                )
            }
            "blob" -> {
                val fileName = suggestedName?.trim()?.takeIf(String::isNotEmpty) ?: "download.bin"
                downloadRequest.value = DownloadRequest(
                    url = trimmed,
                    name = fileName,
                    host = parsed.host.orEmpty().ifEmpty { "blob" },
                    mime = null,
                    bytes = -1L,
                    userAgent = null,
                )
            }
        }
    }

    /** Supplies credentials to a waiting HTTP authentication challenge, or cancels when null. */
    internal fun answerHttpAuth(promptId: String, username: String?, password: String?) {
        val current = pendingHttpAuth
        if (current == null || current.prompt.id != promptId) return
        pendingHttpAuth = null
        _httpAuth.value = null
        if (username != null && password != null) {
            current.handler.proceed(username, password)
        } else {
            current.handler.cancel()
        }
    }

    /** Answers a waiting JavaScript `alert`, `confirm`, `prompt`, or `beforeunload` modal. */
    internal fun answerJsDialog(promptId: String, confirmed: Boolean, promptInput: String? = null) {
        val current = pendingJsDialog
        if (current == null || current.prompt.id != promptId) return
        pendingJsDialog = null
        _jsDialog.value = null
        if (confirmed) {
            current.callback.confirm(promptInput)
        } else {
            current.callback.cancel()
        }
    }

    /** Reloads the failing URL on [id] after a [PageError]. */
    internal fun retryError(id: String = _state.value.active.orEmpty()) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        val targetUrl = tab.error?.failingUrl?.takeIf(String::isNotEmpty) ?: tab.url
        if (targetUrl.isNotEmpty() && targetUrl != Addresses.HOME) {
            load(id, targetUrl)
        }
    }

    /** Proceeds past an active [SslWarning] on [id] only after explicit user confirmation. */
    internal fun proceedPastSslWarning(id: String = _state.value.active.orEmpty()) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        val warning = tab.sslWarning
        val handler = pendingSslHandlers.remove(id)
        update(id) { it.copy(sslWarning = null, loading = handler != null) }
        if (handler != null) {
            handler.proceed()
        } else if (warning != null && warning.url.isNotEmpty()) {
            load(id, warning.url)
        }
    }

    /** Cancels an active [SslWarning] on [id] and navigates back to safety (or Home). */
    internal fun goBackFromSslWarning(id: String = _state.value.active.orEmpty()) {
        pendingSslHandlers.remove(id)?.cancel()
        update(id) { it.copy(sslWarning = null, loading = false) }
        val targetView = views[id]
        if (targetView != null && targetView.canGoBack()) {
            targetView.goBack()
        } else {
            home(id)
        }
    }

    /** Opens Android's system print / Save-as-PDF spooler for the active web tab. */
    internal fun printCurrentTab(activity: Activity): Boolean {
        val tab = _state.value.current?.takeUnless { it.home } ?: return false
        val activeView = views[tab.id] ?: return false
        val printManager = activity.getSystemService(PrintManager::class.java) ?: return false
        val jobName = tab.label.ifBlank { "Ren Page" }
        val adapter = activeView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
        return true
    }

    /** Requests a pinned launcher shortcut on the user's Home screen for the active `http(s)` tab. */
    internal fun pinCurrentTabShortcut(): Boolean {
        val tab = _state.value.current?.takeUnless { it.home } ?: return false
        val parsed = Uri.parse(tab.url)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val label = tab.label.ifBlank { tab.url }
        val icon = tab.favicon?.let(IconCompat::createWithBitmap)
            ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val launchIntent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(parsed)
        val shortcutId = "web-${tab.url.hashCode().toUInt().toString(16)}"
        val info = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label.take(SHORT_LABEL_MAX))
            .setLongLabel(label.take(LONG_LABEL_MAX))
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** Vertical scroll offset of the active tab's WebView in pixels, or `0` on Home. */
    internal fun currentTabScrollY(): Int {
        val tab = _state.value.current?.takeUnless { it.home } ?: return 0
        return views[tab.id]?.scrollY ?: 0
    }

    /** Re-applies dynamic WebView and DNS preferences (such as algorithmic darkening and DoH) across live tabs. */
    internal fun applyWebPreferences() {
        val currentSettings = browserSettings
        if (currentSettings != null) {
            dns?.configure(currentSettings.dnsMode, currentSettings.customDohUrl, currentSettings.shieldsEnabled)
        }
        val allowDark = currentSettings?.darkWebPages == true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            for (liveView in views.values.toList()) {
                val _ = runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(liveView.settings, allowDark) }
            }
        }
    }

    /** Wipes the selected browsing data categories and invokes [onDone] on the main thread when complete. */
    internal fun clearBrowsingData(options: ClearDataOptions, onDone: () -> Unit = {}) {
        val liveViews = views.values.toList()
        if (options.history) {
            history.clearAll()
            history.clearClosedTabs()
            for (liveView in liveViews) {
                liveView.clearHistory()
                liveView.clearFormData()
            }
            File(context.filesDir, TAB_STATES_DIR).listFiles()?.forEach { file ->
                val _ = runCatching { file.delete() }
            }
            change { current ->
                current.copy(tabs = current.tabs.map { it.copy(canGoBack = !it.home, canGoForward = false) })
            }
        }
        if (options.cache) {
            httpAllowedHosts.clear()
            for (liveView in liveViews) {
                liveView.clearCache(true)
                liveView.clearSslPreferences()
            }
        }
        if (options.cookiesAndStorage) {
            val _ = runCatching {
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            }
            val _ = runCatching { WebStorage.getInstance().deleteAllData() }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                val _ = runCatching {
                    val profileStore = ProfileStore.getInstance()
                    for (profileName in listOf(Profile.DEFAULT_PROFILE_NAME, PRIVATE_PROFILE)) {
                        val profile = profileStore.getProfile(profileName)
                        if (profile != null) {
                            profile.cookieManager.removeAllCookies(null)
                            profile.cookieManager.flush()
                            profile.webStorage.deleteAllData()
                        }
                    }
                }
            }
        }
        if (options.sitePermissions) {
            sitePermissions.clearTemporary()
            mainScope.launch {
                sitePermissions.clearAll()
                onDone()
            }
        } else {
            onDone()
        }
    }

    /** The tab's WebView for the chrome to show; null while the tab is on home. A tab restored from disk loads here. */
    public fun shown(id: String): WebView? {
        val tab = _state.value.tabs.firstOrNull { it.id == id }?.takeUnless { it.home } ?: return null
        return views[id] ?: view(id).also { created ->
            if (!restoreTabStateBundle(id, created)) {
                created.loadUrl(internal(tab.url) ?: tab.url)
            }
        }
    }

    /** Says yes to [downloadRequest]: saves `data:`/`blob:` payloads or hands `http(s)` URLs to [DownloadManager]. */
    public fun download() {
        val request = downloadRequest.value ?: return
        downloadRequest.value = null
        val parsed = Uri.parse(request.url)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return
        when (scheme) {
            "data" -> {
                val decoded = parseDataUri(request.url) ?: return
                saveBytesToDownloads(request.name, decoded.mime, decoded.bytes)
            }
            "blob" -> downloadBlobFromActiveTab(request.url, request.name)
            "http", "https" -> {
                val job = DownloadManager.Request(parsed)
                    .setTitle(request.name)
                    .setMimeType(request.mime)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.name)
                request.userAgent?.let { ua -> job.addRequestHeader("User-Agent", ua) }
                val activeIncognito = _state.value.current?.incognito == true
                val cookie = if (activeIncognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    runCatching {
                        ProfileStore.getInstance().getProfile(PRIVATE_PROFILE)?.cookieManager?.getCookie(request.url)
                    }.getOrNull()
                } else if (!activeIncognito) {
                    runCatching { CookieManager.getInstance().getCookie(request.url) }.getOrNull()
                } else {
                    null
                }
                cookie?.let { header -> job.addRequestHeader("Cookie", header) }
                context.getSystemService(DownloadManager::class.java)?.enqueue(job)
            }
        }
    }

    public fun dismissDownload() {
        downloadRequest.value = null
    }

    public fun exitFullscreen() {
        leaveFullscreen?.onCustomViewHidden()
        leaveFullscreen = null
        fullscreen.value = null
    }

    /**
     * Runs [script] in the tab's page and returns what it evaluates to, as the JSON the WebView hands back. Null when
     * the tab has no page (it is on home).
     */
    public suspend fun evaluate(id: String, script: String): String? {
        val view = shown(id) ?: return null
        return suspendCancellableCoroutine { answer -> view.evaluateJavascript(script) { answer.resume(it) } }
    }

    /**
     * Taps the tab's page at a point of its viewport, [x] and [y] as fractions of its width and height. False when the
     * tab isn't on screen, where nothing can be touched.
     */
    public fun tap(id: String, x: Float, y: Float): Boolean {
        val view = shown(id)?.takeIf { it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 } ?: return false
        val at = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x * view.width, y * view.height, 0)
        val up = MotionEvent.obtain(at, at + TAP_MS, MotionEvent.ACTION_UP, x * view.width, y * view.height, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        return true
    }

    /** The tab's page as it is drawn now, as a PNG; null when the tab has no page or it isn't laid out on screen. */
    public fun capture(id: String): ByteArray? {
        val view = shown(id)?.takeIf { it.width > 0 && it.height > 0 } ?: return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /**
     * Keeps the workspace token as the cookie `ren_token` on ren's backend, for what a page can't add a header to:
     * WebSockets and iframes. Caddy takes it in place of X-Ren-Token.
     */
    private fun backendCookie() {
        CookieManager.getInstance().setCookie(Applications.API, "ren_token=${token()}; Path=/; Secure; HttpOnly; SameSite=None")
    }

    /** The tab's page as it is drawn now, as a PNG data URL; null as for [capture]. */
    public fun captureDataUrl(id: String): String? =
        capture(id)?.let { "data:image/png;base64," + Base64.encodeToString(it, Base64.NO_WRAP) }

    /** Pauses or resumes every page's timers and media with the app. */
    public fun visible(visible: Boolean) {
        views.values.toList().forEach { if (visible) it.onResume() else it.onPause() }
    }

    internal fun liveViewCount(): Int = views.size

    private fun home(id: String) {
        cancelTabPrompts(id)
        if (views.containsKey(id)) overHome.add(id)
        views[id]?.stopLoading()
        update(id) {
            it.copy(
                url = Addresses.HOME,
                title = "",
                loading = false,
                progress = 0,
                favicon = null,
                canGoBack = false,
                canGoForward = overHome.contains(id),
                blockedCount = 0,
                error = null,
                sslWarning = null,
            )
        }
    }

    private fun view(id: String): WebView {
        val existing = views[id]
        if (existing != null) return existing
        val created = create(id)
        views[id] = created
        evictExcessWebViews()
        return created
    }

    private fun change(transform: (BrowserState) -> BrowserState) {
        _state.update(transform)
        store.save(_state.value)
    }

    private fun update(id: String, transform: (BrowserTab) -> BrowserTab) = change { current ->
        current.copy(tabs = current.tabs.map { if (it.id == id) transform(it) else it })
    }

    private fun incrementBlockedCount(id: String) {
        _state.update { current ->
            current.copy(tabs = current.tabs.map { if (it.id == id) it.copy(blockedCount = it.blockedCount + 1) else it })
        }
    }

    private fun isIncognito(id: String): Boolean =
        _state.value.tabs.firstOrNull { it.id == id }?.incognito == true

    /** Brings the tab's state in line with its WebView after a navigation. */
    private fun sync(id: String, view: WebView) {
        if (overHome.contains(id)) return
        val url = view.url?.let(::display) ?: return
        update(id) {
            it.copy(
                url = url,
                title = view.title.orEmpty().takeUnless { pageTitle -> pageTitle == view.url }.orEmpty(),
                canGoBack = true,
                canGoForward = view.canGoForward(),
            )
        }
    }

    private fun destroy(view: WebView) {
        val parent = view.parent
        if (parent is ViewGroup) parent.removeView(view)
        view.destroy()
    }

    private fun cleanupPrivateSession() {
        sitePermissions.clearTemporary()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val _ = runCatching {
                val profileStore = ProfileStore.getInstance()
                val privateProfile = profileStore.getProfile(PRIVATE_PROFILE)
                if (privateProfile != null) {
                    privateProfile.cookieManager.removeAllCookies(null)
                    privateProfile.cookieManager.flush()
                    privateProfile.webStorage.deleteAllData()
                    privateProfile.geolocationPermissions.clearAll()
                }
                profileStore.deleteProfile(PRIVATE_PROFILE)
            }
        }
    }

    private fun cancelTabPrompts(tabId: String) {
        pendingSslHandlers.remove(tabId)?.cancel()
        val auth = pendingHttpAuth
        if (auth != null && auth.tabId == tabId) {
            pendingHttpAuth = null
            _httpAuth.value = null
            auth.handler.cancel()
        }
        val dialog = pendingJsDialog
        if (dialog != null && dialog.tabId == tabId) {
            pendingJsDialog = null
            _jsDialog.value = null
            dialog.callback.cancel()
        }
    }

    private fun applyHttpsFirst(tabId: String, rawUrl: String): String {
        httpsUpgradedUrls.remove(tabId)
        val candidate = if (
            !rawUrl.contains("://") &&
            !rawUrl.startsWith("about:", ignoreCase = true) &&
            !rawUrl.startsWith("data:", ignoreCase = true) &&
            !rawUrl.startsWith("blob:", ignoreCase = true) &&
            !rawUrl.startsWith("javascript:", ignoreCase = true)
        ) {
            Addresses.toNavigableUrl(rawUrl)
        } else {
            rawUrl
        }
        if (browserSettings?.httpsFirst != true || !candidate.startsWith("http://", ignoreCase = true)) {
            return candidate
        }
        val parsed = Uri.parse(candidate)
        val host = parsed.host?.let(ContentBlocker::normalizeHost).orEmpty()
        if (
            host.isEmpty() ||
            host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            !host.contains('.') ||
            ContentBlocker.isIpLiteral(host) ||
            host in httpAllowedHosts
        ) {
            return candidate
        }
        val upgraded = "https://${candidate.substring(7)}"
        httpsUpgradedUrls[tabId] = candidate
        return upgraded
    }

    private fun registerServiceWorkerBlocker() {
        if (
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) {
            val _ = runCatching {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                    object : ServiceWorkerClientCompat() {
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                            if (request.url.host == ORIGIN_HOST) return loader.shouldInterceptRequest(request.url)
                            val shieldsOn = browserSettings?.shieldsEnabled ?: true
                            val disabledHosts = browserSettings?.shieldsDisabledHosts ?: emptySet()
                            return if (ContentBlocker.shouldBlockRequest(request.url, null, shieldsOn, disabledHosts)) {
                                ContentBlocker.emptyResponse()
                            } else {
                                null
                            }
                        }
                    },
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(id: String): WebView {
        val webView = WebView(MutableContextWrapper(context))
        val incognitoTab = isIncognito(id)
        if (incognitoTab && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val _ = runCatching {
                ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                WebViewCompat.setProfile(webView, PRIVATE_PROFILE)
            }
        }
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val webSettings = webView.settings
        defaultUserAgents[id] = webSettings.userAgentString.orEmpty()
        if (_state.value.tabs.firstOrNull { it.id == id }?.desktopSite == true) {
            webSettings.userAgentString = DESKTOP_USER_AGENT
        }
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = false
        webSettings.allowContentAccess = false
        webSettings.setSupportMultipleWindows(true)
        webSettings.javaScriptCanOpenWindowsAutomatically = false
        webSettings.mediaPlaybackRequiresUserGesture = true
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.safeBrowsingEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            val _ = runCatching {
                WebSettingsCompat.setWebAuthenticationSupport(
                    webSettings,
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER,
                )
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
            val _ = runCatching { WebSettingsCompat.setBackForwardCacheEnabled(webSettings, true) }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            val _ = runCatching {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, browserSettings?.darkWebPages == true)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PAYMENT_REQUEST)) {
            val _ = runCatching { WebSettingsCompat.setPaymentRequestEnabled(webSettings, true) }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = Pages(id)
        webView.webChromeClient = Chrome(id)
        webView.setOnLongClickListener {
            handleLongPress(webView)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Ren's pages ask for the workspace token their requests carry, the theme and the favorites, give back the
            // favorites Discover changed and say which ren account they are signed in to; no other origin reaches this
            // listener.
            WebViewCompat.addWebMessageListener(webView, "RenHost", setOf("https://$ORIGIN_HOST")) { _, message, _, isMainFrame, reply ->
                val asked = message.data?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (isMainFrame && asked != null) {
                    when (asked.optString("type")) {
                        "token" -> {
                            backendCookie()
                            reply.postMessage(JSONObject().put("type", "token").put("token", token()).toString())
                        }
                        "theme" -> reply.postMessage(JSONObject().put("type", "theme").put("theme", theme()).toString())
                        "oauth-redirect" -> {
                            val redirect = OAuthLoopback(context.packageName) { url ->
                                reply.postMessage(JSONObject().put("type", "oauth-callback").put("url", url).toString())
                            }.start()
                            reply.postMessage(JSONObject().put("type", "oauth-redirect").put("url", redirect).toString())
                        }
                        "oauth" -> signInPage(asked.optString("url"), incognito = isIncognito(id))
                        "favorites" -> asked.optJSONArray("favorites")?.let { favorites.replace(Favorites.parse(it)) }
                            ?: reply.postMessage(JSONObject().put("type", "favorites").put("favorites", JSONArray(Favorites.json(favorites.list.value))).toString())
                        "account" -> accountReported(RenAccount.parse(asked))
                    }
                }
            }
        }
        webView.setDownloadListener { downloadUrl, userAgent, disposition, mime, bytes ->
            val name = URLUtil.guessFileName(downloadUrl, disposition, mime)
            downloadRequest.value = DownloadRequest(
                url = downloadUrl,
                name = name,
                host = Uri.parse(downloadUrl).host.orEmpty(),
                mime = mime,
                bytes = bytes,
                userAgent = userAgent,
            )
            // A download leaves the page where it was.
            update(id) { it.copy(loading = false) }
        }
        return webView
    }

    private fun handleLongPress(webView: WebView): Boolean {
        val hit = webView.hitTestResult
        val hitType = hit.type
        if (
            hitType != WebView.HitTestResult.SRC_ANCHOR_TYPE &&
            hitType != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE &&
            hitType != WebView.HitTestResult.IMAGE_TYPE
        ) {
            return false
        }
        val extra = hit.extra?.takeIf(String::isNotEmpty)
        val initialLink = if (hitType == WebView.HitTestResult.SRC_ANCHOR_TYPE) extra else null
        val initialImage = if (
            hitType == WebView.HitTestResult.IMAGE_TYPE ||
            hitType == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        ) {
            extra
        } else {
            null
        }
        if (initialLink != null || initialImage != null) {
            _contextMenu.value = ContextMenuTarget(linkUrl = initialLink, linkText = null, imageUrl = initialImage)
        }
        val handler = Handler(Looper.getMainLooper()) { msg ->
            val data = msg.data
            val href = data.getString("url")?.takeIf(String::isNotEmpty) ?: initialLink
            val linkTitle = data.getString("title")?.trim()?.takeIf(String::isNotEmpty)
            val src = data.getString("src")?.takeIf(String::isNotEmpty) ?: initialImage
            if (href != null || src != null) {
                _contextMenu.value = ContextMenuTarget(linkUrl = href, linkText = linkTitle, imageUrl = src)
            }
            true
        }
        webView.requestFocusNodeHref(handler.obtainMessage())
        return true
    }

    private fun evictExcessWebViews() {
        if (views.size <= MAX_LIVE_WEBVIEWS) return
        val activeId = _state.value.active
        val candidates = views.keys.filter { candidateId -> candidateId != activeId }
        var excess = views.size - MAX_LIVE_WEBVIEWS
        for (victimId in candidates) {
            if (excess <= 0) break
            val victim = views.remove(victimId) ?: continue
            saveTabStateBundle(victimId, victim)
            defaultUserAgents.remove(victimId)
            destroy(victim)
            excess--
        }
    }

    private fun tabStateFile(id: String): File {
        val safeId = id.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }.ifEmpty { "tab" }
        return File(File(context.filesDir, TAB_STATES_DIR), "$safeId.bundle")
    }

    private fun saveTabStateBundle(id: String, webView: WebView) {
        if (isIncognito(id)) return
        val _ = runCatching {
            val bundle = Bundle()
            webView.saveState(bundle)
            if (bundle.isEmpty) return
            val file = tabStateFile(id)
            file.parentFile?.mkdirs()
            val parcel = Parcel.obtain()
            try {
                bundle.writeToParcel(parcel, 0)
                file.writeBytes(parcel.marshall())
            } finally {
                parcel.recycle()
            }
        }
    }

    private fun restoreTabStateBundle(id: String, webView: WebView): Boolean {
        if (isIncognito(id)) return false
        val file = tabStateFile(id)
        if (!file.exists()) return false
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return false
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val bundle = Bundle()
                bundle.readFromParcel(parcel)
                !bundle.isEmpty && webView.restoreState(bundle) != null
            } finally {
                parcel.recycle()
            }
        }.getOrDefault(false)
    }

    private fun deleteTabStateBundle(id: String) {
        val _ = runCatching { tabStateFile(id).delete() }
    }

    private fun saveBytesToDownloads(fileName: String, mime: String?, bytes: ByteArray) {
        val cleanName = File(fileName).name.ifEmpty { "download.bin" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        if (itemUri != null) {
            val written = runCatching {
                resolver.openOutputStream(itemUri)?.use { out -> out.write(bytes) } != null
            }.getOrDefault(false)
            if (written) {
                val doneValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                val _ = runCatching { resolver.update(itemUri, doneValues, null, null) }
                return
            }
            val _ = runCatching { resolver.delete(itemUri, null, null) }
        }
        val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        fallbackDir.mkdirs()
        val _ = runCatching { File(fallbackDir, cleanName).writeBytes(bytes) }
    }

    private fun downloadBlobFromActiveTab(blobUrl: String, fileName: String) {
        val activeId = _state.value.active ?: return
        val activeView = views[activeId] ?: return
        val quotedUrl = JSONObject.quote(blobUrl)
        val script = """
            (async () => {
              try {
                const res = await fetch($quotedUrl);
                const blob = await res.blob();
                return await new Promise((resolve) => {
                  const reader = new FileReader();
                  reader.onloadend = () => resolve(typeof reader.result === 'string' ? reader.result : '');
                  reader.onerror = () => resolve('');
                  reader.readAsDataURL(blob);
                });
              } catch (_) {
                return '';
              }
            })()
        """.trimIndent()
        activeView.evaluateJavascript(script) { raw ->
            val unquoted = raw?.trim()?.removeSurrounding("\"")?.replace("\\/", "/").orEmpty()
            val decoded = parseDataUri(unquoted)
            if (decoded != null) {
                saveBytesToDownloads(fileName, decoded.mime, decoded.bytes)
            }
        }
    }

    private fun handleIntentNavigation(tabId: String, rawUri: String, hasGesture: Boolean) {
        val parsed = try {
            Intent.parseUri(rawUri, Intent.URI_INTENT_SCHEME)
        } catch (_: URISyntaxException) {
            return
        }
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        parsed.selector = null
        parsed.flags = parsed.flags and
            Intent.FLAG_GRANT_READ_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION.inv()
        val fallbackUrl = parsed.getStringExtra("browser_fallback_url")
            ?.trim()
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        if (hasGesture) {
            try {
                context.startActivity(parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Target app is absent on this device; fall back to web URL or Play Store listing.
            }
        }
        if (fallbackUrl != null) {
            load(tabId, fallbackUrl)
            return
        }
        val targetPackage = parsed.`package`?.trim()?.takeIf(String::isNotEmpty)
        if (hasGesture && targetPackage != null) {
            external(Uri.parse("market://details?id=${Uri.encode(targetPackage)}"))
        }
    }

    private fun enqueueJsDialog(tabId: String, prompt: JsDialogPrompt, callback: PendingJsCallback) {
        pendingJsDialog?.callback?.cancel()
        pendingJsDialog = PendingJsDialog(tabId = tabId, prompt = prompt, callback = callback)
        _jsDialog.value = prompt
    }

    private inner class Pages(private val tabId: String) : WebViewClientCompat() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.url.host == ORIGIN_HOST) return loader.shouldInterceptRequest(request.url)
            if (!request.isForMainFrame) {
                val pageUrl = _state.value.tabs.firstOrNull { it.id == tabId }?.url
                val shieldsOn = browserSettings?.shieldsEnabled ?: true
                val disabledHosts = browserSettings?.shieldsDisabledHosts ?: emptySet()
                if (ContentBlocker.shouldBlockRequest(request.url, pageUrl, shieldsOn, disabledHosts)) {
                    incrementBlockedCount(tabId)
                    return ContentBlocker.emptyResponse()
                }
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val requestUrl = request.url
            return when (requestUrl.scheme?.lowercase(Locale.ROOT)) {
                "http", "https" -> {
                    if (request.isForMainFrame) {
                        val raw = requestUrl.toString()
                        val stripped = if (browserSettings?.stripTrackingParams != false) {
                            ContentBlocker.stripTrackingParameters(raw)
                        } else {
                            raw
                        }
                        if (stripped != raw) {
                            load(tabId, stripped)
                            return true
                        }
                        update(tabId) { it.copy(loading = true, progress = 0, error = null, sslWarning = null) }
                    }
                    false
                }
                "ren" -> {
                    if (request.isForMainFrame) load(tabId, requestUrl.toString())
                    true
                }
                "intent" -> {
                    if (request.isForMainFrame) {
                        handleIntentNavigation(tabId, requestUrl.toString(), request.hasGesture())
                    }
                    true
                }
                // Another app's address (tel:, mailto:, a store page): only when the user tapped it, and only by asking the system.
                else -> {
                    if (request.isForMainFrame && request.hasGesture()) external(requestUrl)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // Ren's pages send the token cookie to the backend from another site; a website gets no third-party cookies.
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, url.startsWith(PAGES))
            if (overHome.contains(tabId)) return
            update(tabId) {
                it.copy(
                    url = display(url),
                    loading = true,
                    progress = 0,
                    favicon = null,
                    error = null,
                )
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = sync(tabId, view)

        override fun onPageFinished(view: WebView, url: String) {
            httpsUpgradedUrls.remove(tabId)
            sync(tabId, view)
            update(tabId) { it.copy(loading = false, progress = 100) }
            val currentTab = _state.value.tabs.firstOrNull { it.id == tabId }
            if (
                !overHome.contains(tabId) &&
                currentTab != null &&
                !currentTab.incognito &&
                currentTab.error == null &&
                currentTab.sslWarning == null
            ) {
                val pageTitle = view.title.orEmpty()
                history.record(url, pageTitle)
                visited(url, pageTitle)
                saveTabStateBundle(tabId, view)
            }
            evictExcessWebViews()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
            if (!request.isForMainFrame) return
            val failingUrl = request.url.toString()
            val fallbackHttp = httpsUpgradedUrls.remove(tabId)
            if (fallbackHttp != null) {
                val host = Uri.parse(fallbackHttp).host?.let(ContentBlocker::normalizeHost).orEmpty()
                if (host.isNotEmpty()) httpAllowedHosts.add(host)
                load(tabId, fallbackHttp)
                return
            }
            val code = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) {
                error.errorCode
            } else {
                ERROR_UNKNOWN
            }
            val description = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) {
                error.description.toString()
            } else {
                "Page failed to load"
            }
            update(tabId) {
                it.copy(
                    url = display(failingUrl).ifEmpty { it.url },
                    loading = false,
                    progress = 0,
                    error = PageError(code = code, description = description, failingUrl = failingUrl),
                )
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val errorUrl = error.url.orEmpty()
            val errorHost = Uri.parse(errorUrl).host?.let(ContentBlocker::normalizeHost).orEmpty()
            val currentTab = _state.value.tabs.firstOrNull { it.id == tabId }
            val currentHost = currentTab?.url?.let { Uri.parse(it).host }?.let(ContentBlocker::normalizeHost).orEmpty()
            // Third-party subresource certificate errors after main page load are silently rejected without replacing the page.
            if (currentTab != null && !currentTab.loading && currentHost.isNotEmpty() && errorHost.isNotEmpty() && errorHost != currentHost) {
                handler.cancel()
                return
            }
            pendingSslHandlers.put(tabId, handler)?.cancel()
            val shownUrl = errorUrl.ifEmpty { currentTab?.url.orEmpty() }
            update(tabId) {
                it.copy(
                    url = shownUrl.ifEmpty { it.url },
                    loading = false,
                    progress = 0,
                    sslWarning = SslWarning(
                        url = shownUrl,
                        host = errorHost.ifEmpty { currentHost },
                        primaryError = error.primaryError,
                    ),
                )
            }
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String?,
            realm: String?,
        ) {
            pendingHttpAuth?.handler?.cancel()
            val prompt = HttpAuthPrompt(
                id = UUID.randomUUID().toString(),
                host = host.orEmpty(),
                realm = realm.orEmpty(),
            )
            pendingHttpAuth = PendingHttpAuth(tabId = tabId, prompt = prompt, handler = handler)
            _httpAuth.value = prompt
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponseCompat,
        ) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_SHOW_INTERSTITIAL)) {
                callback.showInterstitial(true)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            // The page's renderer died: this WebView can't be used again, so the tab gets a fresh one on its address.
            val url = _state.value.tabs.firstOrNull { it.id == tabId }?.url
            views.remove(tabId)?.let(::destroy)
            defaultUserAgents.remove(tabId)
            if (url != null && url != Addresses.HOME) load(tabId, url)
            return true
        }
    }

    private inner class Chrome(private val tabId: String) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) =
            update(tabId) { it.copy(progress = newProgress, loading = it.loading && newProgress < 100) }

        override fun onReceivedTitle(view: WebView, title: String?) = sync(tabId, view)

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) = update(tabId) { it.copy(favicon = icon) }

        /** A link that opens a new window opens a new tab, when the user tapped it. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj
            if (transport !is WebView.WebViewTransport) return false
            val parentIncognito = isIncognito(tabId)
            val newTabId = UUID.randomUUID().toString()
            change {
                it.copy(
                    tabs = it.tabs + BrowserTab(id = newTabId, url = "about:blank", loading = true, incognito = parentIncognito),
                    active = newTabId,
                )
            }
            transport.webView = view(newTabId)
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) = close(tabId)

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = fileChooser?.invoke(filePathCallback, fileChooserParams) ?: false

        override fun onPermissionRequest(request: PermissionRequest) {
            sitePermissions.onWebPermissionRequest(request, incognito = isIncognito(tabId), scope = mainScope)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            sitePermissions.onGeolocationPrompt(origin, callback, incognito = isIncognito(tabId), scope = mainScope)
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            enqueueJsDialog(
                tabId = tabId,
                prompt = JsDialogPrompt(
                    id = UUID.randomUUID().toString(),
                    url = url.orEmpty(),
                    kind = JsDialogKind.Alert,
                    message = message.orEmpty(),
                ),
                callback = PendingJsCallback.Standard(result),
            )
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            enqueueJsDialog(
                tabId = tabId,
                prompt = JsDialogPrompt(
                    id = UUID.randomUUID().toString(),
                    url = url.orEmpty(),
                    kind = JsDialogKind.Confirm,
                    message = message.orEmpty(),
                ),
                callback = PendingJsCallback.Standard(result),
            )
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult,
        ): Boolean {
            enqueueJsDialog(
                tabId = tabId,
                prompt = JsDialogPrompt(
                    id = UUID.randomUUID().toString(),
                    url = url.orEmpty(),
                    kind = JsDialogKind.Prompt,
                    message = message.orEmpty(),
                    defaultValue = defaultValue.orEmpty(),
                ),
                callback = PendingJsCallback.Prompt(result),
            )
            return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            enqueueJsDialog(
                tabId = tabId,
                prompt = JsDialogPrompt(
                    id = UUID.randomUUID().toString(),
                    url = url.orEmpty(),
                    kind = JsDialogKind.BeforeUnload,
                    message = message.orEmpty(),
                ),
                callback = PendingJsCallback.Standard(result),
            )
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            leaveFullscreen = callback
            fullscreen.value = view
        }

        override fun onHideCustomView() {
            leaveFullscreen = null
            fullscreen.value = null
        }
    }

    private fun external(url: Uri) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            // No app on the phone opens this kind of address; the page stays where it is.
        }
    }

    private data class PendingHttpAuth(
        val tabId: String,
        val prompt: HttpAuthPrompt,
        val handler: HttpAuthHandler,
    )

    private sealed interface PendingJsCallback {
        fun confirm(input: String?)
        fun cancel()

        class Standard(private val result: JsResult) : PendingJsCallback {
            override fun confirm(input: String?) = result.confirm()
            override fun cancel() = result.cancel()
        }

        class Prompt(private val result: JsPromptResult) : PendingJsCallback {
            override fun confirm(input: String?) = result.confirm(input.orEmpty())
            override fun cancel() = result.cancel()
        }
    }

    private data class PendingJsDialog(
        val tabId: String,
        val prompt: JsDialogPrompt,
        val callback: PendingJsCallback,
    )

    private class ParsedDataUri(
        val mime: String,
        val bytes: ByteArray,
    )

    public companion object {
        internal const val PRIVATE_PROFILE: String = "ren_private"
        internal const val MAX_LIVE_WEBVIEWS: Int = 6
        internal const val DESKTOP_USER_AGENT: String =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        private const val ORIGIN_HOST: String = "appassets.androidplatform.net"
        private const val TAB_STATES_DIR: String = "tab_states"
        private const val SHORT_LABEL_MAX: Int = 24
        private const val LONG_LABEL_MAX: Int = 64

        /** How long a tap's finger stays down. */
        private const val TAP_MS: Long = 60L
        private const val PAGES: String = "https://$ORIGIN_HOST/assets/ren/web"

        /** The bundled page behind one of Ren's `ren://` addresses (a destination may name a part, `ren://discover#signin`); null for a web address. */
        internal fun internal(url: String): String? = when {
            Addresses.searchQuery(url) != null -> "$PAGES/search/index.html?${Uri.parse(url).encodedQuery.orEmpty()}"
            else -> Destination.of(url.substringBefore('#'))?.let { destination ->
                "$PAGES/destinations/index.html?tab=${destination.id}" + url.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
            }
        }

        /** The address the bar shows for a loaded URL: Ren's pages by their `ren://` names. */
        internal fun display(url: String): String = when {
            url.startsWith("$PAGES/search/index.html") -> "ren://search?${Uri.parse(url).encodedQuery.orEmpty()}"
            url.startsWith("$PAGES/destinations/index.html") ->
                Destination.entries.firstOrNull { it.id == Uri.parse(url).getQueryParameter("tab") }?.address ?: url
            else -> url
        }

        private fun parseDataUri(raw: String): ParsedDataUri? {
            if (!raw.startsWith("data:", ignoreCase = true)) return null
            val comma = raw.indexOf(',')
            if (comma < 5) return null
            val meta = raw.substring(5, comma)
            val payload = raw.substring(comma + 1)
            val parts = meta.split(';')
            val isBase64 = parts.any { token -> token.equals("base64", ignoreCase = true) }
            val mime = parts.firstOrNull()?.trim()?.takeIf { '/' in it } ?: "application/octet-stream"
            val bytes = if (isBase64) {
                runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }
            return ParsedDataUri(mime = mime, bytes = bytes)
        }
    }
}

/** Where the open tabs are kept between launches: their addresses and titles, and which was active. */
public class BrowserStore(private val read: () -> String?, private val write: (String) -> Unit) {
    public fun load(): BrowserState {
        val json = runCatching { JSONObject(read() ?: return BrowserState()) }.getOrNull() ?: return BrowserState()
        val list = json.optJSONArray("tabs") ?: return BrowserState()
        val tabs = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { tab ->
            val id = tab.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            // A saved page comes back with its address and title, and loads when its tab is first shown.
            BrowserTab(
                id = id,
                url = tab.optString("url").ifEmpty { Addresses.HOME },
                title = tab.optString("title"),
                canGoBack = tab.optString("url") != Addresses.HOME,
            )
        }
        return BrowserState(tabs, json.optString("active").takeIf { active -> tabs.any { it.id == active } } ?: tabs.firstOrNull()?.id)
    }

    public fun save(state: BrowserState) {
        val normalTabs = state.tabs.filterNot { it.incognito }
        val tabs = JSONArray()
        normalTabs.forEach { tab ->
            tabs.put(JSONObject().put("id", tab.id).put("url", tab.url).put("title", tab.title))
        }
        val activeId = state.active?.takeIf { active -> normalTabs.any { it.id == active } }
            ?: normalTabs.lastOrNull()?.id.orEmpty()
        write(JSONObject().put("tabs", tabs).put("active", activeId).toString())
    }
}
