package com.hiron.ren.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiron.ren.R
import com.hiron.ren.browser.Addresses
import com.hiron.ren.browser.Browser
import com.hiron.ren.browser.BrowserTab
import com.hiron.ren.browser.DownloadRequest
import com.hiron.ren.browser.Favorites
import com.hiron.ren.core.RenRuntime
import kotlin.math.abs
import kotlin.math.roundToInt

public const val ADDRESS_TAG: String = "address"
public const val ASK_REN_TAG: String = "ask-ren"
public const val TABS_TAG: String = "tabs"
public const val MORE_TAG: String = "more"

/** The other screens the browser's menu opens. */
public enum class Page { Activity, Settings }

/**
 * The browser: the toolbar with the address bar and Ask Ren, the active tab (Ren's home page or a web page, or two tabs
 * in split view), and the bottom bar with back, forward, search, the tabs and the menu. The bottom bar steps aside while the keyboard is up.
 * Each new [searchRequests] (the search widget) puts the address bar in editing, with the keyboard up.
 */
@Composable
public fun BrowserScreen(runtime: RenRuntime, waiting: Int, searchRequests: Int = 0, onAskRen: () -> Unit, onOpen: (Page) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val browser = runtime.browser
    val state by browser.state.collectAsStateWithLifecycle()
    val tab = state.current ?: return
    var editing by rememberSaveable { mutableStateOf(false) }
    var addressValue by remember(tab.id) { mutableStateOf(initialAddressValue(tab)) }
    var tabs by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var historySheet by rememberSaveable { mutableStateOf(false) }
    var bookmarksSheet by rememberSaveable { mutableStateOf(false) }
    var split by rememberSaveable { mutableStateOf(listOf<String>()) }
    val panes = split.takeIf { ids ->
        tab.id in ids && ids.all { id -> state.tabs.any { it.id == id && !it.home } }
    }

    val findState by browser.findState.collectAsStateWithLifecycle()
    val contextMenu by browser.contextMenu.collectAsStateWithLifecycle()
    val contextAccount by runtime.contextWidget.account.collectAsStateWithLifecycle()
    val httpAuth by browser.httpAuth.collectAsStateWithLifecycle()
    val jsDialog by browser.jsDialog.collectAsStateWithLifecycle()
    val permissionPrompt by browser.sitePermissions.prompt.collectAsStateWithLifecycle()
    val download by browser.downloadRequest.collectAsStateWithLifecycle()
    val fullscreen by browser.fullscreen.collectAsStateWithLifecycle()

    val startEditing = {
        addressValue = initialAddressValue(tab)
        editing = true
    }
    val go = { typed: String ->
        editing = false
        browser.go(typed, runtime.settings.searchLevel)
    }

    LaunchedEffect(searchRequests) {
        if (searchRequests > 0) startEditing()
    }
    Uploads(browser)
    BackHandler(fullscreen != null) { browser.exitFullscreen() }
    BackHandler(fullscreen == null && findState.active) { browser.clearFind() }
    BackHandler(fullscreen == null && !findState.active && editing) { editing = false }
    BackHandler(fullscreen == null && !findState.active && !editing && tab.canGoBack) { browser.back() }

    // The keyboard rises over the bottom bar, and the page ends at whichever is higher. The page so only shrinks as the
    // keyboard comes and grows as it goes, as in a browser without a bottom bar: a page that tells the keyboard from a
    // resize by how much it shrank (Univer, in an open spreadsheet) isn't left a bar's height taller than the screen.
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets(bottom = bottomBarHeight)))) {
            Toolbar(
                tab = tab,
                editing = editing,
                addressValue = addressValue,
                onAddressChange = { addressValue = it },
                waiting = waiting,
                onEdit = startEditing,
                onGo = go,
                onCancel = { editing = false },
                onHome = { browser.load(tab.id, Addresses.HOME) },
                onAskRen = onAskRen,
            )
            if (findState.active && !editing) {
                FindInPageBar(
                    findState = findState,
                    onQueryChange = browser::findInPage,
                    onPrevious = { browser.findNext(false) },
                    onNext = { browser.findNext(true) },
                    onClose = browser::clearFind,
                )
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.chrome)) {
                if (tab.loading) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(tab.progress.coerceIn(5, 100) / 100f).background(p.accent))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab.home) {
                    BrowserHome(runtime, go)
                } else if (panes != null) {
                    SplitPanes(browser, panes, tab.id)
                } else {
                    PullToRefreshTabContainer(
                        canRefresh = tab.error == null && tab.sslWarning == null,
                        currentScrollY = browser::currentTabScrollY,
                        onRefresh = browser::reload,
                    ) {
                        TabView(browser, tab.id)
                    }
                }
                val sslWarning = tab.sslWarning
                val pageError = tab.error
                if (sslWarning != null) {
                    SslWarningCard(
                        warning = sslWarning,
                        onBackToSafety = { browser.goBackFromSslWarning(tab.id) },
                        onProceed = { browser.proceedPastSslWarning(tab.id) },
                    )
                } else if (pageError != null) {
                    PageErrorCard(
                        error = pageError,
                        canGoBack = tab.canGoBack,
                        onRetry = { browser.retryError(tab.id) },
                        onSearchWithRen = { go(Addresses.searchUrl(pageError.failingUrl, runtime.settings.searchLevel)) },
                        onBack = browser::back,
                    )
                }
                if (editing) {
                    OmniboxDropdown(
                        tab = tab,
                        tabs = state.tabs,
                        typedText = addressValue.text,
                        runtime = runtime,
                        onGo = go,
                        onSelectTab = { selectedId ->
                            editing = false
                            browser.select(selectedId)
                        },
                    )
                }
            }
        }
        BottomBar(
            tab = tab,
            count = state.tabs.size,
            onBack = browser::back,
            onForward = browser::forward,
            onSearch = startEditing,
            onTabs = { tabs = true },
            onMore = { menu = true },
            onSwipeTab = { delta ->
                val list = state.tabs
                val currentIndex = list.indexOfFirst { it.id == tab.id }
                if (currentIndex >= 0) {
                    val nextIndex = currentIndex + delta
                    if (nextIndex in list.indices) {
                        browser.select(list[nextIndex].id)
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomBarHeight = it.height },
        )
    }

    if (tabs) TabsSheet(browser, state.tabs, state.active, onDismiss = { tabs = false })
    if (menu) {
        MenuSheet(
            tab = tab,
            runtime = runtime,
            onDismiss = { menu = false },
            onNewTab = { val _ = browser.open(incognito = false) },
            onNewPrivateTab = { val _ = browser.open(incognito = true) },
            onReload = { if (tab.loading) browser.stop() else browser.reload() },
            onOpenHistory = { historySheet = true },
            onOpenBookmarks = { bookmarksSheet = true },
            split = panes != null,
            onSplit = {
                if (panes != null) {
                    split = emptyList()
                } else {
                    val other = state.tabs.lastOrNull { it.id != tab.id && !it.home }
                    if (other == null) {
                        Toast.makeText(context, "Open a page in another tab to split the view.", Toast.LENGTH_SHORT).show()
                    } else {
                        split = listOf(other.id, tab.id)
                    }
                }
            },
            onOpen = onOpen,
        )
    }
    if (historySheet) {
        HistorySheet(
            browser = browser,
            onOpenUrl = go,
            onDismiss = { historySheet = false },
        )
    }
    if (bookmarksSheet) {
        BookmarksSheet(
            tab = tab,
            favorites = runtime.favorites,
            onOpenUrl = go,
            onDismiss = { bookmarksSheet = false },
        )
    }
    contextMenu?.let { target ->
        ContextMenuSheet(
            target = target,
            tab = tab,
            browser = browser,
            addContext = if (contextAccount.contextReady) { kind, title, url -> runtime.contextWidget.addContext(kind, title, url) } else null,
            onDismiss = browser::dismissContextMenu,
        )
    }
    permissionPrompt?.let { prompt ->
        SitePermissionSheet(
            prompt = prompt,
            sitePermissions = browser.sitePermissions,
        )
    }
    httpAuth?.let { prompt ->
        HttpAuthDialog(
            prompt = prompt,
            onAnswer = browser::answerHttpAuth,
        )
    }
    jsDialog?.let { prompt ->
        JsDialogModal(
            prompt = prompt,
            onAnswer = browser::answerJsDialog,
        )
    }
    download?.let { DownloadSheet(it, onSave = browser::download, onDismiss = browser::dismissDownload) }
    fullscreen?.let { view ->
        AndroidView(
            factory = { FrameLayout(it).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
            modifier = Modifier.fillMaxSize(),
            update = { frame ->
                if (view.parent !== frame) {
                    val parent = view.parent
                    if (parent is ViewGroup) parent.removeView(view)
                    frame.removeAllViews()
                    frame.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
        )
    }
}

private fun initialAddressValue(tab: BrowserTab): TextFieldValue {
    val start = Addresses.searchQuery(tab.url) ?: tab.url.takeUnless { tab.home }.orEmpty()
    return TextFieldValue(start, TextRange(0, start.length))
}

/** Pull-to-refresh wrapper for WebView tabs that activates only when the page is scrolled to the top (`scrollY == 0`). */
@Composable
private fun PullToRefreshTabContainer(
    canRefresh: Boolean,
    currentScrollY: () -> Int,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val triggerPx = with(density) { 72.dp.toPx() }
    val maxPullPx = with(density) { 120.dp.toPx() }
    var pullPx by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(canRefresh) {
                if (!canRefresh) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (currentScrollY() != 0) return@awaitEachGesture
                    val startX = down.position.x
                    val startY = down.position.y
                    var engaged = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) {
                                if (engaged && pullPx >= triggerPx) {
                                    onRefresh()
                                }
                                break
                            }
                            val dx = pointer.position.x - startX
                            val dy = pointer.position.y - startY
                            if (!engaged) {
                                if (currentScrollY() != 0 || dy < -slop || (abs(dx) > slop && abs(dx) >= abs(dy))) {
                                    break
                                }
                                if (dy > slop && dy > abs(dx) * 1.25f) {
                                    engaged = true
                                }
                            }
                            if (engaged) {
                                pullPx = ((dy - slop) * 0.55f).coerceIn(0f, maxPullPx)
                                pointer.consume()
                            }
                        }
                    } finally {
                        pullPx = 0f
                    }
                }
            },
    ) {
        content()
        if (pullPx > 8f) {
            val ready = pullPx >= triggerPx
            val pill = RoundedCornerShape(50)
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, (pullPx * 0.5f).roundToInt()) }
                    .padding(top = 8.dp)
                    .height(34.dp)
                    .clip(pill)
                    .background(p.raised)
                    .border(1.dp, if (ready) p.accent else p.line, pill)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = if (ready) p.accent else p.ink2,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (ready) "Release to reload" else "Pull to reload",
                    style = Type.labelMd,
                    color = p.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A page's upload field opens the system's file picker while the browser is on screen. */
