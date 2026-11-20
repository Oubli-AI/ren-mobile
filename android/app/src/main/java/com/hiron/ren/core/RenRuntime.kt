package com.hiron.ren.core

import com.hiron.ren.browser.Applications
import com.hiron.ren.browser.Browser
import com.hiron.ren.browser.BrowserDns
import com.hiron.ren.browser.BrowserHistory
import com.hiron.ren.browser.BrowserStore
import com.hiron.ren.browser.ContextWidget
import com.hiron.ren.browser.Favorites
import com.hiron.ren.browser.PhoneChat
import com.hiron.ren.browser.PhoneReport
import com.hiron.ren.browser.PhoneAction
import com.hiron.ren.browser.SitePermissionsManager
import android.app.Application
import android.content.Context
import com.hiron.ren.BuildConfig
import com.hiron.ren.device.AppUsage
import com.hiron.ren.device.DeviceTools
import com.hiron.ren.device.Messaging
import com.hiron.ren.device.RenCallScreening
import com.hiron.ren.device.RenNotificationListener
import com.hiron.ren.device.ReminderStore
import com.hiron.ren.device.ReminderStoreFile
import com.hiron.ren.device.Reminders
import com.hiron.ren.mcp.HubLink
import com.hiron.ren.mcp.PhoneMcpServer
import com.hiron.ren.service.Notifications
import com.hiron.ren.ui.ImageLoader
import com.hiron.ren.ui.Plain
import com.hiron.ren.voice.AgentTools
import com.hiron.ren.voice.Assistant
import com.hiron.ren.voice.RenAgent
import com.hiron.ren.voice.Provider
import com.hiron.ren.voice.StrandsAgent
import com.hiron.ren.voice.SearchTools
import com.hiron.ren.widget.RenWidgets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

public class RenApp : Application() {
    public lateinit var runtime: RenRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        runtime = RenRuntime(this)
        RenWidgets.follow(this, runtime)
    }
}

public val Context.ren: RenRuntime get() = (applicationContext as RenApp).runtime

/**
 * The rendered [state], the robot's command stream, the phone tools, the agents ([agent], ElevenLabs or Strands) and the
 * approvals they ask for with `request_approval`.
 */
