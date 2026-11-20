package com.hiron.ren.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What a vault item is; [fields] are the fields a new one starts with. */
public enum class VaultKind(public val wire: String, public val label: String, public val fields: List<String>) {
    Login("login", "Login", listOf("username", "password")),
    Card("card", "Card", listOf("name", "number", "expiry", "cvc")),
    Bank("bank", "Bank account", listOf("holder", "routing", "account")),
    Token("token", "API token", listOf("token")),
    Other("other", "Other", listOf("value")),
    ;

    public companion object {
        public fun of(wire: String?): VaultKind = entries.firstOrNull { it.wire == wire } ?: Other
    }
}

/**
 * One saved secret. [sites] are the domains its values may go to (a domain covers its subdomains), as
 * `HTTP_REQUEST_TOKEN_CONFIG` binds a token to its domains in strands_tools' `http_request`. [ask] makes every use wait
 * for the user's OK.
 */
public data class VaultItem(
    val id: String,
    val name: String,
    val kind: VaultKind,
    val sites: List<String>,
    val fields: List<Pair<String, String>>,
    val ask: Boolean = true,
) {
    public fun value(field: String): String? = fields.firstOrNull { it.first.equals(field, ignoreCase = true) }?.second

    /** Whether [url] is an https address on one of [sites]. */
    public fun allows(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && sites.any { host == it || host.endsWith(".$it") }
    }

    public companion object {
        public fun new(kind: VaultKind): VaultItem = VaultItem(UUID.randomUUID().toString(), "", kind, emptyList(), kind.fields.map { it to "" })

        /** A site as the user types it (`https://www.chase.com/login`, `Chase.com`) as the domain it is kept as. */
        public fun site(text: String): String? {
            val trimmed = text.trim().lowercase().removePrefix("*.")
            if (trimmed.isEmpty()) return null
            val host = runCatching { URI(if ("://" in trimmed) trimmed else "https://$trimmed").host }.getOrNull() ?: return null
            return host.trimEnd('.').takeIf { '.' in it }
        }
    }
}

/** Seals and opens the vault's file. */
public interface VaultCipher {
    public fun seal(plain: ByteArray): ByteArray
    public fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM under a key the Android Keystore makes and keeps: its material never enters the app's process and can't be
 * taken off the phone (https://developer.android.com/privacy-and-security/keystore). The sealed bytes are the IV, then
 * the ciphertext.
 */
public class KeystoreCipher(private val alias: String = "ren_vault") : VaultCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV)) }
        return cipher.doFinal(sealed, IV, sealed.size - IV)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV = 12
    }
}

/**
 * The user's vault: logins, cards, bank accounts, API tokens and anything else, sealed in [file] by [cipher]. Agents
 * see items by name with their kind, sites and field names ([listing]), never a value: a tool resolves a value
 * itself and uses it only on the item's sites, and [scrub] takes every value out of what a tool answers.
 */
public class Vault(private val file: File, private val cipher: VaultCipher) {
    private val _items = MutableStateFlow<List<VaultItem>?>(null)

    public val items: StateFlow<List<VaultItem>?> = _items.asStateFlow()

    /** The items, opened from [file] the first time they are needed. */
    @Synchronized
    public fun all(): List<VaultItem> = _items.value ?: load().also { _items.value = it }

    /** The item an agent names, by its name or id. */
    public fun find(name: String): VaultItem? = all().let { items ->
        items.firstOrNull { it.id == name } ?: items.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }

    @Synchronized
    public fun save(item: VaultItem): Unit = write(all().filterNot { it.id == item.id } + item)

    @Synchronized
    public fun delete(id: String): Unit = write(all().filterNot { it.id == id })

    /** The user's "Always allow" for [id]: agents use it without asking from now on. */
    @Synchronized
    public fun allow(id: String): Unit = write(all().map { if (it.id == id) it.copy(ask = false) else it })

    /** What agents see: each item's name, kind, sites and field names, and whether using it asks the user. */
    public fun listing(): JSONObject = JSONObject().put(
        "items",
        JSONArray(
            all().map { item ->
                JSONObject().put("name", item.name).put("kind", item.kind.wire).put("sites", JSONArray(item.sites))
                    .put("fields", JSONArray(item.fields.map { it.first })).put("asks_first", item.ask)
            },
        ),
    )

    /**
     * [text] with every vault value of [MIN_SCRUB] characters or more replaced by `[vault: item › field]`. A value of
     * digits (a card or account number) is found with spaces or dashes between its digits too, as pages write them.
     */
    public fun scrub(text: String): String {
        if (_items.value == null && !file.exists()) return text
        return all().fold(text) { out, item ->
            item.fields.fold(out) { acc, (field, value) ->
                val bare = value.filterNot { it == ' ' || it == '-' }
                when {
                    value.length < MIN_SCRUB -> acc
                    bare.length >= MIN_SCRUB && bare.all(Char::isDigit) ->
                        Regex(bare.toList().joinToString("[ -]?")).replace(acc, "[vault: ${item.name} › $field]")
                    else -> acc.replace(value, "[vault: ${item.name} › $field]")
                }
            }
        }
    }

    private fun load(): List<VaultItem> {
        if (!file.exists()) return emptyList()
        val rows = JSONArray(String(cipher.open(file.readBytes())))
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val sites = row.getJSONArray("sites")
            val fields = row.getJSONArray("fields")
            VaultItem(
                row.getString("id"), row.getString("name"), VaultKind.of(row.getString("kind")),
                (0 until sites.length()).map(sites::getString),
                (0 until fields.length()).map { fields.getJSONArray(it).let { f -> f.getString(0) to f.getString(1) } },
                row.optBoolean("ask", true),
            )
        }
    }

    private fun write(items: List<VaultItem>) {
        val rows = JSONArray(
            items.map { item ->
                JSONObject().put("id", item.id).put("name", item.name).put("kind", item.kind.wire).put("sites", JSONArray(item.sites))
                    .put("fields", JSONArray(item.fields.map { JSONArray(listOf(it.first, it.second)) })).put("ask", item.ask)
            },
        )
        val next = File(file.path + ".new")
        next.writeBytes(cipher.seal(rows.toString().toByteArray()))
        check(next.renameTo(file)) { "Couldn't save the vault." }
        _items.value = items
    }

    public companion object {
        public const val MIN_SCRUB: Int = 5
    }
}
