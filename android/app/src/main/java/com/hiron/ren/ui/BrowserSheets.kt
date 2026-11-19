package com.hiron.ren.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiron.ren.browser.Addresses
import com.hiron.ren.browser.Browser
import com.hiron.ren.browser.BrowserTab
import com.hiron.ren.browser.ClearDataOptions
import com.hiron.ren.browser.ContextMenuTarget
import com.hiron.ren.browser.Favorites
import com.hiron.ren.browser.FindState
import com.hiron.ren.browser.HttpAuthPrompt
import com.hiron.ren.browser.JsDialogKind
import com.hiron.ren.browser.JsDialogPrompt
import com.hiron.ren.browser.Omnibox
import com.hiron.ren.browser.OmniboxSuggestion
import com.hiron.ren.browser.PageError
import com.hiron.ren.browser.SitePermissionKind
import com.hiron.ren.browser.SitePermissionPrompt
import com.hiron.ren.browser.SitePermissionsManager
import com.hiron.ren.browser.SslWarning
import com.hiron.ren.browser.SuggestionKind
import com.hiron.ren.core.RenRuntime

internal fun readClipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0)?.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
}

internal fun copyToClipboard(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

internal fun shareLink(context: Context, url: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
    if (!subject.isNullOrBlank()) {
        send.putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * Local suggestions and current-page quick actions laid below the toolbar while the address bar is edited.
 */
@Composable
internal fun OmniboxDropdown(
    tab: BrowserTab,
    tabs: List<BrowserTab>,
    typedText: String,
    runtime: RenRuntime,
    onGo: (String) -> Unit,
    onSelectTab: (String) -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val favorites by runtime.favorites.list.collectAsStateWithLifecycle()
    val historyEntries by runtime.history.entries.collectAsStateWithLifecycle()
    val clipboard = remember(tab.id, tab.incognito) {
        if (tab.incognito) null else readClipboardText(context)
    }
    val suggestions = remember(typedText, tabs, favorites, historyEntries, clipboard, tab.id) {
        Omnibox.suggest(
            query = typedText,
            tabs = tabs,
            favorites = favorites,
            history = historyEntries,
            clipboardText = clipboard,
            currentTabId = tab.id,
        )
    }
    if (tab.home && suggestions.isEmpty()) return

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .background(p.raised),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!tab.home) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.card))
                        .background(p.card)
                        .border(1.dp, p.line, RoundedCornerShape(Radius.card))
                        .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Public, contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tab.label, style = Type.cardTitle, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tab.url, style = Type.mono, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(6.dp))
                    IconKey(
                        icon = Icons.Outlined.ContentCopy,
                        description = "Copy",
                        onClick = { copyToClipboard(context, "Address", tab.url) },
                        size = 40.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                    IconKey(
                        icon = Icons.Outlined.Share,
                        description = "Share",
                        onClick = { shareLink(context, tab.url, tab.title) },
                        size = 40.dp,
                    )
                }
                if (suggestions.isNotEmpty()) {
                    Rule(Modifier.padding(vertical = 4.dp), color = p.line)
                }
            }
            for (suggestion in suggestions) {
                OmniboxRow(
                    suggestion = suggestion,
                    onClick = {
                        val targetTabId = suggestion.tabId
                        if (suggestion.kind == SuggestionKind.OpenTab && targetTabId != null) {
                            onSelectTab(targetTabId)
                        } else {
                            onGo(suggestion.url)
                        }
                    },
                )
            }
        }
        Rule(color = p.line)
    }
}

