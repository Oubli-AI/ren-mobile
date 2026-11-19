package com.hiron.ren.ui

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.ren.browser.Applications
import com.hiron.ren.browser.Destination
import com.hiron.ren.browser.Favorite
import com.hiron.ren.browser.Favorites
import com.hiron.ren.browser.RenApplication
import com.hiron.ren.browser.SearchLevel
import com.hiron.ren.core.RenRuntime
import kotlinx.coroutines.launch

public const val HOME_SEARCH_TAG: String = "home-search"
public const val HOME_SEARCH_HINT: String = "Ask me anything. I dare you."
public const val HOME_DISCOVER_TAG: String = "home-discover"
public const val HOME_ADD_FAVORITE_TAG: String = "home-add-favorite"

/**
 * Ren's home page, the page a new tab opens on: where to go (Search, here, and ren desktop's destinations), the logotype over a spray
 * of volt, the search box with how hard the search should work, and the favorite links. What is typed goes where the
 * address bar would take it; a favorite opens in this tab.
 */
@Composable
internal fun BrowserHome(runtime: RenRuntime, onGo: (String) -> Unit) {
    val p = LocalPalette.current
    var level by rememberSaveable { mutableStateOf(runtime.settings.searchLevel) }
    val favorites by runtime.favorites.list.collectAsState()
    Column(
        Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Environments(onGo)
        Spacer(Modifier.height(64.dp))
        // The spray halo: a soft volt bloom behind the mark, the one street detail on this page.
        Box(
            Modifier.drawBehind {
                val r = size.width * 0.9f
                drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.2f), Color.Transparent), Offset(size.width * 0.35f, size.height * 0.45f), r), r, Offset(size.width * 0.35f, size.height * 0.45f))
                drawCircle(Brush.radialGradient(listOf(p.accentHi.copy(alpha = 0.14f), Color.Transparent), Offset(size.width * 0.72f, size.height * 0.62f), r * 0.8f), r * 0.8f, Offset(size.width * 0.72f, size.height * 0.62f))
            },
        ) {
            RenWordmark(size = 84f)
        }
        Spacer(Modifier.height(36.dp))
        SearchBox(onGo)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            SearchLevel.entries.forEach { option ->
                FilterChip(option.label, option == level, {
                    level = option
                    runtime.settings.searchLevel = option
                })
            }
        }
        Spacer(Modifier.height(36.dp))
        FavoriteLinks(favorites, runtime.favorites, onGo)
        val apps by runtime.applications.list.collectAsState()
        LaunchedEffect(Unit) { runtime.applications.refresh() }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            AppLinks(apps, runtime.applications, onGo)
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** Home (this page) and ren desktop's destinations side by side, as its sidebar lists them; the row scrolls. */
@Composable
private fun Environments(onGo: (String) -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(Radius.card)
    Row(
        Modifier.horizontalScroll(rememberScrollState()).clip(shape).background(p.tile).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DestinationTab(Icons.Outlined.Home, "Home", current = true, onClick = {})
        Destination.entries.forEach { destination ->
            val tag = if (destination == Destination.Discover) Modifier.testTag(HOME_DISCOVER_TAG) else Modifier
            DestinationTab(destination.icon, destination.name, current = false, onClick = { onGo(destination.address) }, modifier = tag)
        }
    }
}

/** The icon ren desktop's sidebar draws for a destination (lucide's Compass, Microscope, Columns3, FileText, Calendar, Music2, Hammer). */
private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Discover -> Icons.Outlined.Explore
        Destination.Research -> Icons.Outlined.Biotech
        Destination.Execute -> Icons.Outlined.ViewWeek
        Destination.Documents -> Icons.Outlined.Description
        Destination.Calendar -> Icons.Outlined.CalendarToday
        Destination.Vibe -> Icons.Outlined.MusicNote
        Destination.Build -> Icons.Outlined.Build
    }

@Composable
private fun DestinationTab(icon: ImageVector, label: String, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(Radius.control)
    Row(
        modifier.height(36.dp).clip(shape).background(if (current) p.card else Color.Transparent)
            .clickable(enabled = !current, role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (current) p.ink else p.ink2, modifier = Modifier.size(16.dp))
        Text(label, style = Type.key, color = if (current) p.ink else p.ink2)
    }
}

/**
 * The search box: a card that lights up in volt while it has focus, the volt search tile, the field, and the go key,
 * which turns volt once there is something to go to. The hint shrinks to fit the field rather than being cut off.
 */
