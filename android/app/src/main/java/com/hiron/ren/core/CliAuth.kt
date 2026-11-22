package com.hiron.ren.core

import com.hiron.ren.BuildConfig
import com.hiron.ren.voice.Provider
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Sign-in state for one CLI text provider (Claude Code or Antigravity) on the workspace server. */
public data class CliAuthStatus(
    val provider: Provider,
    val signedIn: Boolean,
    val account: String = "",
    val method: String = "",
)

/** An in-flight CLI OAuth flow waiting for its browser callback URL or pasted code. */
public data class PendingCliAuth(
    val sessionId: String,
    val provider: Provider,
    val url: String,
    val callbackPrefix: String,
)

/** UI state of CLI provider sign-in across Settings and browser auth tabs. */
public data class CliAuthState(
    val statuses: Map<Provider, CliAuthStatus> = emptyMap(),
    val checking: Set<Provider> = emptySet(),
    val starting: Provider? = null,
    val completing: Provider? = null,
    val pending: PendingCliAuth? = null,
    val error: String? = null,
    val revision: Int = 0,
)

/**
 * Browser-driven sign-in for Claude Code and Antigravity CLI (`deploy/vm/gemini/cli_auth.py`).
 *
 * Lives on [RenRuntime] so an active OAuth session survives Settings closing while the user signs in on a browser tab.
 * Opening the provider's sign-in URL in Ren's own tab lets [com.hiron.ren.browser.Browser] intercept the OAuth callback
 * redirect automatically and call [complete] with zero copy-paste.
 */
