package com.hiron.ren.browser

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.util.Locale

/**
 * Custom Tabs service that warms WebView's default profile renderer and preconnects likely `http`/`https` origins on
 * the main thread when the device's WebView supports multi-profile speculative loading.
 */
internal class RenCustomTabsService : CustomTabsService() {
    @OptIn(Profile.ExperimentalWarmUpRendererProcess::class)
    override fun warmup(flags: Long): Boolean {
        Handler(Looper.getMainLooper()).post {
            if (
                WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS)
            ) {
                ProfileStore.getInstance().getProfile(Profile.DEFAULT_PROFILE_NAME)?.warmUpRendererProcess()
            }
        }
        return true
    }

    override fun newSession(sessionToken: CustomTabsSessionToken): Boolean = true

    @OptIn(Profile.ExperimentalPreconnect::class)
    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: List<Bundle>?,
    ): Boolean {
        val scheme = url?.scheme?.lowercase(Locale.ROOT)
        if (url != null && (scheme == "http" || scheme == "https")) {
            val target = url.toString()
            Handler(Looper.getMainLooper()).post {
                if (
                    WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.PRECONNECT)
                ) {
                    ProfileStore.getInstance().getProfile(Profile.DEFAULT_PROFILE_NAME)?.preconnect(target)
                }
            }
        }
        return true
    }

    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null

    override fun updateVisuals(sessionToken: CustomTabsSessionToken, bundle: Bundle?): Boolean = false

    override fun requestPostMessageChannel(sessionToken: CustomTabsSessionToken, postMessageOrigin: Uri): Boolean = false

    override fun postMessage(sessionToken: CustomTabsSessionToken, message: String, extras: Bundle?): Int =
        RESULT_FAILURE_DISALLOWED

    override fun validateRelationship(
        sessionToken: CustomTabsSessionToken,
        relation: Int,
        origin: Uri,
        extras: Bundle?,
    ): Boolean = false

    override fun receiveFile(
        sessionToken: CustomTabsSessionToken,
        uri: Uri,
        purpose: Int,
        extras: Bundle?,
    ): Boolean = false
}