@Composable
private fun Uploads(browser: Browser) {
    var answer by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        answer?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        answer = null
    }
    DisposableEffect(browser) {
        browser.fileChooser = { callback, params ->
            answer?.onReceiveValue(null)
            answer = callback
            runCatching { picker.launch(params.createIntent()) }.isSuccess.also {
                if (!it) {
                    callback.onReceiveValue(null)
                    answer = null
                }
            }
        }
        onDispose { browser.fileChooser = null }
    }
}

/**
 * Two tabs at once, as ren desktop's split workspace shows them: one above the other, side by side in landscape. Touching
 * a pane makes its tab the active one, which the address bar, the bottom bar and the menu act on; the volt edge marks it.
 */
@Composable
private fun SplitPanes(browser: Browser, ids: List<String>, active: String) {
    val p = LocalPalette.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pane = @Composable { id: String, modifier: Modifier ->
        Box(
            modifier
                .background(if (id == active) p.accent else p.chrome)
                .padding(2.dp)
                .pointerInput(id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) browser.select(id)
                        }
                    }
                },
        ) {
            TabView(browser, id)
        }
    }
    if (landscape) {
        Row(Modifier.fillMaxSize()) { ids.forEach { pane(it, Modifier.weight(1f).fillMaxHeight()) } }
    } else {
        Column(Modifier.fillMaxSize()) { ids.forEach { pane(it, Modifier.weight(1f).fillMaxWidth()) } }
    }
}