@Composable
private fun OmniboxRow(suggestion: OmniboxSuggestion, onClick: () -> Unit) {
    val p = LocalPalette.current
    val icon = when (suggestion.kind) {
        SuggestionKind.Clipboard -> Icons.Outlined.ContentPaste
        SuggestionKind.OpenTab -> Icons.Outlined.Tab
        SuggestionKind.Favorite -> Icons.Outlined.BookmarkBorder
        SuggestionKind.History -> Icons.Outlined.History
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.control))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(suggestion.title, style = Type.cardTitle, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(suggestion.url, style = Type.mono, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (suggestion.kind == SuggestionKind.OpenTab) {
            Spacer(Modifier.width(8.dp))
            StatusTag("Switch", Tone.Accent, outline = true)
        }
    }
}

/**
 * Compact bar below the toolbar for searching text inside the active tab's page.
 */
@Composable
internal fun FindInPageBar(
    findState: FindState,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf(findState.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
    }
    LaunchedEffect(findState.query) {
        if (findState.query != query) {
            query = findState.query
        }
    }
    val shape = RoundedCornerShape(Radius.control)
    val hasMatches = findState.totalMatches > 0

    Column(Modifier.fillMaxWidth().background(p.chrome)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { next ->
                    query = next
                    onQueryChange(next)
                },
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                textStyle = Type.body.copy(color = p.ink),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { if (hasMatches) onNext() }),
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .height(34.dp)
                            .clip(shape)
                            .background(p.bg)
                            .border(1.dp, p.line, shape)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text("Find in page", style = Type.body, color = p.muted, maxLines = 1)
                            }
                            inner()
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${findState.currentMatch}/${findState.totalMatches}",
                            style = Type.mono,
                            color = p.ink2,
                            maxLines = 1,
                        )
                    }
                },
            )
            FindStepKey(
                icon = Icons.Outlined.KeyboardArrowUp,
                description = "Previous match",
                enabled = hasMatches,
                onClick = onPrevious,
            )
            FindStepKey(
                icon = Icons.Outlined.KeyboardArrowDown,
                description = "Next match",
                enabled = hasMatches,
                onClick = onNext,
            )
            FindStepKey(
                icon = Icons.Outlined.Close,
                description = "Close find",
                enabled = true,
                onClick = onClose,
            )
        }
        Rule(color = p.line)
    }
}

@Composable
private fun FindStepKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radius.control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) p.ink else p.dot, modifier = Modifier.size(18.dp))
    }
}

/**
 * Full-bleed Ink & Volt error card shown when the active tab's main-frame navigation fails.
 */
@Composable
internal fun PageErrorCard(
    error: PageError,
    canGoBack: Boolean,
    onRetry: () -> Unit,
    onSearchWithRen: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val hostLookup = error.code == WebViewClient.ERROR_HOST_LOOKUP ||
        error.description.contains("NAME_NOT_RESOLVED", ignoreCase = true) ||
        error.description.contains("ADDRESS_UNREACHABLE", ignoreCase = true)
    val headline = if (hostLookup) "Can't reach this site" else "Page didn't load"

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Eyebrow("Connection error", tone = Tone.Red)
        Text(headline, style = Type.headline, color = p.ink)
        RenCard(padding = PaddingValues(16.dp)) {
            Text(error.failingUrl, style = Type.mono, color = p.ink)
            Spacer(Modifier.height(8.dp))
            Text(
                error.description.ifBlank { "Error code ${error.code}" },
                style = Type.mono,
                color = p.muted,
            )
        }
        Spacer(Modifier.height(4.dp))
        PrimaryKey("Retry", onRetry, trailingArrow = false)
        SecondaryKey("Search with Ren", onSearchWithRen, leading = Icons.Outlined.Search)
        if (canGoBack) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextKey("Back", onBack, leading = Icons.AutoMirrored.Outlined.ArrowBack)
            }
        }
    }
}

/**
 * Full-bleed Ink & Volt TLS warning card shown when a certificate check fails on the active tab.
 */