@Composable
private fun SearchBox(onGo: (String) -> Unit) {
    val p = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Radius.panel)
    val edge by animateColorAsState(if (focused) p.accent else p.line, tween(Motion.QUICK), label = "search-edge")
    val go = { if (text.isNotBlank()) onGo(text) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).ring(p.accent.copy(alpha = 0.2f), shape, focused, gap = 2.dp, width = 3.dp)
            .clip(shape).background(p.card).border(if (focused) 1.5.dp else 1.dp, edge, shape).padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.card)).background(p.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).testTag(HOME_SEARCH_TAG),
            singleLine = true,
            textStyle = Type.bodyLg.copy(color = p.ink),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            interactionSource = source,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        BasicText(
                            HOME_SEARCH_HINT,
                            style = Type.bodyLg.copy(color = p.muted),
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = Type.bodyLg.fontSize),
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        val ready = text.isNotBlank()
        val key by animateColorAsState(if (ready) p.accent else p.tile, tween(Motion.QUICK), label = "search-key")
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(key).clickable(enabled = ready, role = Role.Button, onClick = go)
                .semantics { contentDescription = "Search" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = if (ready) p.accentInk else p.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The favorite links as ren desktop's home page draws them (`FavoriteLinks.tsx`, its `dial` variant): each link a
 * favicon tile with its title underneath, then Add, here in one row that scrolls sideways. Holding a tile offers to
 * remove it.
 */
@Composable
private fun FavoriteLinks(list: List<Favorite>, favorites: Favorites, onGo: (String) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    LazyRow(
        Modifier.fillMaxWidth().semantics { contentDescription = "Favorite links" },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        items(list, key = { it.id }) { favorite ->
            FavoriteTile(favorite, onOpen = { onGo(favorite.url) }, onRemove = { favorites.remove(favorite.id) })
        }
        item(key = "add") { AddTile(onClick = { adding = true }) }
    }
    if (adding) AddFavorite(favorites, onDone = { adding = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(favorite: Favorite, onOpen: () -> Unit, onRemove: () -> Unit) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.width(72.dp).clip(RoundedCornerShape(Radius.card))
                .combinedClickable(role = Role.Button, onLongClickLabel = "Remove ${favorite.title}", onLongClick = { menu = true }, onClick = onOpen)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shape = RoundedCornerShape(Radius.card)
            Box(Modifier.size(48.dp).clip(shape).background(p.card).border(1.dp, p.line, shape), contentAlignment = Alignment.Center) {
                SiteIcon(favorite.url, 20.dp)
            }
            Text(favorite.title, style = Type.labelMd, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from favorites") }, onClick = {
                menu = false
                onRemove()
            })
        }
    }
}

/**
 * The apps OpenCode built for the ren account, as ren desktop's sidebar shows them: icon tiles with the name under
 * them. A web app opens in this tab; an Android app downloads and Android's installer opens on it.
 */
@Composable
private fun AppLinks(list: List<RenApplication>, applications: Applications, onGo: (String) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Text("APPLICATIONS", style = Type.labelMd, color = p.muted, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp))
    LazyRow(Modifier.fillMaxWidth().semantics { contentDescription = "Applications" }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { app ->
            Column(
                Modifier.width(72.dp).clip(RoundedCornerShape(Radius.card)).clickable(role = Role.Button) {
                    if (!app.android) onGo(app.location)
                    else scope.launch {
                        Toast.makeText(context, "Downloading ${app.name}…", Toast.LENGTH_SHORT).show()
                        applications.install(app)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    }
                }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val shape = RoundedCornerShape(Radius.card)
                Box(Modifier.size(48.dp).clip(shape).background(p.card).border(1.dp, p.line, shape), contentAlignment = Alignment.Center) {
                    if (app.android) Icon(Icons.Outlined.Android, contentDescription = null, tint = p.accent, modifier = Modifier.size(24.dp))
                    else SiteIcon(app.location, 28.dp, app.icon)
                }
                Text(app.name, style = Type.labelMd, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** [icon], else the site's icon from Google's favicon service, as ren desktop shows it; a globe while it loads or when it can't. */
@Composable
private fun SiteIcon(url: String, size: Dp, icon: String = "") {
    val p = LocalPalette.current
    val images = LocalImages.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = produceState<ImageBitmap?>(null, url, icon, images) {
        value = icon.takeIf(String::isNotBlank)?.let { images?.bitmap(it, px * 2) }
            ?: Favorites.icon(url)?.let { images?.bitmap(it, px * 2) }
    }.value
    if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(size))
    else Icon(Icons.Outlined.Public, contentDescription = null, tint = p.muted, modifier = Modifier.size(size))
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val p = LocalPalette.current
    val radius = with(LocalDensity.current) { Radius.card.toPx() }
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(Radius.card)).clickable(role = Role.Button, onClick = onClick)
            .padding(4.dp).testTag(HOME_ADD_FAVORITE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(48.dp).drawBehind {
                drawRoundRect(p.lineHi, cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = p.muted, modifier = Modifier.size(16.dp))
        }
        Text("Add", style = Type.labelMd, color = p.muted)
    }
}

/** ren desktop's "Add a favorite": an optional title and the address, which has to be an http(s) one. */
@Composable
private fun AddFavorite(favorites: Favorites, onDone: () -> Unit) {
    val p = LocalPalette.current
    var title by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val save = {
        if (favorites.add(title, address) == null) error = "Enter a valid http(s) address." else onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = p.card,
        title = { Text("Add a favorite", style = Type.headline, color = p.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RecessedField(title, { title = it }, placeholder = "Title (optional)")
                RecessedField(
                    address,
                    {
                        address = it
                        error = null
                    },
                    placeholder = "https://…",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                error?.let { Text(it, style = Type.labelMd, color = p.danger) }
            }
        },
        confirmButton = { TextButton(onClick = save) { Text("Save", style = Type.button, color = p.accent) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", style = Type.button, color = p.ink2) } },
    )
}
