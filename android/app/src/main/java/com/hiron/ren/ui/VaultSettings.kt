package com.hiron.ren.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiron.ren.core.Vault
import com.hiron.ren.core.VaultItem
import com.hiron.ren.core.VaultKind

/**
 * Settings › Vault: the user's logins, cards, bank accounts and API tokens. Rows show what the agents see (a name, a
 * kind and the sites); opening one to see or change its values takes biometrics or the screen lock.
 */
@Composable
internal fun VaultSettings(vault: Vault) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val items by vault.items.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<VaultItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    RenCard(padding = PaddingValues(0.dp)) {
        (items ?: runCatching(vault::all).getOrElse { error = it.message; emptyList() }).forEach { item ->
            Column(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button) {
                    val activity = context as? FragmentActivity ?: return@clickable
                    authenticate(activity, onOk = { editing = item }, onError = { error = it }, title = "Open ${item.name}")
                }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(item.name, style = Type.bodyLg, color = p.ink)
                Text(
                    listOf(item.kind.label, item.sites.joinToString().ifEmpty { "No sites yet" }, if (item.ask) "Asks you first" else "Always allowed").joinToString(" · "),
                    style = Type.cardBody, color = p.ink2,
                )
            }
            Rule(Modifier.padding(horizontal = 18.dp), color = p.line)
        }
        Row(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Keycap("Add to the vault", { editing = VaultItem.new(VaultKind.Login) }, tone = KeyTone.Accent)
        }
    }
    error?.let { InfoStrip(it, icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
    InfoStrip(
        "The values stay on this phone, sealed with a key the Android Keystore keeps. Agents see names and sites, never values: the phone types or sends a value itself, only to the item's sites.",
    )
    editing?.let { item -> VaultEditor(item, isNew = items.orEmpty().none { it.id == item.id }, vault) { editing = null } }
}

/** One item's editor: its kind (while new), name, sites, fields and whether using it asks first. */
@Composable
private fun VaultEditor(start: VaultItem, isNew: Boolean, vault: Vault, onDone: () -> Unit) {
    val p = LocalPalette.current
    var kind by remember { mutableStateOf(start.kind) }
    var name by remember { mutableStateOf(start.name) }
    var sites by remember { mutableStateOf(start.sites.joinToString(", ")) }
    val fields = remember { mutableStateListOf(*start.fields.toTypedArray()) }
    var ask by remember { mutableStateOf(start.ask) }
    var shown by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val save = {
        val domains = sites.split(',', ' ', '\n').mapNotNull(VaultItem::site).distinct()
        error = when {
            name.isBlank() -> "Give it a name the agents can ask for."
            domains.isEmpty() -> "Add the site it's for, like chase.com."
            else -> null
        }
        if (error == null) {
            runCatching { vault.save(VaultItem(start.id, name.trim(), kind, domains, fields.filter { it.first.isNotBlank() }, ask)) }
                .onSuccess { onDone() }.onFailure { error = it.message }
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = p.card,
        title = { Text(if (isNew) "Add to the vault" else start.name, style = Type.headline, color = p.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isNew) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VaultKind.entries.forEach { k ->
                            FilterChip(k.label, k == kind, {
                                kind = k
                                fields.clear()
                                fields.addAll(k.fields.map { it to "" })
                            })
                        }
                    }
                }
                RecessedField(name, { name = it }, label = "Name", placeholder = "Chase checking")
                RecessedField(sites, { sites = it }, label = "Sites", placeholder = "chase.com, api.github.com")
                fields.forEachIndexed { i, (field, value) ->
                    if (field in kind.fields) {
                        RecessedField(value, { fields[i] = field to it }, label = field.replaceFirstChar(Char::uppercase),
                            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation())
                    } else {
                        RecessedField(field, { fields[i] = it to value }, label = "Field", placeholder = "pin")
                        RecessedField(value, { fields[i] = field to it }, visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextKey("Add a field", { fields.add("" to "") })
                    TextKey(if (shown) "Hide values" else "Show values", { shown = !shown })
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(ask, role = Role.Switch) { ask = it }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ask me before each use", style = Type.bodyLg, color = p.ink, modifier = Modifier.weight(1f))
                    RenToggle(ask, null)
                }
                error?.let { Text(it, style = Type.labelMd, color = p.danger) }
            }
        },
        confirmButton = { TextButton(onClick = save) { Text("Save", style = Type.button, color = p.accent) } },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { runCatching { vault.delete(start.id) }.onSuccess { onDone() }.onFailure { error = it.message } }) {
                        Text("Delete", style = Type.button, color = p.danger)
                    }
                }
                TextButton(onClick = onDone) { Text("Cancel", style = Type.button, color = p.ink2) }
            }
        },
    )
}
