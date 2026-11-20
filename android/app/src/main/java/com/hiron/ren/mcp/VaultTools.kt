package com.hiron.ren.mcp

import com.hiron.ren.core.Vault
import com.hiron.ren.core.VaultItem
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.error
import io.modelcontextprotocol.kotlin.sdk.types.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The vault as tools, the way strands_tools' `http_request` uses a token: the agent names a credential, and the tool
 * resolves its value itself and sends it only to the item's sites (`HTTP_REQUEST_TOKEN_CONFIG`). As Strands Shell
 * does (https://strandsagents.com/docs/user-guide/shell/security/index.md), the value goes on the original request
 * only, never on a redirect. Each use waits for [consent].
 */
internal class VaultTools(private val vault: Vault, http: OkHttpClient, private val consent: suspend (VaultItem, String) -> Boolean) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    val tools: List<Tool> = listOf(
        Tool(
            name = "vault_list",
            description = "The user's vault on their phone: their logins, cards, bank accounts and API tokens, each with its name, kind, the " +
                "sites it may be used on and its field names, never a value. Use an item by its name: vault_request sends it with an " +
                "HTTPS request, and browser_type types it into a page. You never see the values, and the phone may ask the user first.",
            inputSchema = ToolSchema(),
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        Tool(
            name = "vault_request",
            description = "Send an HTTPS request from the user's phone with a credential from their vault, as http_request does with a token. " +
                "The phone adds the value itself, only for an address on one of the item's sites, and doesn't follow redirects. Returns " +
                "the status, the content type, Location on a redirect, and the body with any vault value taken out.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("url", str("The https address"))
                    put("item", str("The vault item's name from vault_list"))
                    put("field", str("The item's field to send; the item's only field, or password for basic, when left out"))
                    put("auth", enum("How the value goes: bearer (Authorization: Bearer, the default), header (the header named in name), query (the query parameter named in name) or basic (the item's username and password)", "bearer", "header", "query", "basic"))
                    put("name", str("The header or query parameter's name, for header and query"))
                    put("method", enum("The HTTP method, GET by default", "GET", "POST", "PUT", "PATCH", "DELETE"))
                    put("headers", buildJsonObject { put("type", "array"); put("description", "More request headers, each as Name: value"); put("items", buildJsonObject { put("type", "string") }) })
                    put("body", str("The request body"))
                    put("content_type", str("The body's content type, application/json by default"))
                },
                required = listOf("url", "item"),
            ),
            annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = true),
        ),
    )

    suspend fun call(name: String, input: JSONObject): CallToolResult =
        runCatching { if (name == "vault_list") CallToolResult.success(vault.listing().toString()) else request(input) }
            .getOrElse { CallToolResult.error(it.message ?: "The vault couldn't do that.") }

    private suspend fun request(input: JSONObject): CallToolResult {
        val url = input.getString("url").toHttpUrlOrNull() ?: error("That isn't an address.")
        val item = vault.find(input.getString("item")) ?: error("No vault item ${input.getString("item")}. Call vault_list for the items.")
        if (!item.allows(url.toString())) error("${item.name} may only be sent over https to ${item.sites.joinToString().ifEmpty { "no site yet" }}.")
        val auth = input.optString("auth").ifEmpty { "bearer" }
        val field = input.optString("field").ifEmpty { if (auth == "basic") "password" else item.fields.singleOrNull()?.first.orEmpty() }
        val value = item.value(field) ?: error("${item.name} has no field \"$field\"; it has ${item.fields.joinToString { it.first }}.")
        val method = input.optString("method").ifEmpty { "GET" }
        if (!consent(item, "Send ${item.name} to ${url.host}: $method ${url.encodedPath}")) error("The user didn't allow it.")

        val builder = Request.Builder()
        input.optJSONArray("headers")?.let { list ->
            (0 until list.length()).map(list::getString).forEach { line ->
                builder.header(line.substringBefore(':').trim(), line.substringAfter(':', "").trim())
            }
        }
        val name = input.optString("name")
        val target = when (auth) {
            "bearer" -> url.also { builder.header("Authorization", "Bearer $value") }
            "basic" -> url.also { builder.header("Authorization", Credentials.basic(item.value("username").orEmpty(), value)) }
            "header" -> url.also { builder.header(name.ifEmpty { error("Give the header's name in name.") }, value) }
            "query" -> url.newBuilder().addQueryParameter(name.ifEmpty { error("Give the query parameter's name in name.") }, value).build()
            else -> error("auth is bearer, header, query or basic.")
        }
        val body = input.optString("body").takeIf { input.has("body") }?.toRequestBody(null)
        builder.url(target).method(method, body ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(null) else null)
        if (body != null) builder.header("Content-Type", input.optString("content_type").ifEmpty { "application/json" })

        return withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val answer = JSONObject().put("status", response.code).put("content_type", response.header("Content-Type"))
                    .put("location", response.header("Location")).put("body", text.take(BODY_LIMIT)).put("truncated", text.length > BODY_LIMIT)
                CallToolResult.success(answer.toString())
            }
        }
    }

    private fun str(description: String): JsonObject = buildJsonObject { put("type", "string"); put("description", description) }
    private fun enum(description: String, vararg values: String): JsonObject =
        buildJsonObject { put("type", "string"); put("description", description); putJsonArray("enum") { values.forEach { add(it) } } }

    private companion object {
        const val BODY_LIMIT = 20_000
    }
}
