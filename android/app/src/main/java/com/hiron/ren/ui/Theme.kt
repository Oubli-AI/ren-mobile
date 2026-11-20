package com.hiron.ren.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hiron.ren.R

/**
 * Ren's design tokens, "Ink & Volt", as ren desktop implements them (its `tailwind.config.ts` and `src/index.css`):
 * ink and paper neutrals with one electric colour, volt blurple, used for primary actions, focus, selection, the
 * active state and agent activity. Surfaces are flat behind 1dp hairlines; only layers that float cast a shadow.
 */
public data class Palette(
    /** Paper: the page and content background (`surface-0` / `surface-900`). */
    val bg: Color,
    /** Chrome: the browser frame behind the toolbar, the tab strip and the bottom bar (`--ron-chrome`). */
    val chrome: Color,
    /** Layers that float: sheets, menus, dialogs (`card` / `popover`). */
    val raised: Color,
    /** Secondary buttons and icon keys (`.btn-secondary`). */
    val key: Color,
    /** A secondary button while it is pressed. */
    val keyHi: Color,
    /** Recessed fills: wells, pills, Ren's bubbles, a segmented control's track (`surface-100` / `surface-800`). */
    val tile: Color,
    /** The active segment on a [tile] track. */
    val high: Color,
    /** Ink: primary text and the ink button's fill. */
    val ink: Color,
    /** Secondary text. */
    val ink2: Color,
    /** Muted text: placeholders, timestamps, tag labels. */
    val muted: Color,
    /** Hairlines between tiers. */
    val rule: Color,
    /** Volt: primary actions, focus, selection, links, agent activity (`blurple-600` on paper, `-400` on ink). */
    val accent: Color,
    /** Ink on volt and on an ink button. */
    val accentInk: Color,
    /** Volt while hovered or pressed (`blurple-700` on paper, `-300` on ink). */
    val accentHi: Color,
    /** A volt wash: the accent badge, the user's bubble, a selected well. */
    val accentSoft: Color,
    /** Info: Ren working (`info`). */
    val info: Color,
    /** Success: done, connected, granted. */
    val ok: Color,
    /** Signal: warnings and waiting on your OK. Yellow, never orange. */
    val amb: Color,
    /** Danger: errors, destructive actions, a live microphone. */
    val danger: Color,
    /** Cards (`.card`). */
    val card: Color,
    /** A card while it is pressed. */
    val cardHi: Color,
    /** Inputs (`.input`). */
    val field: Color,
    /** Card and field borders (`surface-200` / `surface-800`). */
    val line: Color,
    /** A pressed or hovered card's border (`surface-300` / `surface-700`). */
    val lineHi: Color,
    /** The ink hairline (`--ren-hairline`): frames and chips over any surface. */
    val edge: Color,
    /** A faint ink wash: icon wells, info strips. */
    val edgeSoft: Color,
    /** Step dots and an unselected radio ring. */
    val dot: Color,
    /** A toggle's track when off. */
    val track: Color,
    /** Chrome over content, slightly see-through (`.chrome-glass`). */
    val glass: Color,
    val dark: Boolean,
)

public val LightPalette: Palette = Palette(
    bg = Color(0xFFFFFFFF), chrome = Color(0xFFF1F2F6), raised = Color(0xFFFFFFFF), key = Color(0xFFF3F3F7),
    keyHi = Color(0xFFE6E7ED), tile = Color(0xFFF3F3F7), high = Color(0xFFFFFFFF), ink = Color(0xFF0B0C17), ink2 = Color(0xFF4A4C58),
    muted = Color(0xFF72747E), rule = Color(0xFFE6E7ED),
    accent = Color(0xFF5543FF), accentInk = Color(0xFFFFFFFF), accentHi = Color(0xFF4533D8), accentSoft = Color(0x1A5543FF),
    info = Color(0xFF676AFE), ok = Color(0xFF01B568), amb = Color(0xFFE6C103), danger = Color(0xFFEF2D4A),
    card = Color(0xFFFFFFFF), cardHi = Color(0xFFF9FAFC), field = Color(0xFFF9FAFC), line = Color(0xFFE6E7ED),
    lineHi = Color(0xFFD3D4DC), edge = Color(0x170B0C17), edgeSoft = Color(0x0A0B0C17), dot = Color(0x2E0B0C17),
    track = Color(0xFFE6E7ED), glass = Color(0xE0F1F2F6),
    dark = false,
)