/** The active tab's WebView, in the activity's context while it is shown so its own dialogs and menus can open. */
@Composable
private fun TabView(browser: Browser, id: String) {
    val context = LocalContext.current
    AndroidView(
        factory = { FrameLayout(it) },
        modifier = Modifier.fillMaxSize(),
        update = { frame ->
            val view = browser.shown(id)
            if (view != null && view.parent !== frame) {
                val parent = view.parent
                if (parent is ViewGroup) parent.removeView(view)
                val viewContext = view.context
                if (viewContext is MutableContextWrapper) viewContext.baseContext = context
                frame.removeAllViews()
                frame.addView(view)
            }
        },
        onRelease = { it.removeAllViews() },
    )
}

/** The toolbar: Ren's mark (home), the address bar, and Ask Ren. While the address is edited it takes the whole bar. */
@Composable
private fun Toolbar(
    tab: BrowserTab,
    editing: Boolean,
    addressValue: TextFieldValue,
    onAddressChange: (TextFieldValue) -> Unit,
    waiting: Int,
    onEdit: () -> Unit,
    onGo: (String) -> Unit,
    onCancel: () -> Unit,
    onHome: () -> Unit,
    onAskRen: () -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.chrome).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editing) {
                AddressField(addressValue, onAddressChange, onGo, Modifier.weight(1f))
                TextKey("Cancel", onCancel)
            } else {
                Image(
                    painterResource(R.drawable.ren_mark),
                    contentDescription = "Home",
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(Radius.control)).clickable(role = Role.Button, onClick = onHome),
                )
                AddressPill(tab, onEdit, Modifier.weight(1f))
                AskRen(waiting, onAskRen)
            }
        }
        Rule(color = p.line)
    }
}

