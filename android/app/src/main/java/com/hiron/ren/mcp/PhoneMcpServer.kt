package com.hiron.ren.mcp

import com.hiron.ren.browser.Browser
import com.hiron.ren.browser.SearchLevel
import com.hiron.ren.core.Vault
import com.hiron.ren.core.VaultItem
import com.hiron.ren.device.ToolResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.error
import io.modelcontextprotocol.kotlin.sdk.types.success
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Ren's phone as one MCP server: the phone tools, `request_approval`, the search tools, the browser's tools and the
 * vault's. The device tools go to [run], the same function the client tools use; `request_approval` goes to [approve]
 * and the search tools to [search] (served only with one), which answer what the agent is told or why the call failed;
 * the `browser_*` tools drive [browser], and they and the `vault_*` tools use [vault]'s items, each use after
 * [consent]. Every answer leaves with the vault's values taken out ([Vault.scrub]).
 * [version] is the app's version name, reported in `initialize`.
 */
public class PhoneMcpServer(
    private val version: String,
    private val browser: Browser,
    private val level: () -> SearchLevel,
    private val approve: suspend (JSONObject) -> Result<String>,
    private val search: (suspend (String, JSONObject) -> Result<String>)?,
    private val vault: Vault,
    private val consent: suspend (VaultItem, String) -> Boolean,
    private val http: OkHttpClient,
    private val run: suspend (String, JSONObject) -> ToolResult,
) {
    public fun build(): Server {
        val server = Server(
            serverInfo = Implementation(name = "ren-phone", version = version, title = "Ren phone"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        fun add(tool: Tool, handle: suspend (JSONObject) -> CallToolResult) = server.addTool(tool) { request -> scrubbed(handle(input(request))) }
        PhoneTools.all.forEach { tool -> add(tool) { answer(run(tool.name, it)) } }
        add(PhoneTools.approval) { answer(approve(it)) }
        search?.let { search -> PhoneTools.searches.forEach { tool -> add(tool) { answer(search(tool.name, it)) } } }
        val pages = BrowserTools(browser, level, vault, consent)
        pages.tools.forEach { tool -> add(tool) { pages.call(tool.name, it) } }
        val safe = VaultTools(vault, http, consent)
        safe.tools.forEach { tool -> add(tool) { safe.call(tool.name, it) } }
        return server
    }

    private fun scrubbed(result: CallToolResult): CallToolResult =
        result.copy(content = result.content.map { if (it is TextContent) TextContent(vault.scrub(it.text)) else it })

    public companion object {
        /** The MCP arguments as the JSON object [com.hiron.ren.device.DeviceTools] reads; both sides are plain JSON. */
        internal fun input(request: CallToolRequest): JSONObject = request.arguments?.let { JSONObject(it.toString()) } ?: JSONObject()

        /** A [ToolResult] as the SDK's result: the data as text on success, the message as an error otherwise. */
        internal fun answer(result: ToolResult): CallToolResult {
            val text = when (val data = result.data) {
                null, JSONObject.NULL -> ""
                else -> data.toString()
            }
            return if (result.ok) CallToolResult.success(text) else CallToolResult.error(text.ifBlank { "The action failed on the phone." })
        }

        /** What the agent is told as a success, or the failure's message as an error. */
        internal fun answer(result: Result<String>): CallToolResult =
            result.fold({ CallToolResult.success(it) }, { CallToolResult.error(it.message ?: "The action failed on the phone.") })

        /** The arguments as the map the client tools' handlers read, the way the ElevenLabs SDK hands them over. */
        public fun parameters(input: JSONObject): Map<String, Any> = input.keys().asSequence().associateWith { input.get(it) }
    }
}
