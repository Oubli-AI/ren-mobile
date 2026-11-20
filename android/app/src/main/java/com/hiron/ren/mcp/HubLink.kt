package com.hiron.ren.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.WebSocketClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Where the phone stands with Ren's hub. */
public sealed interface HubState {
    /** The link is switched off. */
    public data object Off : HubState

    public data object Connecting : HubState

    /** The phone's tools are served through the hub. */
    public data object Attached : HubState

    /** The last try failed for [reason]; another follows. */
    public data class Retrying(val reason: String) : HubState
}

/**
 * The phone's link to Ren's hub. A phone can't be called from outside, so it dials the hub and serves its MCP server
 * over that WebSocket (the SDK's WebSocket transport carries either side of MCP; the hub is the client on it). The
 * link stays up while it is on: when the socket drops it dials again, waiting a little longer after each failure.
 *
 * It is also the phone's MCP client: [tools] and [call] reach whatever else is attached to the hub, ren desktop's
 * tools under `desktop_`.
 */
public class HubLink(
    private val scope: CoroutineScope,
    /** The hub's WebSocket base, like `wss://<workspace host>/hub`. */
    private val url: () -> String,
    private val token: () -> String,
    private val version: String,
    private val server: () -> Server,
) {
    private val _state = MutableStateFlow<HubState>(HubState.Off)
    public val state: StateFlow<HubState> = _state.asStateFlow()

    private val http = HttpClient(CIO) {
        install(WebSockets)
        install(SSE)
    }
    private var job: Job? = null

    public fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { keepAttached() }
    }

    public fun stop() {
        job?.cancel()
        job = null
        _state.value = HubState.Off
    }

    private suspend fun keepAttached() {
        var wait = FIRST_RETRY
        while (scope.isActive) {
            _state.value = HubState.Connecting
            val failure = try {
                attachUntilClosed { wait = FIRST_RETRY }
                "The hub closed the link."
            } catch (cancelled: CancellationException) {
                // A socket that closes cancels the calls waiting on it; only this link's own cancellation ends the loop.
                currentCoroutineContext().ensureActive()
                "The hub closed the link."
            } catch (error: Exception) {
                error.message ?: error.javaClass.simpleName
            }
            _state.value = HubState.Retrying(failure)
            delay(wait)
            wait = (wait * 2).coerceAtMost(LAST_RETRY)
        }
    }

    /** One attachment: dial, serve the phone's MCP server on the socket, and return when the socket closes. */
    private suspend fun attachUntilClosed(onAttached: () -> Unit) {
        val transport = WebSocketClientTransport(http, "${url().trimEnd('/')}/attach/phone") { header(HttpHeaders.Authorization, "Bearer ${token()}") }
        val closed = CompletableDeferred<Unit>()
        val session = server().createSession(transport)
        session.onClose { closed.complete(Unit) }
        try {
            // A socket the hub refused can close before the callback is in place, so the link is proven by a ping the
            // hub answers, and watched with another every [PING_EVERY].
            while (true) {
                withTimeoutOrNull(PING_TIMEOUT) { session.ping() } ?: error("The hub didn't answer.")
                if (_state.value != HubState.Attached) {
                    _state.value = HubState.Attached
                    onAttached()
                }
                if (withTimeoutOrNull(PING_EVERY) { closed.await() } != null) return
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { session.close() } }
        }
    }

    /** The hub's tools as the phone sees them: its own, ren desktop's under `desktop_`, and `hub_peers`. */
    public suspend fun tools(): List<Tool> = withHub { it.listTools(ListToolsRequest()).tools }

    /** Calls the hub's tool [name] and returns its text, or throws with what the tool said went wrong. */
    public suspend fun call(name: String, arguments: Map<String, Any?> = emptyMap()): String = withHub { hub ->
        val result: CallToolResult = hub.callTool(name, arguments)
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        if (result.isError == true) error(text.ifBlank { "$name failed." })
        text
    }

    /** One MCP client session with the hub over Streamable HTTP. */
    private suspend fun <T> withHub(use: suspend (Client) -> T): T = withContext(Dispatchers.IO) {
        val client = Client(Implementation(name = "ren-phone", version = version))
        val base = url().trimEnd('/').replaceFirst("ws", "http")
        client.connect(StreamableHttpClientTransport(http, "$base/mcp") { header(HttpHeaders.Authorization, "Bearer ${token()}") })
        try {
            use(client)
        } finally {
            val _ = runCatching { client.close() }
        }
    }

    public companion object {
        private val FIRST_RETRY = 2.seconds
        private val LAST_RETRY = 60.seconds
        private val PING_EVERY = 20.seconds
        private val PING_TIMEOUT = 10.seconds

        /** The hub tool that says which sides are attached. */
        public const val PEERS: String = "hub_peers"
    }
}
