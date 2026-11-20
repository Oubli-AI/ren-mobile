package com.hiron.ren.browser

import android.Manifest
import android.app.Application
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Environment
import android.webkit.JsPromptResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import com.hiron.ren.core.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJsPromptResult
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowWebView
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [BrowserEngineTest.ConfirmableShadowJsPromptResult::class])
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserEngineTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("ren", Application.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "tab_states").deleteRecursively()
        shadowOf(app).checkActivities(false)
        shadowOf(app).grantPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @After
    fun tearDown() {
        shadowOf(app).checkActivities(false)
        Dispatchers.resetMain()
    }

    @Test
    fun webTabsSendABrowserUserAgentNotAnAppWebViews() {
        val webView = "Mozilla/5.0 (Linux; Android 17; SM-S921U1 Build/CP2A.260605.016; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/142.0.7444.0 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 17; SM-S921U1 Build/CP2A.260605.016) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.7444.0 Mobile Safari/537.36",
            Browser.browserUserAgent(webView),
        )
    }

    @Test
    fun incognitoTabsExcludeStoreHistoryAndVisitedAndCloseCleanly() {
        var storedTabsJson: String? = null
        var storedHistoryJson: String? = null
        val visitedUrls = ArrayList<String>()

        val store = BrowserStore({ storedTabsJson }, { storedTabsJson = it })
        val favorites = Favorites({ null }, {})
        val history = BrowserHistory({ storedHistoryJson }, { storedHistoryJson = it })
        val permissions = SitePermissionsManager(app)
        val settings = Settings(app)
        val browser = Browser(
            context = app,
            store = store,
            favorites = favorites,
            token = { "tok" },
            history = history,
            sitePermissions = permissions,
            browserSettings = settings,
        )
        browser.visited = { url, _ -> visitedUrls.add(url) }

        val normalId = checkNotNull(browser.state.value.active)
        browser.load(normalId, "https://example.com/normal")
        val normalView = checkNotNull(browser.shown(normalId))
        webViewClientOf(normalView).onPageFinished(normalView, "https://example.com/normal")

        assertEquals(listOf("https://example.com/normal"), history.entries.value.map { it.url })
        assertEquals(listOf("https://example.com/normal"), visitedUrls)

        val privOne = browser.open("https://secret.example.com/one", select = true, incognito = true)
        val privTwo = browser.open("https://secret.example.com/two", select = true, incognito = true)
        assertTrue(browser.state.value.tabs.first { it.id == privOne }.incognito)
        assertTrue(browser.state.value.tabs.first { it.id == privTwo }.incognito)

        val privViewOne = checkNotNull(browser.shown(privOne))
        val privViewTwo = checkNotNull(browser.shown(privTwo))
        webViewClientOf(privViewOne).onPageFinished(privViewOne, "https://secret.example.com/one")
        webViewClientOf(privViewTwo).onPageFinished(privViewTwo, "https://secret.example.com/two")

        // Private tabs are never persisted to BrowserStore, BrowserHistory, or the Context Agent's visited stream.
        val reloaded = store.load()
        assertEquals(listOf(normalId), reloaded.tabs.map { it.id })
        assertEquals(normalId, reloaded.active)
        assertFalse(storedTabsJson.orEmpty().contains("secret.example.com"))
        assertFalse(storedTabsJson.orEmpty().contains(privOne))
        assertEquals(listOf("https://example.com/normal"), history.entries.value.map { it.url })
        assertEquals(listOf("https://example.com/normal"), visitedUrls)

        // Closing one private tab destroys its WebView and never pushes it onto closedTabs.
        browser.close(privOne)
        assertTrue(shadowOf(privViewOne).wasDestroyCalled())
        assertTrue(history.closedTabs.value.isEmpty())

        // Closing all remaining private tabs destroys WebViews before private profile cleanup and returns to normal tab.
        browser.closeAll(incognitoOnly = true)
        assertTrue(shadowOf(privViewTwo).wasDestroyCalled())
        assertTrue(history.closedTabs.value.isEmpty())
        assertEquals(listOf(normalId), browser.state.value.tabs.map { it.id })
        assertEquals(normalId, browser.state.value.active)

        // Closing every tab leaves a fresh normal Home tab.
        browser.closeAll(incognitoOnly = null)
        assertEquals(1, browser.state.value.tabs.size)
        val freshTab = checkNotNull(browser.state.value.current)
        assertTrue(freshTab.home)
        assertFalse(freshTab.incognito)
    }

    @Test
    fun mainFrameReceivedErrorPopulatesPageErrorAndRetryReloads() {
        val browser = createBrowser()
        val tabId = checkNotNull(browser.state.value.active)
        val failingUrl = "https://unreachable.example.com/docs"
        val visitedUrls = ArrayList<String>()
        browser.visited = { url, _ -> visitedUrls.add(url) }

        browser.load(tabId, failingUrl)
        val webView = checkNotNull(browser.shown(tabId))
        val client = webViewClientOf(webView)

        // Subframe errors never replace the main page with an error card.
        client.onReceivedError(
            webView,
            fakeRequest("https://cdn.example.com/sub.js", isForMainFrame = false, hasGesture = false),
            FakeWebResourceError(-6, "net::ERR_CONNECTION_REFUSED"),
        )
        assertNull(browser.state.value.current?.error)

        // Main-frame error populates PageError and halts loading.
        client.onReceivedError(
            webView,
            fakeRequest(failingUrl, isForMainFrame = true, hasGesture = false),
            FakeWebResourceError(-2, "net::ERR_NAME_NOT_RESOLVED"),
        )
        val erroredTab = checkNotNull(browser.state.value.current)
        assertFalse(erroredTab.loading)
        assertEquals(
            PageError(code = -2, description = "net::ERR_NAME_NOT_RESOLVED", failingUrl = failingUrl),
            erroredTab.error,
        )

        // Trailing onPageFinished after an error must not record broken pages in history or visited.
        client.onPageFinished(webView, failingUrl)
        assertTrue(browser.history.entries.value.isEmpty())
        assertTrue(visitedUrls.isEmpty())

        // retryError clears PageError and reloads the failing URL.
        browser.retryError(tabId)
        val retriedTab = checkNotNull(browser.state.value.current)
        assertNull(retriedTab.error)
        assertTrue(retriedTab.loading)
        assertEquals(failingUrl, shadowOf(webView).lastLoadedUrl)
    }

    @Test
    fun intentUriNavigatesToFallbackUrlWhenTargetAppIsAbsent() {
        shadowOf(app).checkActivities(true)
        val settings = Settings(app)
        val browser = createBrowser(settings = settings)
        val tabId = checkNotNull(browser.state.value.active)
        browser.load(tabId, "https://shop.example.com")
        val webView = checkNotNull(browser.shown(tabId))
        val client = webViewClientOf(webView)

        val intentUrl = "intent://product/42#Intent;scheme=missingapp;package=com.missing.app;" +
            "S.browser_fallback_url=https%3A%2F%2Fshop.example.com%2Fweb-item%3Futm_source%3Dbanner%26id%3D42;end"
        val handled = client.shouldOverrideUrlLoading(
            webView,
            fakeRequest(intentUrl, isForMainFrame = true, hasGesture = true),
        )
        assertTrue(handled)
        assertEquals("https://shop.example.com/web-item?id=42", browser.state.value.current?.url)
        assertEquals("https://shop.example.com/web-item?id=42", shadowOf(webView).lastLoadedUrl)

        // Non-http(s) fallback schemes in intent:// URIs are ignored safely.
        val unsafeIntent = "intent://x#Intent;scheme=missingapp;package=com.missing.app;" +
            "S.browser_fallback_url=javascript%3Aalert(1);end"
        assertTrue(
            client.shouldOverrideUrlLoading(
                webView,
                fakeRequest(unsafeIntent, isForMainFrame = true, hasGesture = true),
            ),
        )
        assertEquals("https://shop.example.com/web-item?id=42", browser.state.value.current?.url)
    }

    @Test
    fun reopenClosedTabToggleDesktopSiteAndFindInPageWorkCorrectly() {
        val browser = createBrowser()
        val firstId = checkNotNull(browser.state.value.active)
        browser.load(firstId, "https://one.example.com")
        val secondId = browser.open("https://two.example.com", select = true, incognito = false)
        val homeId = browser.open(Addresses.HOME, select = true, incognito = false)

        browser.close(homeId)
        browser.close(firstId)
        browser.close(secondId)

        // LIFO reopen restores second, then first, and skips Home.
        val reopenedSecond = checkNotNull(browser.reopenClosedTab())
        assertEquals("https://two.example.com", browser.state.value.tabs.first { it.id == reopenedSecond }.url)
        assertEquals(reopenedSecond, browser.state.value.active)

        val reopenedFirst = checkNotNull(browser.reopenClosedTab())
        assertEquals("https://one.example.com", browser.state.value.tabs.first { it.id == reopenedFirst }.url)
        assertNull(browser.reopenClosedTab())

        // Desktop site toggle switches userAgentString and reloads.
        val activeView = checkNotNull(browser.shown(reopenedFirst))
        val mobileUa = activeView.settings.userAgentString
        val reloadsBefore = shadowOf(activeView).reloadInvocations

        browser.toggleDesktopSite(reopenedFirst)
        assertTrue(browser.state.value.tabs.first { it.id == reopenedFirst }.desktopSite)
        assertEquals(Browser.DESKTOP_USER_AGENT, activeView.settings.userAgentString)
        assertEquals(reloadsBefore + 1, shadowOf(activeView).reloadInvocations)

        browser.toggleDesktopSite(reopenedFirst)
        assertFalse(browser.state.value.tabs.first { it.id == reopenedFirst }.desktopSite)
        assertEquals(mobileUa, activeView.settings.userAgentString)
        assertEquals(reloadsBefore + 2, shadowOf(activeView).reloadInvocations)

        // Find in page activates, tracks query, and resets on clearFind.
        browser.startFind()
        assertTrue(browser.findState.value.active)
        browser.findInPage("kotlin")
        assertEquals("kotlin", browser.findState.value.query)
        browser.findNext(forward = true)
        browser.findNext(forward = false)
        browser.clearFind()
        assertEquals(FindState(), browser.findState.value)
    }

    @Test
    fun clearBrowsingDataWipesHistoryCacheCookiesAndSavedSitePermissions() {
        val browser = createBrowser()
        val tabId = checkNotNull(browser.state.value.active)
        browser.load(tabId, "https://docs.example.com")
        val webView = checkNotNull(browser.shown(tabId))

        browser.history.record("https://docs.example.com", "Docs", now = 1_000L)
        browser.history.pushClosedTab("https://old.example.com", "Old")
        assertEquals(1, browser.history.entries.value.size)
        assertEquals(1, browser.history.closedTabs.value.size)

        // Save a site permission decision on disk first.
        val permScope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val permRequest = object : PermissionRequest() {
            override fun getOrigin(): Uri = Uri.parse("https://docs.example.com")
            override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE)
            override fun grant(resources: Array<String>) = Unit
            override fun deny() = Unit
        }
        browser.sitePermissions.onWebPermissionRequest(permRequest, incognito = false, scope = permScope)
        runBlocking { permScope.coroutineContext[Job]?.children?.toList()?.joinAll() }
        val prompt = checkNotNull(browser.sitePermissions.prompt.value)
        browser.sitePermissions.respond(prompt.id, allow = true, remember = true, scope = permScope)
        runBlocking {
            permScope.coroutineContext[Job]?.children?.toList()?.joinAll()
            assertEquals(1, browser.sitePermissions.allSaved().size)
        }

        val completed = CompletableDeferred<Boolean>()
        browser.clearBrowsingData(
            ClearDataOptions(
                history = true,
                cookiesAndStorage = true,
                cache = true,
                sitePermissions = true,
            ),
        ) {
            completed.complete(true)
        }
        runBlocking {
            ShadowLooper.idleMainLooper()
            assertTrue(completed.await())
            assertTrue(browser.sitePermissions.allSaved().isEmpty())
        }
        assertTrue(browser.history.entries.value.isEmpty())
        assertTrue(browser.history.closedTabs.value.isEmpty())
        assertTrue(shadowOf(webView).wasClearHistoryCalled())
        assertTrue(shadowOf(webView).wasClearCacheCalled())
    }

    @Test
    fun shieldsHttpsFirstSslDialogsContextMenuEvictionAndDataDownloadsWork() {
        val settings = Settings(app)
        settings.httpsFirst = true
        val browser = createBrowser(settings = settings)
        val tabId = checkNotNull(browser.state.value.active)

        // HTTPS-First upgrades cleartext http:// navigations and falls back on main-frame error.
        browser.load(tabId, "http://legacy.example.com/welcome")
        assertEquals("https://legacy.example.com/welcome", browser.state.value.current?.url)
        val webView = checkNotNull(browser.shown(tabId))
        val client = webViewClientOf(webView)
        client.onReceivedError(
            webView,
            fakeRequest("https://legacy.example.com/welcome", isForMainFrame = true, hasGesture = false),
            FakeWebResourceError(-6, "net::ERR_CONNECTION_REFUSED"),
        )
        assertEquals("http://legacy.example.com/welcome", browser.state.value.current?.url)

        // Third-party tracker subresource is blocked and increments tab.blockedCount.
        val blocked = client.shouldInterceptRequest(
            webView,
            fakeRequest("https://securepubads.g.doubleclick.net/tag/js/gpt.js", isForMainFrame = false, hasGesture = false),
        )
        assertNotNull(blocked)
        assertEquals(204, blocked?.statusCode)
        assertEquals(1, browser.state.value.current?.blockedCount)

        // SSL warning holds handler until user proceeds or goes back.
        val sslHandlerOne = Shadow.newInstanceOf(SslErrorHandler::class.java)
        val sslError = SslError(
            SslError.SSL_EXPIRED,
            Shadow.newInstanceOf(SslCertificate::class.java),
            "https://badssl.example.com/",
        )
        client.onReceivedSslError(webView, sslHandlerOne, sslError)
        assertEquals("badssl.example.com", browser.state.value.current?.sslWarning?.host)
        browser.proceedPastSslWarning(tabId)
        assertNull(browser.state.value.current?.sslWarning)
        assertTrue(shadowOf(sslHandlerOne).wasProceedCalled())

        val sslHandlerTwo = Shadow.newInstanceOf(SslErrorHandler::class.java)
        client.onReceivedSslError(webView, sslHandlerTwo, sslError)
        assertNotNull(browser.state.value.current?.sslWarning)
        browser.goBackFromSslWarning(tabId)
        assertNull(browser.state.value.current?.sslWarning)
        assertTrue(shadowOf(sslHandlerTwo).wasCancelCalled())

        // JS prompt supersedes stale dialogs so WebView never hangs.
        val chrome = checkNotNull(shadowOf(webView).webChromeClient)
        val firstJs = ShadowJsPromptResult.newInstance()
        val secondJs = ShadowJsPromptResult.newInstance()
        assertTrue(chrome.onJsPrompt(webView, "https://example.com", "First?", "a", firstJs))
        assertTrue(chrome.onJsPrompt(webView, "https://example.com", "Second?", "b", secondJs))
        assertTrue(shadowOf(firstJs).wasCancelled())
        val activePrompt = checkNotNull(browser.jsDialog.value)
        assertEquals("Second?", activePrompt.message)
        browser.answerJsDialog(activePrompt.id, confirmed = true, promptInput = "typed")
        assertNull(browser.jsDialog.value)
        val secondShadow: ConfirmableShadowJsPromptResult = Shadow.extract(secondJs)
        assertEquals("typed", secondShadow.confirmedValue)

        // Long-press hit test populates contextMenu and dismissContextMenu clears it.
        shadowOf(webView).setHitTestResult(
            ShadowWebView.createHitTestResult(WebView.HitTestResult.SRC_ANCHOR_TYPE, "https://example.com/article"),
        )
        val longClickListener = checkNotNull(shadowOf(webView).onLongClickListener)
        assertTrue(longClickListener.onLongClick(webView))
        assertEquals("https://example.com/article", browser.contextMenu.value?.linkUrl)
        browser.dismissContextMenu()
        assertNull(browser.contextMenu.value)

        // data: URI download decodes bytes without invoking DownloadManager on a non-HTTP URI.
        browser.saveUrl("data:text/plain;base64,aGVsbG8td29ybGQ=", "hello.txt")
        assertEquals("hello.txt", browser.downloadRequest.value?.name)
        browser.download()
        assertNull(browser.downloadRequest.value)
        val fallbackDir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "downloads")
        val savedFile = File(fallbackDir, "hello.txt")
        if (savedFile.exists()) {
            assertEquals("hello-world", savedFile.readText(Charsets.UTF_8))
        }

        // Opening > MAX_LIVE_WEBVIEWS background tabs evicts oldest non-active WebViews cleanly.
        val openedViews = ArrayList<WebView>()
        repeat(Browser.MAX_LIVE_WEBVIEWS + 2) { idx ->
            val bgId = browser.open("https://tab-$idx.example.com", select = true, incognito = false)
            openedViews.add(checkNotNull(browser.shown(bgId)))
        }
        assertTrue(browser.liveViewCount() <= Browser.MAX_LIVE_WEBVIEWS)
        assertTrue(shadowOf(openedViews.first()).wasDestroyCalled())
    }

    @Test
    fun settingsPersistAllBrowserAndPrivacyPreferences() {
        val writer = Settings(app)
        assertEquals(DnsMode.Default, writer.dnsMode)
        assertEquals("", writer.customDohUrl)
        assertTrue(writer.shieldsEnabled)
        assertTrue(writer.stripTrackingParams)
        assertTrue(writer.shieldsDisabledHosts.isEmpty())
        assertEquals(SearchEngine.Default, writer.searchEngine)
        assertTrue(writer.httpsFirst)
        assertFalse(writer.darkWebPages)
        assertFalse(writer.backgroundMedia)
        assertNull(writer.browserHistory)

        writer.dnsMode = DnsMode.Custom
        writer.customDohUrl = "  https://dns.quad9.net/dns-query  "
        writer.shieldsEnabled = true
        writer.stripTrackingParams = false
        writer.setSiteShields("  Example.COM. ", enabled = false)
        writer.setSiteShields("News.Example.org", enabled = false)
        writer.searchEngine = SearchEngine.Brave
        writer.httpsFirst = false
        writer.darkWebPages = true
        writer.backgroundMedia = true
        writer.browserHistory = """[{"id":"1","url":"https://example.com","title":"Example","host":"example.com","visitedAt":1}]"""

        val reader = Settings(app)
        assertEquals(DnsMode.Custom, reader.dnsMode)
        assertEquals("https://dns.quad9.net/dns-query", reader.customDohUrl)
        assertTrue(reader.shieldsEnabled)
        assertFalse(reader.stripTrackingParams)
        assertEquals(setOf("example.com", "news.example.org"), reader.shieldsDisabledHosts)
        assertFalse(reader.isShieldsEnabledFor("sub.example.com"))
        assertTrue(reader.isShieldsEnabledFor("other.org"))

        reader.setSiteShields("EXAMPLE.COM", enabled = true)
        assertTrue(reader.isShieldsEnabledFor("sub.example.com"))
        assertEquals(setOf("news.example.org"), Settings(app).shieldsDisabledHosts)

        assertEquals(SearchEngine.Brave, reader.searchEngine)
        assertFalse(reader.httpsFirst)
        assertTrue(reader.darkWebPages)
        assertTrue(reader.backgroundMedia)
        assertNotNull(reader.browserHistory)

        reader.shieldsEnabled = false
        assertFalse(reader.isShieldsEnabledFor("other.org"))
    }

    private fun createBrowser(settings: Settings = Settings(app)): Browser {
        var tabsJson: String? = null
        var historyJson: String? = null
        return Browser(
            context = app,
            store = BrowserStore({ tabsJson }, { tabsJson = it }),
            favorites = Favorites({ null }, {}),
            token = { "token" },
            history = BrowserHistory({ historyJson }, { historyJson = it }),
            sitePermissions = SitePermissionsManager(app),
            browserSettings = settings,
        )
    }

    private fun webViewClientOf(webView: WebView): WebViewClientCompat {
        val raw = shadowOf(webView).webViewClient
        check(raw is WebViewClientCompat) { "Expected WebViewClientCompat on browser WebView" }
        return raw
    }

    private fun fakeRequest(
        url: String,
        isForMainFrame: Boolean,
        hasGesture: Boolean,
    ): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = isForMainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = hasGesture
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private class FakeWebResourceError(
        private val code: Int,
        private val text: String,
    ) : WebResourceErrorCompat() {
        override fun getErrorCode(): Int = code
        override fun getDebugCode(): Int = code
        override fun getDescription(): CharSequence = text
    }

    @Implements(JsPromptResult::class)
    class ConfirmableShadowJsPromptResult : ShadowJsPromptResult() {
        var confirmedValue: String? = null
            private set

        @Implementation
        protected fun confirm(result: String?) {
            confirmedValue = result
        }
    }
}