@Composable
internal fun SslWarningCard(
    warning: SslWarning,
    onBackToSafety: () -> Unit,
    onProceed: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Eyebrow("Security warning", tone = Tone.Red)
        Text("Connection isn't private", style = Type.headline, color = p.ink)
        Text(
            "Android couldn't verify this site's TLS certificate. Attackers on the network might be trying to read or alter information sent to this host.",
            style = Type.body,
            color = p.ink2,
        )
        RenCard(padding = PaddingValues(16.dp)) {
            Readout("Host", warning.host.ifBlank { warning.url }, mono = true)
            Spacer(Modifier.height(10.dp))
            Rule(color = p.line)
            Spacer(Modifier.height(10.dp))
            Readout(
                "Error",
                "${sslErrorLabel(warning.primaryError)} (${warning.primaryError})",
                valueColor = p.danger,
                mono = true,
            )
        }
        Spacer(Modifier.height(4.dp))
        PrimaryKey("Back to safety", onBackToSafety, trailingArrow = false)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalPalette provides p.copy(ink = p.danger, ink2 = p.danger)) {
                TextKey("Proceed anyway (unsafe)", onProceed)
            }
        }
    }
}

private fun sslErrorLabel(primaryError: Int): String = when (primaryError) {
    SslError.SSL_NOTYETVALID -> "CERT_NOT_YET_VALID"
    SslError.SSL_EXPIRED -> "CERT_EXPIRED"
    SslError.SSL_IDMISMATCH -> "CERT_COMMON_NAME_INVALID"
    SslError.SSL_UNTRUSTED -> "CERT_AUTHORITY_INVALID"
    SslError.SSL_DATE_INVALID -> "CERT_DATE_INVALID"
    else -> "CERT_INVALID"
}

