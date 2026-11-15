package com.hiron.ren.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The ren account the app's pages are signed in to, as they report it (ren desktop's auth and interview stores). */
data class RenAccount(val signedIn: Boolean = false, val email: String = "", val surveyComplete: Boolean = false) {
    /** ren desktop's Context widget is there for a signed-in account that finished the interview. */
    val contextReady: Boolean get() = signedIn && surveyComplete

    companion object {
        fun parse(json: JSONObject) = RenAccount(json.optBoolean("signedIn"), json.optString("email"), json.optBoolean("surveyComplete"))
    }
}

/** What the Context widget's page shows, and so how much of the screen its view takes. */
enum class ContextLayout {
    None,
    Orb,

    /** The orb with a specialist's finished run over it. */
    Notice,
    Panel,
    ;

    companion object {
        fun of(wire: String): ContextLayout = entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } ?: None
    }
}

/** An app the user had on screen, and for how many whole minutes, between two reports. */
data class AppUse(val name: String, val minutes: Int)

/** What Ren did on the phone (the activity log), with when. */
data class PhoneAction(val text: String, val at: Long)

/**
 * ren desktop's Context widget on the phone: one WebView on the bundled context page, which runs ren desktop's widget
 * and its Context Agent's store unmodified, kept alive while the app runs so the agent hears what the phone does even
 * while the widget is closed. The app tells the page about [visit]s in its browser and, at most every [REPORT_EVERY_MS],
 * what the user did on the phone since the last report ([PhoneActivity] from [activity]); the page answers with the
 * [account] it is signed in to and the [layout] it needs.
 */
class ContextWidget(
    private val context: Context,
    private val token: () -> String,
    private val activity: (since: Long, until: Long) -> Pair<List<AppUse>, List<PhoneAction>>,
    private val reportedAt: () -> Long,
    private val reported: (Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _account = MutableStateFlow(RenAccount())
    val account: StateFlow<RenAccount> = _account.asStateFlow()

    private val _layout = MutableStateFlow(ContextLayout.None)
    val layout: StateFlow<ContextLayout> = _layout.asStateFlow()

    private var page: JavaScriptReplyProxy? = null
    private val visits = ArrayDeque<JSONObject>()
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
        .build()

    private var view: WebView? = null

    /** The widget's view, made on first use; null on a WebView without the message listener, where the page can't talk. */
    fun view(): WebView? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return null
        return view ?: create().also { view = it }
    }

    /** A page the phone's browser finished loading; the page's store keeps the public web ones, as ren desktop's does. */
    fun visit(url: String, title: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        val event = JSONObject().put("type", "visit").put("url", url).put("title", title)
        val proxy = page
        if (proxy != null && _account.value.contextReady) proxy.postMessage(event.toString()) else {
            visits.addLast(event)
            while (visits.size > VISITS_KEPT) visits.removeFirst()
        }
    }

    /** The app came to the front: what the user did on the phone since the last report goes to the Context Agent. */
    fun resumed() = report()

    /** Closes the open panel (Back). */
    fun close() {
        page?.postMessage(JSONObject().put("type", "close").toString())
    }

    /** Signs the ren account out of the app's pages (Settings › Account). */
    fun signOut() {
        page?.postMessage(JSONObject().put("type", "sign-out").toString())
    }

    /**
     * Another of the app's pages reported the account (ren's sign-in and interview run on the destinations page). The
     * widget's page reads them when it loads, so a change loads it again.
     */
    fun accountChanged(account: RenAccount) {
        if (account == _account.value) return
        _account.value = account
        page = null
        view?.reload()
    }

    private fun onMessage(message: JSONObject, reply: JavaScriptReplyProxy) {
        when (message.optString("type")) {
            "token" -> reply.postMessage(JSONObject().put("type", "token").put("token", token()).toString())
            "account" -> {
                page = reply
                _account.value = RenAccount.parse(message)
                if (_account.value.contextReady) {
                    while (visits.isNotEmpty()) reply.postMessage(visits.removeFirst().toString())
                    report()
                } else {
                    // Signed out or not through the interview: the record starts again once the account is ready.
                    visits.clear()
                    reported(0L)
                }
            }
            "layout" -> _layout.value = ContextLayout.of(message.optString("layout"))
        }
    }

    private fun report() {
        val proxy = page ?: return
        if (!_account.value.contextReady) return
        val until = now()
        val since = reportedAt()
        // The first report starts the record: the agent hears about the phone from the sign-in on, as ren desktop's
        // does about browsing.
        if (since == 0L) return reported(until)
        if (until - since < REPORT_EVERY_MS) return
        val (apps, actions) = activity(since, until)
        reported(until)
        if (apps.isEmpty() && actions.isEmpty()) return
        val json = JSONObject()
            .put("since", since)
            .put("until", until)
            .put("apps", JSONArray(apps.map { JSONObject().put("name", it.name).put("minutes", it.minutes) }))
            .put("actions", JSONArray(actions.map { JSONObject().put("text", it.text).put("at", it.at) }))
        proxy.postMessage(JSONObject().put("type", "phone").put("activity", json).toString())
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun create(): WebView = WebView(MutableContextWrapper(context)).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = true
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.host == ORIGIN_HOST) loader.shouldInterceptRequest(request.url) else null

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                // The renderer died: this view can't be used again, so the next view() makes a fresh one.
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                this@ContextWidget.view = null
                page = null
                _layout.value = ContextLayout.None
                return true
            }
        }
        WebViewCompat.addWebMessageListener(this, "RenHost", setOf("https://$ORIGIN_HOST")) { _, message, _, isMainFrame, reply ->
            val json = message.data?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (isMainFrame && json != null) onMessage(json, reply)
        }
        loadUrl(PAGE)
    }

    companion object {
        private const val ORIGIN_HOST = "appassets.androidplatform.net"
        private const val PAGE = "https://$ORIGIN_HOST/assets/ren/web/context/index.html"

        /** ren desktop sends browsing once the oldest unsent page has waited three minutes (`browsingDue`); the phone's report keeps the same pace. */
        const val REPORT_EVERY_MS = 3 * 60_000L

        /** ren desktop's browsing queue holds 40 pages (`recordVisit`). */
        private const val VISITS_KEPT = 40
    }
}