/** The address at rest: a padlock or globe and the page's identity, or the invitation to search on home, plus a Private badge on incognito tabs. */
@Composable
private fun AddressPill(tab: BrowserTab, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    val resting = Addresses.resting(tab.url)
    Row(
        modifier
            .height(36.dp)
            .clip(pill)
            .background(p.bg)
            .border(1.dp, if (tab.incognito) p.ink2 else p.line, pill)
            .clickable(role = Role.Button, onClick = onEdit)
            .testTag(ADDRESS_TAG)
            .padding(start = if (tab.incognito) 6.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab.incognito) {
            Row(
                Modifier
                    .height(22.dp)
                    .clip(pill)
                    .background(p.ink)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = p.accentInk, modifier = Modifier.size(12.dp))
                Text("Private", style = Type.labelSm, color = p.accentInk, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
        }
        val icon = when {
            resting.isEmpty() || Addresses.searchQuery(tab.url) != null -> Icons.Outlined.Search
            Addresses.secure(tab.url) -> Icons.Outlined.Lock
            else -> Icons.Outlined.Public
        }
        Icon(icon, contentDescription = null, tint = p.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            resting.ifEmpty { ADDRESS_HINT },
            style = Type.labelLg,
            color = if (resting.isEmpty()) p.muted else p.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

public const val ADDRESS_HINT: String = "Search or type an address"

/** The address being edited: the exact address, selected, with the keyboard's Go. */
@Composable
private fun AddressField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onGo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focus).testTag(ADDRESS_TAG),
        singleLine = true,
        textStyle = Type.body.copy(color = p.ink),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo(value.text) }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(36.dp)
                    .ring(p.accent.copy(alpha = 0.2f), pill, true, width = 2.dp)
                    .clip(pill)
                    .background(p.bg)
                    .border(1.dp, p.accent, pill)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.text.isEmpty()) Text(ADDRESS_HINT, style = Type.body, color = p.muted, maxLines = 1)
                    inner()
                }
                if (value.text.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Clear",
                        tint = p.ink2,
                        modifier = Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button) { onValueChange(TextFieldValue("")) }.padding(6.dp),
                    )
                }
            }
        },
    )
}

/**
 * Ask Ren, as ren desktop draws it (`ArticleAgentActions`): a pill in the logo's volt with white type, in every theme,
 * the name set in the logotype's face. It opens the agent panel and counts the actions waiting on the user.
 */