/** Dark is black and charcoal with no hue: volt is the only colour. */
public val DarkPalette: Palette = Palette(
    bg = Color(0xFF0C0C0C), chrome = Color(0xFF070707), raised = Color(0xFF141414), key = Color(0xFF1E1E1E),
    keyHi = Color(0xFF2C2C2C), tile = Color(0xFF1E1E1E), high = Color(0xFF2C2C2C), ink = Color(0xFFF5F5F5), ink2 = Color(0xFFBDBDBD),
    muted = Color(0xFF919191), rule = Color(0xFF1E1E1E),
    accent = Color(0xFF8D98FE), accentInk = Color(0xFF0B0C17), accentHi = Color(0xFFB3BDFF), accentSoft = Color(0x268D98FE),
    info = Color(0xFF676AFE), ok = Color(0xFF01B568), amb = Color(0xFFE6C103), danger = Color(0xFFEF2D4A),
    card = Color(0xFF141414), cardHi = Color(0xFF1E1E1E), field = Color(0xFF141414), line = Color(0xFF1E1E1E),
    lineHi = Color(0xFF2C2C2C), edge = Color(0x14F5F5F5), edgeSoft = Color(0x0AF5F5F5), dot = Color(0x2EF5F5F5),
    track = Color(0xFF2C2C2C), glass = Color(0xE6070707),
    dark = true,
)

public val LocalPalette: ProvidableCompositionLocal<Palette> = staticCompositionLocalOf { LightPalette }

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int, width: Float, opticalSize: Float) = Font(
    R.font.bricolage_grotesque, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width), FontVariation.Setting("opsz", opticalSize)),
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(R.font.inter, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

@OptIn(ExperimentalTextApi::class)
private fun jetBrainsMono(weight: Int) =
    Font(R.font.jetbrains_mono, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Bricolage Grotesque: headings, heavy and tight, at the optical size of the sizes they are set in. */
public val Display: FontFamily = FontFamily(bricolage(700, 100f, 24f), bricolage(800, 100f, 24f))
/** The poster cut of the display face (`.ren-display`): 800 at 86% width, for heroes. */
public val Poster: FontFamily = FontFamily(bricolage(800, 86f, 40f))
/** The logotype's cut of the display face (ren desktop's docs/brand.md § Logo): 800 at 92% width, opsz 96. */
public val Logo: FontFamily = FontFamily(bricolage(800, 92f, 96f))
/** The logo's volt (`blurple-600`, the tile of RenMark), the same in Light and Dark. */
public val LogoVolt: Color = Color(0xFF5543FF)
/** Inter: every piece of interface text. */
public val Sans: FontFamily = FontFamily(inter(400), inter(500), inter(600))
/** JetBrains Mono: code, numbers in data and tag labels. */
public val Mono: FontFamily = FontFamily(jetBrainsMono(400), jetBrainsMono(500), jetBrainsMono(600))

/** Inter's features as ren sets them: open digits, a single-storey a off, rounded quotes. */
private const val INTER = "cv02, cv03, cv04, cv11, ss03"

private fun sans(weight: FontWeight, size: Float, line: Float, tracking: Float = -0.008f) = TextStyle(
    fontFamily = Sans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em, fontFeatureSettings = INTER,
)

/**
 * The type scale. One display heading per view, in sentence case; interface text is Inter at 13 to 14sp with 12sp for
 * secondary lines; mono is for code, figures and tag labels, the one uppercase style.
 */
public object Type {
    /** A hero line in the poster cut. */
    public val greeting: TextStyle = TextStyle(fontFamily = Poster, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 38.sp, letterSpacing = (-0.045).em)
    public val stat: TextStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 38.sp, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum")
    public val headline: TextStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em)
    public val title: TextStyle = sans(FontWeight.SemiBold, 15f, 20f, -0.01f)
    public val bodyLg: TextStyle = sans(FontWeight.Normal, 16f, 24f)
    public val body: TextStyle = sans(FontWeight.Normal, 14f, 21f)
    public val key: TextStyle = sans(FontWeight.SemiBold, 14f, 20f)
    public val labelLg: TextStyle = sans(FontWeight.Medium, 13f, 16f)
    public val labelMd: TextStyle = sans(FontWeight.Medium, 12f, 16f)
    public val labelSm: TextStyle = sans(FontWeight.Medium, 11f, 14f)
    public val tele: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")
    public val mono: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)

    /** Onboarding step headlines (`display-sm`). */
    public val step: TextStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.025).em)
    /** Buttons (`.btn`). */
    public val button: TextStyle = sans(FontWeight.SemiBold, 14f, 20f)
    /** Secondary keys and text links. */
    public val monoKey: TextStyle = sans(FontWeight.Medium, 13f, 16f)
    /** Tag labels (`.tag-label`): mono capitals, small and widely tracked. */
    public val eyebrow: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.14.em)
    /** Status tags. */
    public val tag: TextStyle = sans(FontWeight.Medium, 11f, 14f)
    public val cardTitle: TextStyle = sans(FontWeight.SemiBold, 14f, 19f)
    public val cardBody: TextStyle = sans(FontWeight.Normal, 13f, 19f)
    /** Pills (`.pill`). */
    public val chip: TextStyle = sans(FontWeight.Medium, 13f, 18f)
    /** Quoted text inside a card: the line to read, the message Ren will send. */
    public val quote: TextStyle = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em)
}

