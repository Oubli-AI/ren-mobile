package com.hiron.ren.core

import android.net.Uri
import com.hiron.ren.browser.Applications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** One of the user's developer accounts, as ren's `GET /connections` lists it. */
public data class Connection(val name: String, val label: String, val connected: Boolean, val oauth: Boolean, val tokenHint: String)

/**
 * The user's Vercel, Netlify, GitHub, GitLab, Supabase and Bitbucket accounts for every agent (ren's
 * `core/connections.py`): ren keeps each sign-in on the server and never shows it to an agent. Signing in opens the
 * provider's page ([start]) in a Custom Tab, and the provider returns to ren; a token can be kept instead ([saveToken]).
 * [user] is the id the agents run as: the signed-in ren account's, or the install's.
 */
public class Connections(private val http: OkHttpClient, private val token: () -> String, private val user: () -> String) {
    private val _list = MutableStateFlow<List<Connection>>(emptyList())
    public val list: StateFlow<List<Connection>> = _list.asStateFlow()

    private fun request(path: String, method: String = "GET", body: RequestBody? = null) = Request.Builder()
        .url("${Applications.API}/connections$path?user_id=${Uri.encode(user())}")
        .header("X-Ren-Token", token())
        .method(method, body)
        .build()

    /** ren's answer to [request]: its body, or why it failed. */
    private suspend fun call(request: Request): Result<String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) Result.success(text)
                else Result.failure(IOException(runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty().ifBlank { "ren answered ${response.code}" }))
            }
        } catch (error: IOException) {
            Result.failure(error)
        }
    }

    /** Lists the accounts again; the error's message when ren can't be reached. */
    public suspend fun refresh(): String? = call(request("")).map(::parse).fold({ _list.value = it; null }, { it.message })

    /** The provider's sign-in page for [name]; blank when the user is signed in already. */
    public suspend fun start(name: String): Result<String> =
        call(request("/$name/start", "POST", EMPTY)).map { JSONObject(it).optString("url") }

    /** Keeps [value], a token the user made at [name], as their sign-in there; the error's message when it can't. */
    public suspend fun saveToken(name: String, value: String): String? =
        call(request("/$name/token", "POST", JSONObject().put("token", value).toString().toRequestBody(JSON))).exceptionOrNull()?.message
            ?: refresh()

    /** Forgets the user's sign-in at [name]; the error's message when it can't. */
    public suspend fun disconnect(name: String): String? = call(request("/$name", "DELETE")).exceptionOrNull()?.message ?: refresh()

    public companion object {
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        public fun parse(text: String): List<Connection> {
            val rows = JSONArray(text)
            return (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                Connection(row.getString("name"), row.getString("label"), row.getBoolean("connected"), row.getBoolean("oauth"), row.optString("token_hint"))
            }
        }
    }
}