@Composable
private fun AskRen(waiting: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(LogoVolt)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(ASK_REN_TAG)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_ren), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text("Ask Ren", style = TextStyle(fontFamily = Logo, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, lineHeight = 16.sp), color = Color.White, maxLines = 1)
        if (waiting > 0) {
            Box(Modifier.heightIn(min = 16.dp).clip(CircleShape).background(LocalPalette.current.amb).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                Text("$waiting", style = Type.labelSm.copy(fontFeatureSettings = "tnum"), color = LightPalette.ink, maxLines = 1)
            }
        }
    }
}

/** The bottom bar: back, forward, search, the tabs and the menu; horizontal swipe switches to the adjacent tab. */
@Composable
private fun BottomBar(
    tab: BrowserTab,
    count: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSearch: () -> Unit,
    onTabs: () -> Unit,
    onMore: () -> Unit,
    onSwipeTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .background(p.chrome)
            .pointerInput(Unit) {
                var dragTotal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragTotal += dragAmount
                    },
                    onDragEnd = {
                        val threshold = 48.dp.toPx()
                        if (dragTotal <= -threshold) {
                            onSwipeTab(1)
                        } else if (dragTotal >= threshold) {
                            onSwipeTab(-1)
                        }
                    },
                )
            }
            .navigationBarsPadding(),
    ) {
        Rule(color = p.line)
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BarKey(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tab.canGoBack, onBack, Modifier.weight(1f))
            BarKey(Icons.AutoMirrored.Outlined.ArrowForward, "Forward", tab.canGoForward, onForward, Modifier.weight(1f))
            BarKey(Icons.Outlined.Search, "Search", true, onSearch, Modifier.weight(1f))
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(Radius.control))
                    .clickable(role = Role.Button, onClick = onTabs)
                    .testTag(TABS_TAG)
                    .semantics { contentDescription = "$count open tabs" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(22.dp).border(1.5.dp, p.ink, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text("$count", style = Type.labelSm.copy(fontWeight = Type.key.fontWeight, fontFeatureSettings = "tnum"), color = p.ink, maxLines = 1)
                }
            }
            BarKey(Icons.Outlined.MoreHoriz, "Menu", true, onMore, Modifier.weight(1f).testTag(MORE_TAG))
        }
    }
}

@Composable
private fun BarKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(Radius.control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) p.ink else p.dot, modifier = Modifier.size(22.dp))
    }
}