/**
 * Sheet for long-pressed links and images inside a tab's page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextMenuSheet(
    target: ContextMenuTarget,
    tab: BrowserTab,
    browser: Browser,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val linkUrl = target.linkUrl?.takeIf(String::isNotBlank)
    val imageUrl = target.imageUrl?.takeIf(String::isNotBlank)
    val headerUrl = linkUrl ?: imageUrl ?: return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.raised,
        contentColor = p.ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 16.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val linkTitle = target.linkText?.trim()?.takeIf(String::isNotEmpty)
                if (linkTitle != null) {
                    Text(linkTitle, style = Type.title, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(headerUrl, style = Type.mono, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Rule(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), color = p.line)
            if (linkUrl != null) {
                SheetActionRow(Icons.Outlined.Add, "Open in new tab") {
                    val _ = browser.open(linkUrl, select = true, incognito = tab.incognito)
                    onDismiss()
                }
                SheetActionRow(Icons.Outlined.Tab, "Open in background tab") {
                    val _ = browser.open(linkUrl, select = false, incognito = tab.incognito)
                    onDismiss()
                }
                SheetActionRow(Icons.Outlined.VisibilityOff, "Open in private tab") {
                    val _ = browser.open(linkUrl, select = true, incognito = true)
                    onDismiss()
                }
                SheetActionRow(Icons.Outlined.ContentCopy, "Copy link") {
                    copyToClipboard(context, "Link", linkUrl)
                    onDismiss()
                }
                SheetActionRow(Icons.Outlined.Share, "Share link") {
                    onDismiss()
                    shareLink(context, linkUrl, target.linkText)
                }
                SheetActionRow(Icons.Outlined.Download, "Download link") {
                    onDismiss()
                    browser.saveUrl(linkUrl)
                }
            }
            if (linkUrl != null && imageUrl != null) {
                Rule(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), color = p.line)
            }
            if (imageUrl != null) {
                SheetActionRow(Icons.Outlined.Image, "Open image in new tab") {
                    val _ = browser.open(imageUrl, select = true, incognito = tab.incognito)
                    onDismiss()
                }
                SheetActionRow(Icons.Outlined.Download, "Download image") {
                    onDismiss()
                    browser.saveUrl(imageUrl)
                }
                SheetActionRow(Icons.Outlined.Link, "Copy image address") {
                    copyToClipboard(context, "Image address", imageUrl)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(Radius.control))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = p.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = Type.body.copy(fontWeight = Type.labelLg.fontWeight), color = p.ink)
    }
}

/**
 * Searchable browsing history sheet with closed-tab restore, per-row delete and full clear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySheet(
    browser: Browser,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    val allEntries by browser.history.entries.collectAsStateWithLifecycle()
    val closedTabs by browser.history.closedTabs.collectAsStateWithLifecycle()
    val filtered = remember(query, allEntries) {
        browser.history.search(query, limit = 100)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.raised,
        contentColor = p.ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("History", Modifier.weight(1f))
                if (allEntries.isNotEmpty()) {
                    Keycap("Clear history", { browser.history.clearAll() })
                }
            }
            if (closedTabs.isNotEmpty()) {
                SecondaryKey(
                    text = "Reopen closed tab",
                    onClick = {
                        val _ = browser.reopenClosedTab()
                        onDismiss()
                    },
                    leading = Icons.Outlined.Restore,
                )
            }
            RecessedField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search history",
            )
            if (filtered.isEmpty()) {
                Text(
                    if (allEntries.isEmpty()) "No browsing history yet." else "No matching pages.",
                    style = Type.cardBody,
                    color = p.muted,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        val shape = RoundedCornerShape(Radius.card)
                        val title = entry.title.ifBlank { entry.host.ifBlank { entry.url } }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .clip(shape)
                                .background(p.card)
                                .border(1.dp, p.line, shape)
                                .clickable(role = Role.Button) {
                                    onOpenUrl(entry.url)
                                    onDismiss()
                                }
                                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = Type.cardTitle, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    Addresses.resting(entry.url).ifEmpty { entry.url },
                                    style = Type.mono,
                                    color = p.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove $title",
                                tint = p.ink2,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { browser.history.remove(entry.id) }
                                    .padding(10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bookmarks sheet backed by [Favorites]: tap to open, remove, add current page or add a custom address.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksSheet(
    tab: BrowserTab,
    favorites: Favorites,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val list by favorites.list.collectAsStateWithLifecycle()
    val normalizedCurrent = remember(tab.url, tab.home) {
        if (tab.home) null else Favorites.normalize(tab.url)
    }
    val currentSaved = normalizedCurrent != null && list.any { it.url == normalizedCurrent }
    var addingCustom by rememberSaveable { mutableStateOf(false) }
    var customTitle by rememberSaveable { mutableStateOf("") }
    var customUrl by rememberSaveable { mutableStateOf("") }
    var customError by rememberSaveable { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.raised,
        contentColor = p.ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Bookmarks", Modifier.weight(1f))
                Keycap("Add favorite", { addingCustom = !addingCustom }, icon = Icons.Outlined.Add)
            }
            if (normalizedCurrent != null && !currentSaved) {
                SecondaryKey(
                    text = "Add current page",
                    onClick = { val _ = favorites.add(tab.title, tab.url) },
                    leading = Icons.Outlined.BookmarkBorder,
                )
            }
            if (addingCustom) {
                RenCard(padding = PaddingValues(14.dp)) {
                    RecessedField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        placeholder = "Title (optional)",
                    )
                    Spacer(Modifier.height(8.dp))
                    RecessedField(
                        value = customUrl,
                        onValueChange = {
                            customUrl = it
                            customError = null
                        },
                        placeholder = "https://…",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    )
                    customError?.let { msg ->
                        Spacer(Modifier.height(6.dp))
                        Text(msg, style = Type.labelMd, color = p.danger)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryKey(
                            text = "Cancel",
                            onClick = {
                                addingCustom = false
                                customError = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        PrimaryKey(
                            text = "Save",
                            onClick = {
                                if (favorites.add(customTitle, customUrl) == null) {
                                    customError = "Enter a valid http(s) address."
                                } else {
                                    customTitle = ""
                                    customUrl = ""
                                    customError = null
                                    addingCustom = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            trailingArrow = false,
                        )
                    }
                }
            }
            if (list.isEmpty()) {
                Text(
                    "No bookmarks saved.",
                    style = Type.cardBody,
                    color = p.muted,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(list, key = { it.id }) { item ->
                        val shape = RoundedCornerShape(Radius.card)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .clip(shape)
                                .background(p.card)
                                .border(1.dp, p.line, shape)
                                .clickable(role = Role.Button) {
                                    onOpenUrl(item.url)
                                    onDismiss()
                                }
                                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = Type.cardTitle, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.url, style = Type.mono, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove ${item.title}",
                                tint = p.ink2,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { favorites.remove(item.id) }
                                    .padding(10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sheet asking whether to allow or block a site permission request on [SitePermissionPrompt.origin].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SitePermissionSheet(
    prompt: SitePermissionPrompt,
    sitePermissions: SitePermissionsManager,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var rememberDecision by remember(prompt.id) { mutableStateOf(!prompt.incognito) }

    ModalBottomSheet(
        onDismissRequest = { sitePermissions.dismissPrompt(prompt.id) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.raised,
        contentColor = p.ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Eyebrow("Site permission", tone = Tone.Amber)
            Text(
                "Allow ${prompt.origin} to use ${formatPermissionKinds(prompt.kinds)}?",
                style = Type.headline,
                color = p.ink,
            )
            if (!prompt.incognito) {
                Rule(color = p.line)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(rememberDecision, role = Role.Switch) { rememberDecision = it }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Remember for this site", style = Type.bodyLg, color = p.ink, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    RenToggle(rememberDecision, null)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryKey(
                    text = "Block",
                    onClick = {
                        sitePermissions.respond(
                            promptId = prompt.id,
                            allow = false,
                            remember = !prompt.incognito && rememberDecision,
                            scope = scope,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                PrimaryKey(
                    text = "Allow",
                    onClick = {
                        sitePermissions.respond(
                            promptId = prompt.id,
                            allow = true,
                            remember = !prompt.incognito && rememberDecision,
                            scope = scope,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    trailingArrow = false,
                )
            }
        }
    }
}

private fun formatPermissionKinds(kinds: List<SitePermissionKind>): String = when (kinds.size) {
    0 -> "device features"
    1 -> kinds[0].label
    2 -> "${kinds[0].label} and ${kinds[1].label}"
    else -> kinds.dropLast(1).joinToString(", ") { it.label } + ", and ${kinds.last().label}"
}

/**
 * Modal dialog for HTTP authentication challenges (`401` / `407`).
 */
