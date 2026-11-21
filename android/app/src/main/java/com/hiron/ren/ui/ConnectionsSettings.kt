package com.hiron.ren.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiron.ren.browser.Browser
import com.hiron.ren.core.Connection
import com.hiron.ren.core.Connections
import kotlinx.coroutines.launch

/**
 * Settings › Connections: the user's Vercel, Netlify, GitHub, GitLab, Supabase and Bitbucket accounts, which every
 * agent can use once connected. Sign in opens the provider's own page; Use a token keeps a token made there.
 */
@Composable
internal fun ConnectionsSettings(connections: Connections, browser: Browser, refresh: Int) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val list by connections.list.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf<Connection?>(null) }
    // Coming back from the provider's page resumes Settings, which lists the accounts again.
    LaunchedEffect(refresh) { error = connections.refresh() }
    RenCard(padding = PaddingValues(0.dp)) {
        list.forEach { account ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(account.label, style = Type.bodyLg, color = p.ink)
                Text(if (account.connected) "Connected" else "Not connected", style = Type.cardBody, color = p.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (account.connected) {
                        Keycap("Disconnect", { scope.launch { error = connections.disconnect(account.name) } })
                    } else {
                        if (account.oauth) {
                            Keycap("Sign in", {
                                scope.launch {
                                    connections.start(account.name).fold(
                                        { url -> if (url.isBlank()) error = connections.refresh() else browser.signInPage(url, incognito = false) },
                                        { error = it.message },
                                    )
                                }
                            }, tone = KeyTone.Accent)
                        }
                        if (account.tokenHint.isNotBlank()) Keycap("Use a token", { typing = account })
                    }
                }
            }
            Rule(Modifier.padding(horizontal = 18.dp), color = p.line)
        }
    }
    error?.let { InfoStrip(it, icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
    InfoStrip(
        "Every agent in Ren can use the accounts you connect: to push code, open pull requests, change a database or deploy. Your sign-ins stay on Ren's server and no agent ever sees them. Anything that changes something asks you here first, showing exactly what it will do.",
    )
    typing?.let { account -> TokenDialog(account, onDone = { typing = null }) { value -> connections.saveToken(account.name, value) } }
}

/** A token for [account], kept as the user's sign-in there; [save] answers why it failed, or null. */
@Composable
private fun TokenDialog(account: Connection, onDone: () -> Unit, save: suspend (String) -> String?) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = p.card,
        title = { Text("${account.label} token", style = Type.headline, color = p.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(account.tokenHint, style = Type.cardBody, color = p.ink2)
                RecessedField(value, { value = it }, label = "Token", visualTransformation = PasswordVisualTransformation())
                error?.let { Text(it, style = Type.labelMd, color = p.danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { error = save(value.trim()); if (error == null) onDone() } }) {
                Text("Save", style = Type.button, color = p.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", style = Type.button, color = p.ink2) } },
    )
}