/** The open tabs: filter Normal or Private, tap one to show it, close one or all, reopen closed tabs, or open a new tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(browser: Browser, tabs: List<BrowserTab>, active: String?, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val closedTabs by browser.history.closedTabs.collectAsStateWithLifecycle()
    var showPrivate by rememberSaveable {
        mutableStateOf(tabs.firstOrNull { it.id == active }?.incognito == true)
    }
    val normalCount = tabs.count { !it.incognito }
    val privateCount = tabs.count { it.incognito }
    LaunchedEffect(privateCount, normalCount) {
        if (showPrivate && privateCount == 0 && normalCount > 0) {
            showPrivate = false
        }
    }
    val shownTabs = remember(tabs, showPrivate) {
        tabs.filter { it.incognito == showPrivate }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.raised,
        contentColor = p.ink,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Open tabs", Modifier.weight(1f))
                Keycap("New tab", { val _ = browser.open(incognito = false); onDismiss() }, icon = Icons.Outlined.Add)
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip("Normal ($normalCount)", !showPrivate, { showPrivate = false })
                FilterChip("Private ($privateCount)", showPrivate, { showPrivate = true })
                Spacer(Modifier.weight(1f))
                if (showPrivate) {
                    Keycap(
                        text = "New private tab",
                        onClick = {
                            val _ = browser.open(incognito = true)
                            onDismiss()
                        },
                        tone = KeyTone.Accent,
                    )
                }
            }
            if (shownTabs.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("No private tabs open.", style = Type.cardBody, color = p.muted)
                    SecondaryKey(
                        text = "New private tab",
                        onClick = {
                            val _ = browser.open(incognito = true)
                            onDismiss()
                        },
                        leading = Icons.Outlined.VisibilityOff,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shownTabs, key = { it.id }) { item ->
                        val on = item.id == active
                        val shape = RoundedCornerShape(Radius.card)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clip(shape)
                                .background(if (on) p.accentSoft else p.card)
                                .border(1.dp, if (on) p.accent else p.line, shape)
                                .clickable(role = Role.Tab) {
                                    browser.select(item.id)
                                    onDismiss()
                                }
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Favicon(item)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        item.label,
                                        style = Type.cardTitle,
                                        color = p.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (item.incognito) {
                                        StatusTag("Private", Tone.Neutral, outline = true)
                                    }
                                }
                                if (!item.home) {
                                    Text(Addresses.resting(item.url), style = Type.labelMd, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Close ${item.label}",
                                tint = p.ink2,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { browser.close(item.id) }
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            }
            if ((!showPrivate && closedTabs.isNotEmpty()) || shownTabs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!showPrivate && closedTabs.isNotEmpty()) {
                        Keycap(
                            text = "Reopen",
                            onClick = {
                                val _ = browser.reopenClosedTab()
                                onDismiss()
                            },
                            icon = Icons.Outlined.Restore,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (shownTabs.isNotEmpty()) {
                        Keycap(
                            text = "Close all",
                            onClick = {
                                browser.closeAll(incognitoOnly = showPrivate)
                                if (showPrivate) onDismiss()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Favicon(tab: BrowserTab) {
    val p = LocalPalette.current
    val icon = tab.favicon
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(p.tile), contentAlignment = Alignment.Center) {
        when {
            tab.incognito && tab.home -> Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = p.ink2, modifier = Modifier.size(16.dp))
            tab.home -> Icon(painterResource(R.drawable.ic_ren), contentDescription = null, tint = p.accent, modifier = Modifier.size(16.dp))
            icon != null -> Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(18.dp))
            else -> Icon(Icons.Outlined.Public, contentDescription = null, tint = p.muted, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * The browser menu: tabs, split view, page controls, Shields, adding the page or a screenshot of it to Ren's context (as ren
 * desktop's page context menu does, while the Context widget's account is ready), Find in page, Desktop site, History,
 * Bookmarks, Print, Activity and Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(
    tab: BrowserTab,
    runtime: RenRuntime,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onReload: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    split: Boolean,
    onSplit: () -> Unit,
    onOpen: (Page) -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val browser = runtime.browser
    val favorites by runtime.favorites.list.collectAsStateWithLifecycle()
    val normalizedBookmarkUrl = remember(tab.url, tab.home) {
        if (tab.home) null else Favorites.normalize(tab.url)
    }
    val savedBookmark = remember(favorites, normalizedBookmarkUrl) {
        if (normalizedBookmarkUrl == null) null else favorites.firstOrNull { it.url == normalizedBookmarkUrl }
    }
    val contextReady = runtime.contextWidget.account.collectAsStateWithLifecycle().value.contextReady
    val shieldHost = remember(tab.url, tab.home) {
        if (tab.home) null else extractWebHost(tab.url)
    }
    var shieldsRevision by remember { mutableIntStateOf(0) }
    val shieldsOn = remember(shieldHost, shieldsRevision) {
        shieldHost != null && runtime.settings.isShieldsEnabledFor(shieldHost)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .padding(bottom = 12.dp),
        ) {
            MenuRow(Icons.Outlined.Add, "New tab") {
                onNewTab()
                onDismiss()
            }
            MenuRow(Icons.Outlined.VisibilityOff, "New private tab") {
                onNewPrivateTab()
                onDismiss()
            }
            if (split || !tab.home) {
                MenuRow(Icons.Outlined.VerticalSplit, if (split) "Close split view" else "Split view") {
                    onSplit()
                    onDismiss()
                }
            }
            if (!tab.home) {
                MenuRow(if (tab.loading) Icons.Outlined.Close else Icons.Outlined.Refresh, if (tab.loading) "Stop" else "Reload") {
                    onReload()
                    onDismiss()
                }
                MenuRow(Icons.Outlined.Share, "Share") {
                    onDismiss()
                    shareLink(context, tab.url, tab.title)
                }
                if (normalizedBookmarkUrl != null) {
                    if (savedBookmark != null) {
                        MenuRow(Icons.Outlined.Bookmark, "Remove bookmark") {
                            runtime.favorites.remove(savedBookmark.id)
                            onDismiss()
                        }
                    } else {
                        MenuRow(Icons.Outlined.BookmarkBorder, "Bookmark") {
                            val _ = runtime.favorites.add(tab.title, tab.url)
                            onDismiss()
                        }
                    }
                }
                if (shieldHost != null) {
                    MenuToggleRow(
                        icon = Icons.Outlined.Shield,
                        label = "Shields",
                        checked = shieldsOn,
                        tag = if (shieldsOn) "${tab.blockedCount} blocked" else "Off",
                    ) { next ->
                        runtime.settings.setSiteShields(shieldHost, next)
                        shieldsRevision++
                        runtime.applyBrowserPreferences()
                        browser.reload()
                    }
                }
                if (contextReady) {
                    MenuRow(Icons.Outlined.BookmarkAdd, "Add page to Ren context") {
                        runtime.contextWidget.addContext("link", tab.title.ifEmpty { tab.url }, tab.url)
                        onDismiss()
                    }
                    MenuRow(Icons.Outlined.Screenshot, "Add screenshot to Ren context") {
                        val shot = browser.captureDataUrl(tab.id)
                        onDismiss()
                        if (shot == null) {
                            Toast.makeText(context, "Nothing to capture on this page.", Toast.LENGTH_SHORT).show()
                        } else {
                            runtime.contextWidget.addContext("screenshot", tab.title, tab.url, shot)
                        }
                    }
                }
                MenuRow(Icons.Outlined.Search, "Find in page") {
                    onDismiss()
                    browser.startFind()
                }
                MenuToggleRow(
                    icon = Icons.Outlined.DesktopWindows,
                    label = "Desktop site",
                    checked = tab.desktopSite,
                ) {
                    browser.toggleDesktopSite(tab.id)
                    onDismiss()
                }
                if (normalizedBookmarkUrl != null) {
                    MenuRow(Icons.Outlined.PushPin, "Add to Home screen") {
                        val pinned = browser.pinCurrentTabShortcut()
                        onDismiss()
                        if (!pinned) {
                            Toast.makeText(context, "Launcher doesn't support pinned shortcuts.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                MenuRow(Icons.Outlined.Print, "Print / Save as PDF") {
                    val activity = context.findActivity()
                    onDismiss()
                    if (activity != null) {
                        val _ = browser.printCurrentTab(activity)
                    }
                }
            }
            Rule(Modifier.padding(vertical = 4.dp, horizontal = 8.dp), color = p.line)
            MenuRow(Icons.Outlined.History, "History") {
                onDismiss()
                onOpenHistory()
            }
            MenuRow(Icons.Outlined.Bookmarks, "Bookmarks") {
                onDismiss()
                onOpenBookmarks()
            }
            Rule(Modifier.padding(vertical = 4.dp, horizontal = 8.dp), color = p.line)
            MenuRow(Icons.Outlined.Timeline, "Activity") {
                onDismiss()
                onOpen(Page.Activity)
            }
            MenuRow(Icons.Outlined.Tune, "Settings") {
                onDismiss()
                onOpen(Page.Settings)
            }
        }
    }
}

private fun extractWebHost(url: String): String? {
    val parsed = Uri.parse(url)
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = parsed.host?.lowercase()?.trimEnd('.')?.takeIf(String::isNotEmpty) ?: return null
    return if (host == "appassets.androidplatform.net") null else host
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
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

@Composable
private fun MenuToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    tag: String? = null,
    onChange: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(Radius.control))
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = p.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = Type.body.copy(fontWeight = Type.labelLg.fontWeight),
            color = p.ink,
            modifier = Modifier.weight(1f),
        )
        if (tag != null) {
            StatusTag(tag, if (checked) Tone.Accent else Tone.Neutral, outline = true)
            Spacer(Modifier.width(10.dp))
        }
        RenToggle(checked, null)
    }
}

/** A page wants to save a file: what and from where, with Save and Cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(request: DownloadRequest, onSave: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.raised,
        contentColor = p.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow("Download")
            Text(request.name, style = Type.title, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull("From ${request.host}", size(request.bytes)).joinToString(" · "), style = Type.body, color = p.ink2)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryKey("Cancel", onDismiss, Modifier.weight(1f))
                PrimaryKey("Save", onSave, Modifier.weight(1f), trailingArrow = false)
            }
        }
    }
}

/** A file size in plain units; null when the page didn't say. */
internal fun size(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes < 1_000_000 -> "${(bytes + 500) / 1000} KB"
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}
