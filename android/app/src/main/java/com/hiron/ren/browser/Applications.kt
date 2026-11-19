package com.hiron.ren.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** An app OpenCode built for the ren account: a web app at its https [location], or an Android app whose APK ren serves. */
public data class RenApplication(val id: String, val name: String, val android: Boolean, val location: String, val icon: String)

/**
 * The ren account's Applications (ren's `GET /context/applications`, which the Context Agent's `register_application`
 * fills): Home lists them; a web app opens in a tab, an Android app's APK downloads and Android's installer opens on it.
 */
public class Applications(private val context: Context, private val http: OkHttpClient, private val token: () -> String) {
    private val _list = MutableStateFlow<List<RenApplication>>(emptyList())
    public val list: StateFlow<List<RenApplication>> = _list.asStateFlow()

    /** The signed-in ren account's id, as the pages report it; blank when signed out. */
    public var userId: String = ""
        set(value) {
            if (value != field) _list.value = emptyList()
            field = value
        }

    private fun request(path: String) = Request.Builder()
        .url("$API/context/applications$path?user_id=${Uri.encode(userId)}")
        .header("X-Ren-Token", token())
        .build()

    public suspend fun refresh() {
        if (userId.isBlank()) return
        val user = userId
        val listed = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(request("")).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    parse(response.body?.string().orEmpty())
                }
            }.getOrNull()
        }
        if (listed != null && user == userId) _list.value = listed
    }

    /** Downloads [app]'s APK and hands it to Android's installer; the error's message when it can't. */
    public suspend fun install(app: RenApplication): String? = withContext(Dispatchers.IO) {
        val apk = File(context.cacheDir, "apps/${app.id}.apk")
        try {
            http.newCall(request("/${app.id}/apk")).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) throw IOException("ren answered ${response.code} for ${app.name}")
                apk.parentFile?.mkdirs()
                apk.outputStream().use { body.byteStream().copyTo(it) }
            }
        } catch (error: IOException) {
            return@withContext error.message ?: "${app.name} didn't download"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.apps", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    }

    public companion object {
        /** ren desktop's backend on the workspace VM, which the app's pages call too (`VITE_API_BASE_URL`). */
        public const val API: String = "https://rivet.ron-ai.dev"
        private const val APK = "application/vnd.android.package-archive"

        public fun parse(json: String): List<RenApplication> {
            val items = JSONObject(json).optJSONArray("applications") ?: return emptyList()
            return (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val kind = item.optString("kind")
                if (kind != "web" && kind != "android") return@mapNotNull null
                RenApplication(item.optString("id"), item.optString("name"), kind == "android", item.optString("location"), item.optString("icon"))
                    .takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
            }
        }
    }
}