@Composable
internal fun HttpAuthDialog(
    prompt: HttpAuthPrompt,
    onAnswer: (String, String?, String?) -> Unit,
) {
    val p = LocalPalette.current
    var username by remember(prompt.id) { mutableStateOf("") }
    var password by remember(prompt.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { onAnswer(prompt.id, null, null) },
        containerColor = p.raised,
        title = { Text("Sign in", style = Type.headline, color = p.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    buildString {
                        append(prompt.host)
                        if (prompt.realm.isNotBlank()) {
                            append(" · ")
                            append(prompt.realm)
                        }
                    },
                    style = Type.mono,
                    color = p.ink2,
                )
                RecessedField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Username",
                    placeholder = "Username",
                )
                RecessedField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    placeholder = "Password",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAnswer(prompt.id, username, password) }),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            Keycap("Sign in", { onAnswer(prompt.id, username, password) }, tone = KeyTone.Accent)
        },
        dismissButton = {
            Keycap("Cancel", { onAnswer(prompt.id, null, null) })
        },
    )
}

/**
 * Modal dialog for JavaScript `alert`, `confirm`, `prompt` and `beforeunload` requests.
 */
@Composable
internal fun JsDialogModal(
    prompt: JsDialogPrompt,
    onAnswer: (String, Boolean, String?) -> Unit,
) {
    val p = LocalPalette.current
    var input by remember(prompt.id) { mutableStateOf(prompt.defaultValue) }
    val host = remember(prompt.url) { Uri.parse(prompt.url).host?.takeIf(String::isNotEmpty) ?: prompt.url }
    val title = when (prompt.kind) {
        JsDialogKind.BeforeUnload -> "Leave this page?"
        JsDialogKind.Alert, JsDialogKind.Confirm, JsDialogKind.Prompt -> host
    }
    val confirmLabel = if (prompt.kind == JsDialogKind.BeforeUnload) "Leave page" else "OK"
    val dismissLabel = if (prompt.kind == JsDialogKind.BeforeUnload) "Stay" else "Cancel"

    AlertDialog(
        onDismissRequest = {
            val confirmed = prompt.kind == JsDialogKind.Alert
            onAnswer(prompt.id, confirmed, null)
        },
        containerColor = p.raised,
        title = { Text(title, style = Type.title, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (prompt.message.isNotBlank()) {
                    Text(prompt.message, style = Type.body, color = p.ink)
                }
                if (prompt.kind == JsDialogKind.Prompt) {
                    RecessedField(
                        value = input,
                        onValueChange = { input = it },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onAnswer(prompt.id, true, input) }),
                    )
                }
            }
        },
        confirmButton = {
            Keycap(
                text = confirmLabel,
                onClick = {
                    val value = if (prompt.kind == JsDialogKind.Prompt) input else null
                    onAnswer(prompt.id, true, value)
                },
                tone = KeyTone.Accent,
            )
        },
        dismissButton = {
            if (prompt.kind != JsDialogKind.Alert) {
                Keycap(dismissLabel, { onAnswer(prompt.id, false, null) })
            }
        },
    )
}

