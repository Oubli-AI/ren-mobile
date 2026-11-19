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
public data class RenAccount(val signedIn: Boolean = false, val email: String = "", val surveyComplete: Boolean = false, val userId: String = "") {
    /** ren desktop's Context widget is there for a signed-in account that finished the interview. */
    val contextReady: Boolean get() = signedIn && surveyComplete

    public companion object {
        public fun parse(json: JSONObject): RenAccount = RenAccount(json.optBoolean("signedIn"), json.optString("email"), json.optBoolean("surveyComplete"), json.optString("userId"))
    }
}

/** What the Context widget's page shows, and so how much of the screen its view takes. */
public enum class ContextLayout {
    None,
    Orb,

    /** The orb with a specialist's finished run over it. */
    Notice,
    Panel,
    ;

    public companion object {
        public fun of(wire: String): ContextLayout = entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } ?: None
    }
}

/** An app the user had on screen, and for how many whole minutes, between two reports. */
public data class AppUse(val name: String, val minutes: Int)

/** What Ren did on the phone (the activity log), with when. */
public data class PhoneAction(val text: String, val at: Long)

/** Something the user said to Ren on the phone, and Ren's answer (empty while none came). */
public data class PhoneChat(val user: String, val assistant: String)

/**
 * What happened on the phone between two reports: the apps the user used, what Ren did, the calls and texts (the
 * phone tools' `calls_recent` and `sms_list` rows, ren's `PhoneCall` and `PhoneSms`) and the chats with Ren.
 */
public class PhoneReport(
    public val apps: List<AppUse>,
    public val actions: List<PhoneAction>,
    public val calls: JSONArray,
    public val sms: JSONArray,
    public val chats: List<PhoneChat>,
)

/**
 * ren desktop's Context widget on the phone: one WebView on the bundled context page, which runs ren desktop's widget
 * and its Context Agent's store unmodified, kept alive while the app runs so the agent hears what the phone does even
 * while the widget is closed. The app tells the page about [visit]s and searches in its browser, every
 * [notification] another app posts and, at most every [REPORT_EVERY_MS], what happened on the phone since the last
 * report ([PhoneReport] from [activity]); the page answers with the [account] it is signed in to and the [layout] it
 * needs.
 */
public class ContextWidget(
    private val context: Context,
    private val token: () -> String,
    private val activity: (since: Long, until: Long) -> PhoneReport,
    private val reportedAt: () -> Long,
    private val reported: (Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _account = MutableStateFlow(RenAccount())
    public val account: StateFlow<RenAccount> = _account.asStateFlow()

    private val _layout = MutableStateFlow(ContextLayout.None)
    public val layout: StateFlow<ContextLayout> = _layout.asStateFlow()

    private var page: JavaScriptReplyProxy? = null
    private val waiting = ArrayDeque<JSONObject>()

    /** The text each notification last had, so an app's repost of the same one isn't heard twice. */
    private val heard = LinkedHashMap<String, String>()
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
        .build()

    private var view: WebView? = null

    /** The widget's view, made on first use; null on a WebView without the message listener, where the page can't talk. */
    public fun view(): WebView? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return null
        return view ?: create().also { view = it }
    }

    /** A page the phone's browser finished loading, or a search (ren desktop's search page tells its store the query). */
    public fun visit(url: String, title: String) {
        val shown = Browser.display(url)
        val query = Addresses.searchQuery(shown)
        when {
            query != null -> tell(JSONObject().put("type", "search").put("query", query))
            shown.startsWith("https://") || shown.startsWith("http://") -> tell(JSONObject().put("type", "visit").put("url", shown).put("title", title))
        }
    }

    /**
     * A notification another app posted (`notifications_list`'s row, ren's `PhoneNotification`): the Context Agent
     * reacts to each one at once, and drafts a reply to a message from a person.
     */
    public fun notification(json: JSONObject) {
        val key = json.getString("key")
        val text = json.optString("title") + json.optString("text")
        if (heard.put(key, text) == text) return
        while (heard.size > NOTIFICATIONS_HEARD) heard.remove(heard.keys.first())
        tell(JSONObject().put("type", "notification").put("notification", json))
    }

    private fun tell(event: JSONObject) {
        val proxy = page
        if (proxy != null && _account.value.contextReady) proxy.postMessage(event.toString()) else {
            waiting.addLast(event)
            while (waiting.size > WAITING_KEPT) waiting.removeFirst()
        }
    }

    /** The app came to the front: what the user did on the phone since the last report goes to the Context Agent. */
    public fun resumed(): Unit = report()

    /** Closes the open panel (Back). */
    public fun close() {
        page?.postMessage(JSONObject().put("type", "close").toString())
    }

    /** Signs the ren account out of the app's pages (Settings › Account). */
    public fun signOut() {
        page?.postMessage(JSONObject().put("type", "sign-out").toString())
    }

    /**
     * Another of the app's pages reported the account (ren's sign-in and interview run on the destinations page). The
     * widget's page reads them when it loads, so a change loads it again.
     */
    public fun accountChanged(account: RenAccount) {
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
                    while (waiting.isNotEmpty()) reply.postMessage(waiting.removeFirst().toString())
                    report()
                } else {
                    // Signed out or not through the interview: the record starts again once the account is ready.
                    waiting.clear()
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
        val phone = activity(since, until)
        reported(until)
        phone.chats.forEach {
            proxy.postMessage(JSONObject().put("type", "chat").put("channel", CHAT_CHANNEL).put("user", it.user).put("assistant", it.assistant).toString())
        }
        if (phone.apps.isEmpty() && phone.actions.isEmpty() && phone.calls.length() == 0 && phone.sms.length() == 0) return
        val json = JSONObject()
            .put("since", since)
            .put("until", until)
            .put("apps", JSONArray(phone.apps.map { JSONObject().put("name", it.name).put("minutes", it.minutes) }))
            .put("actions", JSONArray(phone.actions.map { JSONObject().put("text", it.text).put("at", it.at) }))
            .put("calls", phone.calls)
            .put("sms", phone.sms)
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

    public companion object {
        private const val ORIGIN_HOST = "appassets.androidplatform.net"
        private const val NOTIFICATIONS_HEARD: Int = 200

        /** The chats with Ren on the phone, as ren's store names a chat's place (`observeChat`'s channel). */
        private const val CHAT_CHANNEL: String = "Ren on the phone"
        private const val PAGE = "https://$ORIGIN_HOST/assets/ren/web/context/index.html"

        /** ren desktop sends browsing once the oldest unsent page has waited three minutes (`browsingDue`); the phone's report keeps the same pace. */
        public const val REPORT_EVERY_MS: Long = 3 * 60_000L

        /** ren desktop's browsing queue holds 40 pages (`recordVisit`); the phone keeps as many events for a page still loading. */
        private const val WAITING_KEPT: Int = 40
    }
}
