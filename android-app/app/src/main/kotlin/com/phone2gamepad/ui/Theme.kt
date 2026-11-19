package com.phone2gamepad.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens. One restrained accent (teal) on neutral dark greys; semantic colours (ok / warn / danger) are used only
 * for state, never decoration. Face-button colours are the conventional controller ones.
 */
object Palette {
    val Bg = Color(0xFF0F1114)
    val Surface = Color(0xFF181B20)
    val SurfaceHigh = Color(0xFF22262D)
    val SurfaceHigher = Color(0xFF2B3038)
    val Outline = Color(0xFF3A404A)
    val OutlineSoft = Color(0xFF2A2F37)
    val Text = Color(0xFFE8EAED)
    val TextDim = Color(0xFFA3AAB5) // 7.9:1 on Bg, 7.0:1 on Surface
    val TextFaint = Color(0xFF7D8590) // 4.6:1 on Bg: secondary captions only
    val Accent = Color(0xFF5BC4B6)
    val OnAccent = Color(0xFF00201C)
    val AccentContainer = Color(0xFF173B37)
    val Ok = Color(0xFF6CCB7A)
    val Danger = Color(0xFFF07A76)
    val Warn = Color(0xFFE6B450)
    val ButtonA = Color(0xFF58C46A)
    val ButtonB = Color(0xFFE5534B)
    val ButtonX = Color(0xFF4D8FE8)
    val ButtonY = Color(0xFFE6B450)
}

/** 4dp spacing scale. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Readable column width for list/form pages; wider screens (landscape, tablets) centre the column. */
    val contentMax = 600.dp
    /** Minimum touch target. */
    val touch = 48.dp
}

private val P2GTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val P2GShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun P2GTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent, onPrimary = Palette.OnAccent,
            primaryContainer = Palette.AccentContainer, onPrimaryContainer = Palette.Text,
            secondary = Palette.Accent, onSecondary = Palette.OnAccent,
            secondaryContainer = Palette.SurfaceHigher, onSecondaryContainer = Palette.Text,
            background = Palette.Bg, onBackground = Palette.Text,
            surface = Palette.Surface, onSurface = Palette.Text,
            surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.TextDim,
            surfaceContainer = Palette.Surface, surfaceContainerHigh = Palette.SurfaceHigh,
            surfaceContainerHighest = Palette.SurfaceHigher, surfaceContainerLow = Palette.Surface,
            inverseSurface = Palette.Text, inverseOnSurface = Palette.Bg,
            outline = Palette.Outline, outlineVariant = Palette.OutlineSoft, error = Palette.Danger,
        ),
        typography = P2GTypography,
        shapes = P2GShapes,
    ) {
        Surface(Modifier.fillMaxSize(), color = Palette.Bg, contentColor = Palette.Text, content = content)
    }
}

// ---------------------------------------------------------------- page chrome

/** Top app bar: back arrow (when [onBack] is set), title, trailing actions. */
@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(P2GIcons.Back, contentDescription = "Back") }
        } else {
            Spacer(Modifier.width(Space.m))
        }
        Text(
            title, Modifier.weight(1f).padding(start = Space.xs).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** Standard page: top bar + scrollable, width-capped content column (centred on wide screens). */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    maxWidth: Dp = Space.contentMax,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            AppTopBar(title, onBack, actions)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = maxWidth).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.l, vertical = Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                    content = {
                        content()
                        Spacer(Modifier.size(Space.l))
                    },
                )
            }
            if (bottomBar != null) {
                Box(Modifier.fillMaxWidth().background(Palette.Bg), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m)) { bottomBar() }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- buttons

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.m),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(Space.s)) }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Space.touch),
        shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, Palette.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Text),
        contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.s),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.s)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.Accent, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Space.touch)) {
        Text(text, color = if (enabled) color else Palette.TextFaint, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- content blocks

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.padding(top = Space.s).semantics { heading() },
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp), color = Palette.TextDim,
    )
}

/** Flat surface card; clickable when [onClick] is set. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    padding: Dp = Space.l,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = if (selected) Palette.AccentContainer else Palette.Surface,
        border = BorderStroke(1.dp, if (selected) Palette.Accent else Palette.OutlineSoft),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(Space.xs), content = content)
    }
}

/** List row: leading icon, title + optional subtitle, trailing slot (defaults to a chevron when clickable). */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(36.dp).background(Palette.SurfaceHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = Palette.Text)
            }
            Spacer(Modifier.width(Space.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(P2GIcons.ChevronRight, null, tint = Palette.TextDim)
        }
    }
}

/** A card holding several [ListRow]s separated by hairlines. */
@Composable
fun RowGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.OutlineSoft),
    ) { Column(content = content) }
}

@Composable
fun SwitchListRow(title: String, subtitle: String?, checked: Boolean, horizontalPadding: Dp = Space.l, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = horizontalPadding, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Space.m)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        Switch(checked, onCheckedChange = null)
    }
}

enum class Tone { Neutral, Ok, Busy, Warn, Error }

private fun toneColor(t: Tone) = when (t) {
    Tone.Neutral -> Palette.TextDim
    Tone.Ok -> Palette.Ok
    Tone.Busy -> Palette.Accent
    Tone.Warn -> Palette.Warn
    Tone.Error -> Palette.Danger
}

@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(toneColor(tone), CircleShape))
}

/** Small pill: coloured dot + label. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Palette.SurfaceHigh, CircleShape).padding(horizontal = Space.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone)
        Spacer(Modifier.width(Space.s))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Inline message block. [tone] picks the icon colour; [action] adds a trailing text button. */
@Composable
fun Notice(
    text: String,
    tone: Tone = Tone.Neutral,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Palette.SurfaceHigh) {
        Row(Modifier.padding(start = Space.l, end = Space.s, top = Space.m, bottom = Space.m), verticalAlignment = Alignment.Top) {
            Icon(
                when (tone) { Tone.Warn, Tone.Error -> P2GIcons.Warning; else -> P2GIcons.Info },
                null, Modifier.padding(top = 2.dp).size(18.dp), tint = toneColor(tone),
            )
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f).padding(end = Space.s), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            }
            if (actionLabel != null && onAction != null) TextAction(actionLabel, onAction)
        }
    }
}

// ------------------------------------------------- legacy helpers kept for the editor screens

@Composable
internal fun InfoCard(title: String, body: String, accent: Boolean = false) {
    Notice(body, tone = if (accent) Tone.Busy else Tone.Neutral, title = title)
}

/** True on a phone held sideways (short and wide): pages switch to two panes instead of one long column. */
@Composable
fun isLandscapePhone(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return c.screenWidthDp > c.screenHeightDp && c.screenHeightDp < 520
}