public class CliAuth(
    private val http: OkHttpClient,
    private val token: () -> String,
    private val scope: CoroutineScope,
    private val baseUrl: String = SERVICE,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val _state = MutableStateFlow(CliAuthState())
    public val state: StateFlow<CliAuthState> = _state.asStateFlow()

    /** Opens [url] in a browser tab that intercepts [callbackPrefix] and forwards the callback URL to [onCallback]. */
    public var openAuthTab: ((url: String, callbackPrefix: String, onCallback: (String) -> Unit) -> Unit)? = null

    /** Brings Settings back to the foreground once an intercepted callback is received. */
    public var returnToSettings: (() -> Unit)? = null

    /** Clears any active browser callback listener. */
    public var clearAuthCapture: (() -> Unit)? = null

    /** Fetches current sign-in status for [provider] from the workspace server. */
    public fun refresh(provider: Provider) {
        val wire = provider.strands ?: return
        if (!provider.cliAuth) return
        _state.update { it.copy(checking = it.checking + provider) }
        scope.launch {
            val encoded = URLEncoder.encode(wire, StandardCharsets.UTF_8.name())
            val result = call(request("/status?provider=$encoded"))
            _state.update { current ->
                val nextChecking = current.checking - provider
                result.fold(
                    onSuccess = { body ->
                        val (status, serverPending) = parseStatus(provider, body)
                        val pending = when {
                            status.signedIn && current.pending?.provider == provider -> null
                            current.pending != null -> current.pending
                            else -> serverPending
                        }
                        current.copy(
                            statuses = current.statuses + (provider to status),
                            checking = nextChecking,
                            pending = pending,
                        )
                    },
                    onFailure = { current.copy(checking = nextChecking) },
                )
            }
        }
    }

    /** Starts a fresh browser sign-in for [provider] and opens its OAuth page in a Ren tab. */
    public fun signIn(provider: Provider) {
        val wire = provider.strands ?: return
        if (!provider.cliAuth) return
        _state.update { it.copy(starting = provider, error = null) }
        scope.launch {
            val payload = JSONObject().put("provider", wire).toString().toRequestBody(JSON)
            val result = call(request("/start", "POST", payload))
            result.fold(
                onSuccess = { body ->
                    val started = parseStart(provider, body)
                    _state.update { it.copy(starting = null, pending = started, error = null) }
                    withContext(main) {
                        openAuthTab?.invoke(started.url, started.callbackPrefix) { callbackUrl -> complete(callbackUrl) }
                    }
                },
                onFailure = { err ->
                    _state.update { it.copy(starting = null, error = err.message ?: "Couldn't start ${provider.label} sign-in.") }
                },
            )
        }
    }

    /** Re-opens the currently pending sign-in URL in an auto-capturing Ren browser tab. */
    public fun resumeInBrowser() {
        val pending = _state.value.pending ?: return
        _state.update { it.copy(error = null) }
        openAuthTab?.invoke(pending.url, pending.callbackPrefix) { callbackUrl -> complete(callbackUrl) }
    }

    /** Completes the active sign-in session with an intercepted callback URL or manually pasted code. */
    public fun complete(rawInput: String) {
        val pending = _state.value.pending ?: return
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) {
            _state.update { it.copy(error = "Paste the authorization code or callback URL.") }
            return
        }
        clearAuthCapture?.invoke()
        returnToSettings?.invoke()
        _state.update { it.copy(completing = pending.provider, error = null) }
        scope.launch {
            val payload = JSONObject()
                .put("session_id", pending.sessionId)
                .put("input", cleaned)
                .toString()
                .toRequestBody(JSON)
            val result = call(request("/complete", "POST", payload))
            _state.update { current ->
                result.fold(
                    onSuccess = { body ->
                        val (status, _) = parseStatus(pending.provider, body)
                        current.copy(
                            statuses = current.statuses + (pending.provider to status),
                            completing = null,
                            pending = if (current.pending?.sessionId == pending.sessionId) null else current.pending,
                            error = null,
                            revision = current.revision + 1,
                        )
                    },
                    onFailure = { err ->
                        current.copy(
                            completing = null,
                            pending = if (current.pending?.sessionId == pending.sessionId) null else current.pending,
                            error = err.message ?: "Couldn't finish ${pending.provider.label} sign-in.",
                        )
                    },
                )
            }
        }
    }

    /** Cancels any pending sign-in session on the phone and server. */
    public fun cancel() {
        val pending = _state.value.pending
        clearAuthCapture?.invoke()
        _state.update { it.copy(pending = null, starting = null, error = null) }
        if (pending != null) {
            scope.launch {
                val payload = JSONObject().put("session_id", pending.sessionId).toString().toRequestBody(JSON)
                val _ = call(request("/cancel", "POST", payload))
            }
        }
    }

    /** Signs out of [provider] on the workspace server. */
    public fun signOut(provider: Provider) {
        val wire = provider.strands ?: return
        if (!provider.cliAuth) return
        clearAuthCapture?.invoke()
        _state.update { it.copy(checking = it.checking + provider, error = null) }
        scope.launch {
            val payload = JSONObject().put("provider", wire).toString().toRequestBody(JSON)
            val result = call(request("/logout", "POST", payload))
            _state.update { current ->
                val nextChecking = current.checking - provider
                result.fold(
                    onSuccess = { body ->
                        val (status, _) = parseStatus(provider, body)
                        current.copy(
                            statuses = current.statuses + (provider to status),
                            checking = nextChecking,
                            pending = if (current.pending?.provider == provider) null else current.pending,
                            error = null,
                            revision = current.revision + 1,
                        )
                    },
                    onFailure = { err ->
                        current.copy(
                            checking = nextChecking,
                            error = err.message ?: "Couldn't sign out of ${provider.label}.",
                        )
                    },
                )
            }
        }
    }

    private fun request(path: String, method: String = "GET", body: RequestBody? = null): Request = Request.Builder()
        .url("$baseUrl/cli-auth$path")
        .header("Authorization", "Bearer ${token()}")
        .method(method, body)
        .build()

    private suspend fun call(request: Request): Result<String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) Result.success(text)
                else Result.failure(
                    IOException(
                        runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
                            .ifBlank { "The workspace answered ${response.code}" },
                    ),
                )
            }
        } catch (error: IOException) {
            Result.failure(error)
        }
    }

    public companion object {
        public const val SERVICE: String = "https://${BuildConfig.WORKSPACE_HOST}/gemini"
        private val JSON = "application/json".toMediaType()

        /** True when [url] is the OAuth redirect for [callbackPrefix] and carries an authorization code or error. */
        public fun isCallbackUrl(url: String, callbackPrefix: String): Boolean {
            if (callbackPrefix.isBlank() || !url.startsWith(callbackPrefix)) return false
            val parsed = runCatching { URI(url) }.getOrNull() ?: return false
            return hasAuthParam(parsed.rawQuery) || hasAuthParam(parsed.rawFragment)
        }

        private fun hasAuthParam(segment: String?): Boolean {
            if (segment.isNullOrBlank()) return false
            return segment.split('&').any { part ->
                val name = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                (name == "code" || name == "error") && value.isNotBlank()
            }
        }

        public fun parseStatus(provider: Provider, text: String): Pair<CliAuthStatus, PendingCliAuth?> {
            val row = JSONObject(text)
            val status = CliAuthStatus(
                provider = provider,
                signedIn = row.optBoolean("signed_in", false),
                account = row.optString("account"),
                method = row.optString("method"),
            )
            val sessionId = row.optString("pending_session_id").takeIf { it.isNotBlank() && it != "null" }
            val url = row.optString("pending_url").takeIf { it.isNotBlank() && it != "null" }
            val prefix = row.optString("callback_prefix")
            val pending = if (sessionId != null && url != null) PendingCliAuth(sessionId, provider, url, prefix) else null
            return status to pending
        }

        public fun parseStart(provider: Provider, text: String): PendingCliAuth {
            val row = JSONObject(text)
            return PendingCliAuth(
                sessionId = row.getString("session_id"),
                provider = provider,
                url = row.getString("url"),
                callbackPrefix = row.optString("callback_prefix"),
            )
        }
    }
}