/** The corner radii: controls, cards, then panels and sheets; chips and segmented controls are full pills. */
public object Radius {
    public val control: Dp = 10.dp
    public val card: Dp = 12.dp
    public val panel: Dp = 20.dp
}

/** Durations (ms) and easings shared by every animated component: quick, and nothing pulses for decoration. */
public object Motion {
    public const val QUICK: Int = 150
    public const val BASE: Int = 220
    public const val SLOW: Int = 400
    /** Per-item entrance delay. */
    public const val STAGGER: Int = 50
    /** `ease-out-expo`. */
    public val Emphasized: CubicBezierEasing = CubicBezierEasing(0.19f, 1f, 0.22f, 1f)
    /** `ease-smooth`. */
    public val Standard: CubicBezierEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
}

/** True when the system has animations off; entrances then land at once. */
@Composable
public fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * ren desktop's themes (`ThemeName` in its `lib/theme.ts`): Light, Dark, Glass and System. System and Glass follow the
 * phone's dark mode.
 */
public enum class ThemeChoice(public val wire: String, public val label: String) {
    Light("light", "Light"),
    Dark("dark", "Dark"),
    Glass("glass", "Glass"),
    System("system", "System"),
    ;

    public companion object {
        public fun of(wire: String?): ThemeChoice = entries.firstOrNull { it.wire == wire } ?: System
    }
}

/**
 * Glass, as ren desktop's "GLASS THEME" in `src/index.css` paints it over a real material: colourless veils (white in
 * Light, black in Dark) over the phone's wallpaper and what is behind Ren, which the window blurs (`Theme.Ren.Glass`),
 * with the same ink and volt. Chrome takes `--ron-glass-chrome`, pages and cards `--ron-glass-content`, sheets and
 * menus `--ron-glass-popover`, wells and secondary keys `--ron-glass-fill`, active controls `--ron-glass-control`,
 * rules `--ron-glass-hairline`.
 */
public fun glassPalette(dark: Boolean): Palette {
    val base = if (dark) DarkPalette else LightPalette
    val chrome = if (dark) Color(0f, 0f, 0f, 0.16f) else Color(1f, 1f, 1f, 0.08f)
    val content = if (dark) Color(0f, 0f, 0f, 0.24f) else Color(1f, 1f, 1f, 0.2f)
    val popover = if (dark) Color(18 / 255f, 18 / 255f, 18 / 255f, 0.8f) else Color(252 / 255f, 252 / 255f, 252 / 255f, 0.78f)
    val fill = if (dark) Color(1f, 1f, 1f, 0.06f) else Color(0f, 0f, 0f, 0.045f)
    val hairline = if (dark) Color(1f, 1f, 1f, 0.09f) else Color(0f, 0f, 0f, 0.08f)
    val control = if (dark) Color(1f, 1f, 1f, 0.09f) else Color(1f, 1f, 1f, 0.5f)
    return base.copy(
        bg = content, chrome = chrome, raised = popover, key = fill, keyHi = control, tile = fill, high = control,
        rule = hairline, card = content, cardHi = control, field = fill, line = hairline, lineHi = hairline,
        track = fill, glass = chrome,
    )
}

/** Ren's theme: Light or Dark, following the system unless [dark] says otherwise, and Glass's veils when [glass]. */
@Composable
public fun RenTheme(dark: Boolean = isSystemInDarkTheme(), glass: Boolean = false, content: @Composable () -> Unit) {
    val p = when {
        glass -> glassPalette(dark)
        dark -> DarkPalette
        else -> LightPalette
    }
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent, onPrimary = p.accentInk, primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
        secondary = p.ink, onSecondary = p.accentInk, tertiary = p.ok, onTertiary = p.accentInk,
        background = p.bg, onBackground = p.ink, surface = p.raised, onSurface = p.ink, surfaceVariant = p.tile,
        onSurfaceVariant = p.ink2, error = p.danger, onError = Color.White, outline = p.lineHi, outlineVariant = p.rule,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.card, surfaceContainer = p.raised,
        surfaceContainerHigh = p.tile, surfaceContainerHighest = p.high, scrim = Color(0x800B0C17),
    )
    val type = Typography(
        headlineSmall = Type.headline,
        titleLarge = Type.headline,
        titleMedium = Type.title,
        titleSmall = Type.labelLg,
        bodyLarge = Type.bodyLg,
        bodyMedium = Type.body,
        bodySmall = Type.labelMd.copy(fontWeight = FontWeight.Normal),
        labelLarge = Type.key,
        labelMedium = Type.labelMd,
        labelSmall = Type.labelSm,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
