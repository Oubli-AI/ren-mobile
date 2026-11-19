package com.hiron.ren.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import com.hiron.ren.browser.Addresses
import com.hiron.ren.browser.DnsMode
import com.hiron.ren.browser.SearchEngine
import com.hiron.ren.core.ren
import com.hiron.ren.testing.shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class BrowserUiTest {
    @get:Rule
    val compose: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            RenTheme {
                Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) {
                    content()
                }
            }
        }
    }

    @Test
    fun openingPrivateTabFromMenuShowsPrivateIndicatorAndClosingReturnsToNormalTab() {
        val runtime = compose.activity.ren
        mount {
            BrowserScreen(
                runtime = runtime,
                waiting = 0,
                onAskRen = {},
                onOpen = {},
            )
        }

        assertFalse(runtime.browser.state.value.current?.incognito == true)
        compose.onAllNodesWithText("Private").assertCountEquals(0)

        compose.onNodeWithTag(MORE_TAG).performClick()
        compose.onNodeWithText("New private tab").performClick()
        compose.waitForIdle()

        assertTrue(runtime.browser.state.value.current?.incognito == true)
        compose.onNodeWithText("Private").assertIsDisplayed()
        shot(compose, compose.activity, "11-browser-private-tab")

        compose.onNodeWithTag(TABS_TAG).performClick()
        compose.onNodeWithText("Close all").performClick()
        compose.waitForIdle()

        assertFalse(runtime.browser.state.value.current?.incognito == true)
        compose.onAllNodesWithText("Private").assertCountEquals(0)
    }

    @Test
    fun pageErrorOnActiveTabRendersPageErrorCardWithRetryAndSearchWithRen() {
        val runtime = compose.activity.ren
        mount {
            BrowserScreen(
                runtime = runtime,
                waiting = 0,
                onAskRen = {},
                onOpen = {},
            )
        }

        val failingUrl = "https://offline.example.test/docs"
        val tabId = checkNotNull(runtime.browser.state.value.active)
        compose.runOnUiThread { runtime.browser.load(tabId, failingUrl) }
        compose.waitForIdle()

        val webView = checkNotNull(runtime.browser.shown(tabId))
        val client = shadowOf(webView).webViewClient
        assertTrue(client is WebViewClientCompat)
        val request = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(failingUrl)
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        val error = object : WebResourceErrorCompat() {
            override fun getErrorCode(): Int = WebViewClient.ERROR_HOST_LOOKUP
            override fun getDebugCode(): Int = 0
            override fun getDescription(): CharSequence = "net::ERR_NAME_NOT_RESOLVED"
        }
        compose.runOnUiThread {
            if (client is WebViewClientCompat) {
                client.onReceivedError(webView, request, error)
            }
        }
        compose.waitForIdle()

        assertNotNull(runtime.browser.state.value.current?.error)
        compose.onNodeWithText("Can't reach this site").assertIsDisplayed()
        compose.onNodeWithText(failingUrl).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Search with Ren").assertIsDisplayed()
        shot(compose, compose.activity, "12-browser-error-card")

        compose.onNodeWithText("Search with Ren").performClick()
        compose.waitForIdle()

        assertNull(runtime.browser.state.value.current?.error)
        assertEquals(
            Addresses.searchUrl(failingUrl, runtime.settings.searchLevel),
            runtime.browser.state.value.current?.url,
        )
    }

    @Test
    fun findInPageOpensFindInPageBarAndClosesCleanly() {
        val runtime = compose.activity.ren
        mount {
            BrowserScreen(
                runtime = runtime,
                waiting = 0,
                onAskRen = {},
                onOpen = {},
            )
        }

        val tabId = checkNotNull(runtime.browser.state.value.active)
        compose.runOnUiThread { runtime.browser.load(tabId, "https://example.com") }
        compose.waitForIdle()

        compose.onNodeWithTag(MORE_TAG).performClick()
        shot(compose, compose.activity, "10-browser-menu")
        compose.onNodeWithText("Find in page").performClick()
        compose.waitForIdle()

        assertTrue(runtime.browser.findState.value.active)
        compose.onNodeWithText("Find in page").assertIsDisplayed()
        compose.onNodeWithText("0/0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close find").assertIsDisplayed()
        shot(compose, compose.activity, "13-browser-find-bar")

        compose.onNodeWithContentDescription("Close find").performClick()
        compose.waitForIdle()

        assertFalse(runtime.browser.findState.value.active)
        compose.onAllNodesWithContentDescription("Close find").assertCountEquals(0)
    }

    @Test
    fun settingsScreenRendersBrowserAndPrivacyAndKeepsSingleTurnOn() {
        val runtime = compose.activity.ren
        mount { SettingsScreen(runtime) }

        compose.onAllNodesWithText("Turn on").assertCountEquals(1)
        compose.onAllNodesWithText("Open Ren's app info").assertCountEquals(1)

        compose.onNodeWithText("Browser & privacy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Secure DNS (DoH)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Custom DoH").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(DnsMode.Custom, runtime.settings.dnsMode)
        compose.onNodeWithText("https://dns.example.com/dns-query").performScrollTo().assertIsDisplayed()

        compose.onNode(isToggleable() and hasText("Block ads and trackers"))
            .performScrollTo()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        assertFalse(runtime.settings.shieldsEnabled)

        compose.onNodeWithText("DuckDuckGo").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(SearchEngine.DuckDuckGo, runtime.settings.searchEngine)

        compose.onNodeWithText("Clear browsing data…").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Clear browsing data").assertIsDisplayed()
        compose.onNodeWithText("Cookies and site data").assertIsDisplayed()
        compose.onNodeWithText("Cached files").assertIsDisplayed()
        shot(compose, compose.activity, "14-browser-clear-data")
        compose.onNodeWithText("Clear data").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("Turn on").assertCountEquals(1)
        compose.onAllNodesWithText("Open Ren's app info").assertCountEquals(1)
    }
}