/**
 * Sheet for clearing browsing history, cookies/site storage, cached files and saved site permissions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClearBrowsingDataSheet(
    browser: Browser,
    onDismiss: () -> Unit,
    onCleared: () -> Unit = {},
) {
    val p = LocalPalette.current
    var history by rememberSaveable { mutableStateOf(true) }
    var cookies by rememberSaveable { mutableStateOf(true) }
    var cache by rememberSaveable { mutableStateOf(true) }
    var permissions by rememberSaveable { mutableStateOf(false) }
    val anySelected = history || cookies || cache || permissions

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.raised,
        contentColor = p.ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Eyebrow("Privacy", tone = Tone.Red)
            Text("Clear browsing data", style = Type.headline, color = p.ink)
            RenCard(padding = PaddingValues(0.dp)) {
                ClearDataOptionRow("History", history) { history = it }
                Rule(Modifier.padding(horizontal = 16.dp), color = p.line)
                ClearDataOptionRow("Cookies and site data", cookies) { cookies = it }
                Rule(Modifier.padding(horizontal = 16.dp), color = p.line)
                ClearDataOptionRow("Cached files", cache) { cache = it }
                Rule(Modifier.padding(horizontal = 16.dp), color = p.line)
                ClearDataOptionRow("Site permissions", permissions) { permissions = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryKey("Cancel", onDismiss, Modifier.weight(1f))
                CompositionLocalProvider(
                    LocalPalette provides p.copy(
                        accent = p.danger,
                        accentHi = p.danger,
                        accentInk = Color.White,
                    ),
                ) {
                    PrimaryKey(
                        text = "Clear data",
                        onClick = {
                            browser.clearBrowsingData(
                                ClearDataOptions(
                                    history = history,
                                    cookiesAndStorage = cookies,
                                    cache = cache,
                                    sitePermissions = permissions,
                                ),
                            ) {
                                onCleared()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = anySelected,
                        trailingArrow = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearDataOptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.bodyLg, color = p.ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        RenToggle(checked, null)
    }
}