public class RenRuntime(private val app: Context) {
    public val settings: Settings = Settings(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow(RenState())
    public val state: StateFlow<RenState> = _state.asStateFlow()

    private val _robot = MutableSharedFlow<RobotCommand>(extraBufferCapacity = 256)
    public val robot: SharedFlow<RobotCommand> = _robot.asSharedFlow()

    @Volatile
    private var shown = false

    /**
     * Whether Ren's screen is in front of the user (lets device tools open apps directly). Coming to the front
     * clears the approval notifications, since the sheet shows those approvals.
     */
    public var visible: Boolean
        get() = shown
        set(value) {
            val appeared = value && !shown
            shown = value
            if (appeared) Notifications.clearApprovals(app, state.value.approvals.map { it.id })
        }

    public val reminders: Reminders = Reminders(app, ReminderStore(File(app.filesDir, ReminderStoreFile)))
    public val tools: DeviceTools = DeviceTools(app, reminders) { visible }

    /** Approvals the agents are waiting on, by request id. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ApprovalAnswer>>()

    private val pslScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    internal val publicSuffixList: PublicSuffixList = PublicSuffixList(app, Dispatchers.Unconfined, pslScope)
    internal val browserDns: BrowserDns = BrowserDns(app, settings.dnsMode, settings.customDohUrl, settings.shieldsEnabled) { host ->
        !settings.isShieldsEnabledFor(host)
    }

    private val http: OkHttpClient = OkHttpClient.Builder().dns(browserDns).build()

    /** Brave Search for the agents' search tools, with the keys in Settings. */
    public val search: BraveSearch = BraveSearch(http, settings::braveSearchKey, settings::braveAnswersKey)

    /** [search] for the agents' search tools, which they get only while `braveSearchTools` is on ([BuildConfig.BRAVE_SEARCH_TOOLS]). */
    private val braveSearch: (suspend (SearchQuery) -> Result<SearchResults>)? = if (BuildConfig.BRAVE_SEARCH_TOOLS) search::search else null

    /** The browser's tabs. */
    public val favorites: Favorites = Favorites({ settings.favorites }, { settings.favorites = it })
    internal val history: BrowserHistory = BrowserHistory({ settings.browserHistory }, { settings.browserHistory = it })
    internal val sitePermissions: SitePermissionsManager = SitePermissionsManager(app)
    public val browser: Browser = Browser(
        context = app,
        store = BrowserStore({ settings.browserTabs }, { settings.browserTabs = it }),
        favorites = favorites,
        token = { settings.geminiToken },
        history = history,
        sitePermissions = sitePermissions,
        publicSuffixList = publicSuffixList,
        browserSettings = settings,
        dns = browserDns,
    )

    /** Applies updated DNS, Shields and WebView preferences to [browserDns] and open browser tabs. */
    internal fun applyBrowserPreferences() {
        browserDns.configure(settings.dnsMode, settings.customDohUrl, settings.shieldsEnabled)
        browser.applyWebPreferences()
    }

    /** Which apps the user used, for the Context Agent. */
    public val appUsage: AppUsage = AppUsage(app)

    /**
     * ren desktop's Context widget and Context Agent, on the ren account the pages are signed in to: it hears the
     * browser's pages, the apps the user used and what Ren did on the phone.
     */
    public val contextWidget: ContextWidget = ContextWidget(app, { settings.geminiToken }, ::phoneActivity, { settings.phoneReportedAt }, { settings.phoneReportedAt = it })

    /** The apps OpenCode built for the ren account, on Home. */
    public val applications: Applications = Applications(app, http) { settings.geminiToken }

    init {
        scope.launch(Dispatchers.IO) { publicSuffixList.prefetch().await() }
        browser.visited = contextWidget::visit
        browser.accountReported = contextWidget::accountChanged
        RenNotificationListener.posted = contextWidget::notification
        contextWidget.asked = ::opened
        scope.launch {
            contextWidget.account.collect {
                applications.userId = if (it.signedIn) it.userId else ""
                applications.refresh()
            }
        }
    }

    private val _attachments = MutableStateFlow<List<PromptAttachment>>(emptyList())

    /** The composer's attachment cards: pastes, and what a page opened with Ask Ren. They go with the next message. */
    public val attachments: StateFlow<List<PromptAttachment>> = _attachments.asStateFlow()

    private val _opens = MutableStateFlow(0)

    /** Counts what pages opened in Ren with Ask Ren; each one opens the agent panel. */
    public val opens: StateFlow<Int> = _opens.asStateFlow()

    /** Adds cards to the composer, at most [PromptAttachments.MAX] in all. */
    public fun attach(items: List<PromptAttachment>) {
        _attachments.value = (_attachments.value + items.filter { item -> _attachments.value.none { it.id == item.id } }).take(PromptAttachments.MAX)
    }

    /** A paste in the composer becomes a card, named from its text. */
    public fun attachPaste(text: String) {
        if (text.isBlank()) return
        attach(listOf(PromptAttachments.pasted(text, _attachments.value.map { it.title })))
    }

    /** Takes a card out of the composer. */
    public fun detach(id: String) {
        _attachments.value = _attachments.value.filterNot { it.id == id }
    }

    /** Sends [typed] with the composer's cards, which leave the composer; returns the id of the user's message. */
    public fun send(typed: String): String {
        val sent = _attachments.value
        _attachments.value = emptyList()
        return ask(PromptAttachments.message(typed, sent))
    }

    /** Ask Ren from the Context widget: [items] land in the composer, and [text], when there is any, is sent with them. */
    private fun opened(text: String, items: List<PromptAttachment>) {
        attach(items)
        _opens.value++
        if (text.isNotBlank()) {
            val _ = send(text)
        }
    }

    /** The user's chat messages the Context Agent has already heard. */
    private var heard = emptySet<String>()

    /**
     * What happened on the phone between two moments: the apps the user used, what Ren did for them (the activity
     * log), their calls and texts, and what they said to Ren since the last report with Ren's answers.
     */
    private fun phoneActivity(since: Long, until: Long): PhoneReport {
        val messages = state.value.messages.filter { !it.system }
        val chats = messages.mapIndexedNotNull { i, message ->
            if (!message.fromUser || message.id in heard) return@mapIndexedNotNull null
            PhoneChat(message.text, messages.getOrNull(i + 1)?.takeIf { !it.fromUser }?.text.orEmpty())
        }
        heard = messages.filter { it.fromUser }.map { it.id }.toSet()
        val messaging = Messaging(app)
        return PhoneReport(
            apps = appUsage.between(since, until),
            actions = state.value.log.filter { it.at in since until until && it.kind in PHONE_ACTIONS }.map { PhoneAction(Plain.log(it.kind, it.text), it.at) },
            calls = messaging.calls(PHONE_ROWS, since),
            sms = messaging.texts(null, PHONE_ROWS, since),
            chats = chats,
        )
    }

    /** Result thumbnails and images, cached in memory only. */
    public val images: ImageLoader = ImageLoader(http)

    private val elevenLabs = RenAgent(app, settings, tools::run, ::approve, ::update, { _robot.tryEmit(it) }, scope, braveSearch)
    /** Gemini, NVIDIA NIM, Mistral and Telnyx in Strands on the workspace VM. */
    public val strands: StrandsAgent = StrandsAgent(app, settings, http, ::update, { _robot.tryEmit(it) }, scope)

    /** The agents for the provider chosen in Settings. */
    public val agent: Assistant
        get() = when (settings.provider) {
            Provider.ElevenLabs -> elevenLabs
            Provider.Gemini, Provider.Nim, Provider.Mistral, Provider.Telnyx, Provider.Xai -> strands
        }

    /** Switches provider; the conversation ends, and the next one starts with [provider]. */
    public fun choose(provider: Provider) {
        if (provider == settings.provider) return
        newChat()
        settings.provider = provider
    }

    /** Switches the text model (null: the provider's default) and its reasoning; the next conversation uses them. */
    public fun chooseModel(provider: Provider, model: String?) {
        if (model == settings.textModel(provider)) return
        if (provider == settings.provider) agent.stop()
        settings.setTextModel(provider, model)
    }

    public fun chooseEffort(provider: Provider, effort: String) {
        if (effort == settings.effort(provider)) return
        if (provider == settings.provider) agent.stop()
        settings.setEffort(provider, effort)
    }

    /** The phone tools, approvals, searches and the browser's tools as an MCP server, served through Ren's hub ([hub]). */
    public val phoneServer: PhoneMcpServer = PhoneMcpServer(
        BuildConfig.VERSION_NAME,
        browser,
        level = { settings.searchLevel },
        approve = { input -> AgentTools.askApproval(input.opt("action_type") as? String, input.opt("details") as? String, ::approve) },
        search = braveSearch?.let { brave -> { name, input -> SearchTools.call(name, PhoneMcpServer.parameters(input), brave, ::update) } },
        run = tools::run,
    )

    /** The phone's link to Ren's hub, where it serves [phoneServer] and reaches ren desktop. */
    public val hub: HubLink = HubLink(scope, { settings.hubUrl }, { settings.geminiToken }, BuildConfig.VERSION_NAME) { phoneServer.build() }

    init {
        RenCallScreening.onIncoming = { number -> note("call", "Incoming call from ${number.ifBlank { "unknown number" }}") }
    }

    /** Sends [text] to the text agent; returns the id of the user's message. */
    public fun ask(text: String): String = agent.sendText(text)

    /** Answers a waiting approval; "Always allow" grants its action to every session from now on. */
    public fun answer(id: String, answer: ApprovalAnswer) {
        val request = state.value.approvals.firstOrNull { it.id == id } ?: return
        if (answer.approved && answer.remember) settings.grant(request.action, true)
        pending[id]?.complete(answer)
        note("approval", RenStore.answer(request, answer))
    }

    /**
     * Shows [request] on the approval sheet (and as a notification while Ren is in the background) and waits for
     * the user. When the agent stops waiting first, the request leaves the sheet.
     */
    public suspend fun approve(request: ApprovalRequest): ApprovalAnswer {
        val answer = CompletableDeferred<ApprovalAnswer>()
        pending[request.id] = answer
        var answered = false
        return try {
            update { it.copy(approvals = it.approvals + request, activity = Activity.Waiting) }
            if (!visible) Notifications.approval(app, request)
            answer.await().also { answered = true }
        } finally {
            pending.remove(request.id)
            Notifications.clearApproval(app, request.id)
            update { s ->
                val approvals = s.approvals.filterNot { it.id == request.id }
                val activity = if (s.activity == Activity.Waiting && approvals.isEmpty()) Activity.Working else s.activity
                val next = s.copy(approvals = approvals, activity = activity)
                if (answered) next else RenStore.note(next, now(), "approval", RenStore.EXPIRED)
            }
        }
    }

    /**
     * Starts work mode in [folder] on the user's computer, or ends it (null). The running session stops, so the next
     * message opens one with it; the chat carries on.
     */
    public fun work(folder: String?) {
        settings.workFolder = folder
        agent.stop()
    }

    /** Ends the conversation: its session stops and the chat clears, back to Ren on its stage. */
    public fun newChat() {
        agent.stop()
        strands.newConversation()
        update { it.copy(messages = emptyList(), activity = Activity.Idle, lastError = null) }
    }

    public fun listening(on: Boolean): Unit = update { it.copy(activity = if (on) Activity.Listening else Activity.Idle) }

    /** Replays the robot's full state (after the page loads). */
    public fun robotSnapshot(): List<RobotCommand> = RobotDirector.diff(null, state.value)

    private fun note(kind: String, text: String) = update { RenStore.note(it, now(), kind, text) }

    private fun update(change: (RenState) -> RenState) {
        val (before, after) = synchronized(lock) {
            val before = _state.value
            val after = change(before)
            _state.value = after
            before to after
        }
        RobotDirector.diff(before, after).forEach { _robot.tryEmit(it) }
    }

    private fun now() = System.currentTimeMillis()
}

/** The activity log's kinds that are things Ren did on the phone (its phone tools, approvals and calls), not its own errors or notes. */
private val PHONE_ACTIONS = setOf("device", "approval", "call")

/** The most calls and the most texts one phone report carries, as many as `calls_recent` and `sms_list` return. */
private const val PHONE_ROWS: Int = 100
