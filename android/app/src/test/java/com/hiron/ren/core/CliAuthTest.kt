package com.hiron.ren.core

import com.hiron.ren.voice.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CliAuthTest {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun detectsOAuthCallbackUrlsWithCodeOrError() {
        val claudePrefix = "https://platform.claude.com/oauth/code/callback"
        assertTrue(CliAuth.isCallbackUrl("$claudePrefix?code=abc&state=xyz", claudePrefix))
        assertTrue(CliAuth.isCallbackUrl("$claudePrefix?error=access_denied", claudePrefix))
        assertTrue(CliAuth.isCallbackUrl("$claudePrefix#code=abc&state=xyz", claudePrefix))
        assertFalse(CliAuth.isCallbackUrl(claudePrefix, claudePrefix))
        assertFalse(CliAuth.isCallbackUrl("https://claude.ai/login?code=abc", claudePrefix))

        val agyPrefix = "https://antigravity.google/oauth-callback"
        assertTrue(CliAuth.isCallbackUrl("$agyPrefix?state=s1&code=4/0Acode", agyPrefix))
        assertFalse(CliAuth.isCallbackUrl("$agyPrefix?state=s1", agyPrefix))
    }

    @Test
    fun signInInterceptsCallbackCompletesAndSignsOut(): Unit = runBlocking {
        val signedIn = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val lastAuthHeader = AtomicReference<String?>()
        val jsonType = "application/json".toMediaType()

        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                lastAuthHeader.set(req.header("Authorization"))
                val reqBody = Buffer().also { req.body?.writeTo(it) }.readUtf8()
                val (code, body) = when (req.url.encodedPath) {
                    "/gemini/cli-auth/status" -> {
                        val provider = req.url.queryParameter("provider").orEmpty()
                        200 to JSONObject()
                            .put("provider", provider)
                            .put("signed_in", signedIn.get())
                            .put("account", if (signedIn.get()) "tim@oubliai.com" else "")
                            .put("method", if (signedIn.get()) "claude.ai" else "none")
                            .put("callback_prefix", "https://platform.claude.com/oauth/code/callback")
                            .toString()
                    }
                    "/gemini/cli-auth/start" -> {
                        val provider = JSONObject(reqBody).getString("provider")
                        val prefix = if (provider == "claude-code") {
                            "https://platform.claude.com/oauth/code/callback"
                        } else {
                            "https://antigravity.google/oauth-callback"
                        }
                        200 to JSONObject()
                            .put("session_id", "sess-1")
                            .put("provider", provider)
                            .put("url", "https://claude.com/cai/oauth/authorize?state=s1")
                            .put("callback_prefix", prefix)
                            .toString()
                    }
                    "/gemini/cli-auth/complete" -> {
                        val input = JSONObject(reqBody).getString("input")
                        if (input.contains("code=good")) {
                            signedIn.set(true)
                            200 to JSONObject()
                                .put("provider", "claude-code")
                                .put("signed_in", true)
                                .put("account", "tim@oubliai.com")
                                .put("method", "claude.ai")
                                .toString()
                        } else {
                            400 to JSONObject().put("detail", "That code wasn't accepted.").toString()
                        }
                    }
                    "/gemini/cli-auth/cancel" -> {
                        cancelled.set(true)
                        200 to """{"ok":true}"""
                    }
                    "/gemini/cli-auth/logout" -> {
                        signedIn.set(false)
                        200 to JSONObject()
                            .put("provider", "claude-code")
                            .put("signed_in", false)
                            .put("account", "")
                            .put("method", "none")
                            .toString()
                    }
                    else -> 404 to """{"detail":"not found"}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "ERR")
                    .body(body.toResponseBody(jsonType))
                    .build()
            }
            .build()

        val auth = CliAuth(http, { "tok-123" }, scope, baseUrl = "https://workspace.test/gemini", main = Dispatchers.Unconfined)
        val opened = CompletableDeferred<Pair<String, (String) -> Unit>>()
        val returned = AtomicBoolean(false)
        auth.openAuthTab = { url, _, onCallback ->
            val _ = opened.complete(url to onCallback)
        }
        auth.returnToSettings = { returned.set(true) }

        auth.refresh(Provider.ClaudeCode)
        val initial = withTimeout(5_000) {
            auth.state.first { Provider.ClaudeCode in it.statuses && Provider.ClaudeCode !in it.checking }
        }
        assertFalse(initial.statuses.getValue(Provider.ClaudeCode).signedIn)
        assertEquals("Bearer tok-123", lastAuthHeader.get())

        auth.signIn(Provider.ClaudeCode)
        val pendingState = withTimeout(5_000) { auth.state.first { it.pending != null || it.error != null } }
        assertNull("signIn failed: ${pendingState.error}", pendingState.error)
        val (openedUrl, deliver) = withTimeout(5_000) { opened.await() }
        assertEquals("sess-1", pendingState.pending?.sessionId)
        assertEquals("https://claude.com/cai/oauth/authorize?state=s1", openedUrl)

        // Simulate Browser intercepting the OAuth redirect URL automatically.
        deliver("https://platform.claude.com/oauth/code/callback?code=good&state=s1")

        val done = withTimeout(5_000) {
            auth.state.first { it.statuses[Provider.ClaudeCode]?.signedIn == true && it.completing == null }
        }
        assertTrue(returned.get())
        assertEquals("tim@oubliai.com", done.statuses.getValue(Provider.ClaudeCode).account)
        assertNull(done.pending)
        assertEquals(1, done.revision)

        auth.signOut(Provider.ClaudeCode)
        val out = withTimeout(5_000) {
            auth.state.first { it.statuses[Provider.ClaudeCode]?.signedIn == false && Provider.ClaudeCode !in it.checking }
        }
        assertEquals(2, out.revision)

        // Start Antigravity and verify cancel clears pending state and notifies server.
        auth.signIn(Provider.Antigravity)
        val agyPending = withTimeout(5_000) { auth.state.first { it.pending?.provider == Provider.Antigravity } }
        assertNotNull(agyPending.pending)
        auth.cancel()
        val afterCancel = withTimeout(5_000) { auth.state.first { it.pending == null } }
        assertNull(afterCancel.pending)
        withTimeout(5_000) {
            while (!cancelled.get()) delay(25)
        }
        assertTrue(cancelled.get())
    }
}
