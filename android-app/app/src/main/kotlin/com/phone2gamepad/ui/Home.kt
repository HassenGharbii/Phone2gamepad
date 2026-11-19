package com.phone2gamepad.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phone2gamepad.AppViewModel
import com.phone2gamepad.Screen
import com.phone2gamepad.data.BackgroundKind
import com.phone2gamepad.data.BuiltinBackgrounds
import com.phone2gamepad.data.ColorSlot
import com.phone2gamepad.data.ControllerType
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.Skin
import com.phone2gamepad.net.ConnState

/** The profile exactly as it will look on the pad: shell, printed art and the profile's own control layout. */
@Composable
fun ProfilePreview(profile: Profile, skin: Skin, image: ImageBitmap?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier.aspectRatio(2.2f).clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = "${profile.controllerType.label} controller preview" },
    ) {
        drawRect(skin.col(ColorSlot.Background))
        drawControllerBody(profile.controllerType, skin, profile.background, image)
        val idle = PadState()
        for (c in GamepadLayout.resolve(profile, size.width, size.height)) {
            drawControlAlpha(c, idle, measurer, skin, profile.opacity, profile.controllerType.domedButtons)
        }
    }
}

private fun backgroundLabel(p: Profile): String = when (p.background.kind) {
    BackgroundKind.None -> "no art"
    BackgroundKind.Builtin -> BuiltinBackgrounds.label(p.background.ref) + " art"
    BackgroundKind.Image -> "your picture"
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val conn by vm.connection.state.collectAsState()
    val metrics by vm.connection.metrics.collectAsState()
    val book by vm.profiles.collectAsState()
    val library by vm.skins.collectAsState()
    val profile = book.active
    val skin = library.resolve(profile.skinId)
    val image = rememberBackgroundImage(vm, profile.background)
    val pc = vm.lastPc
    val summary = ConnText.summarize(conn, metrics)

    LaunchedEffect(conn, vm.userDisconnected) { vm.autoConnectIfNeeded() }

    val landscape = isLandscapePhone()
    val connected = conn is ConnState.Connected
    val playButton: @Composable () -> Unit = {
        PrimaryButton(
            text = when {
                connected -> "Play"
                vm.playPending -> "Connecting…"
                else -> "Connect and play"
            },
            onClick = { vm.play() }, modifier = Modifier.fillMaxWidth(), icon = P2GIcons.Play,
            enabled = !(vm.playPending && !connected),
        )
    }
    val pcCard: @Composable () -> Unit = {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Palette.SurfaceHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(P2GIcons.Computer, null, tint = Palette.Text)
                }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(pc?.name ?: "No PC", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(summary.tone)
                        Spacer(Modifier.width(Space.s))
                        Text(
                            summary.label + (if (connected) summary.detail?.let { " · $it" } ?: "" else ""),
                            style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
                        )
                    }
                }
            }
            if (conn is ConnState.Reconnecting || conn is ConnState.Failed) {
                summary.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(top = Space.s)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.End) {
                TextAction("Change PC", { vm.changePc() }, color = Palette.TextDim)
                when (conn) {
                    is ConnState.Idle, is ConnState.Failed -> TextAction("Connect", { vm.reconnect() })
                    else -> TextAction("Disconnect", { vm.disconnectByUser() })
                }
            }
        }
    }
    val controllerCard: @Composable () -> Unit = {
        AppCard(onClick = { vm.openCustomize() }, padding = Space.m) {
            ProfilePreview(profile, skin, image, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = Space.s, start = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${profile.controllerType.label} · ${skin.name} skin · ${backgroundLabel(profile)}",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(P2GIcons.Edit, null, tint = Palette.Accent)
                Spacer(Modifier.width(Space.xs))
                Text("Customize", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
            }
        }
    }
    val settingsAction: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { vm.navigate(Screen.Settings) }) { Icon(P2GIcons.Settings, contentDescription = "Settings") }
    }

    if (landscape) {
        Page("Phone2Gamepad", onBack = null, actions = settingsAction, maxWidth = 1100.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    pcCard()
                    playButton()
                    if (book.profiles.size > 1) { SectionHeader("Switch profile"); ProfileStrip(vm) }
                }
                Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(Space.m)) { controllerCard() }
            }
        }
    } else {
        Page("Phone2Gamepad", onBack = null, actions = settingsAction, bottomBar = playButton) {
            pcCard()
            SectionHeader("Controller")
            controllerCard()
            if (book.profiles.size > 1) {
                SectionHeader("Switch profile")
                ProfileStrip(vm)
            }
        }
    }
}

/** Horizontal quick-switch strip of profile cards with live previews. */
@Composable
fun ProfileStrip(vm: AppViewModel) {
    val book by vm.profiles.collectAsState()
    val library by vm.skins.collectAsState()
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val activeIndex = book.profiles.indexOfFirst { it.id == book.activeId }.coerceAtLeast(0)
    // Bring the active profile into view (cards are 150dp + 8dp gap).
    LaunchedEffect(activeIndex) { scroll.animateScrollTo(with(density) { ((activeIndex * 158 - 40).coerceAtLeast(0)).dp.roundToPx() }) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        for (p in book.profiles) {
            val sel = p.id == book.activeId
            Column(
                Modifier.width(150.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (sel) Palette.AccentContainer else Palette.Surface)
                    .border(BorderStroke(if (sel) 2.dp else 1.dp, if (sel) Palette.Accent else Palette.OutlineSoft), RoundedCornerShape(14.dp))
                    .clickable(role = Role.RadioButton) { vm.selectProfile(p.id) }
                    .semantics { selected = sel }
                    .padding(Space.s),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProfilePreview(p, library.resolve(p.skinId), rememberBackgroundImage(vm, p.background), Modifier.fillMaxWidth())
                Text(p.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.controllerType.label, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
    }
}

/** One place for everything that changes how the controller looks and feels, with a live preview of the active profile. */
@Composable
fun CustomizeScreen(vm: AppViewModel) {
    val book by vm.profiles.collectAsState()
    val library by vm.skins.collectAsState()
    val profile = book.active
    val skin = library.resolve(profile.skinId)
    val image = rememberBackgroundImage(vm, profile.background)
    var appearanceOpen by remember { mutableStateOf(false) }

    val previewPane: @Composable () -> Unit = {
        ProfilePreview(profile, skin, image, Modifier.fillMaxWidth())
        Text("Changes are saved as you make them and apply to the profile \"${profile.name}\".", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        if (book.profiles.size > 1) {
            SectionHeader("Profile")
            ProfileStrip(vm)
        }
    }
    val optionsPane: @Composable () -> Unit = {
        SectionHeader("Look")
        RowGroup {
            ListRow(
                "Controller type & art", "${profile.controllerType.label} · ${backgroundLabel(profile)}", P2GIcons.Gamepad,
                onClick = { vm.connection.input.releaseAll(); appearanceOpen = true },
            )
            HorizontalDivider(color = Palette.OutlineSoft)
            ListRow("Skin", "${skin.name} · colours and button shapes", P2GIcons.Palette, onClick = { vm.openSkins(profile.id) })
        }
        SectionHeader("Feel")
        RowGroup {
            ListRow("Layout", "Move, resize or hide controls", P2GIcons.Layout, onClick = { vm.openLayoutEditor(profile.id) })
            HorizontalDivider(color = Palette.OutlineSoft)
            ListRow(
                "Tuning",
                if (profile.controllerType == ControllerType.Racing) "Wheel, pedals, tilt, dead zone, sensitivity" else "Dead zone, sensitivity, triggers, opacity",
                P2GIcons.Tune, onClick = { vm.openTuning(profile.id) },
            )
        }
        SectionHeader("Profiles")
        RowGroup {
            ListRow("Manage profiles", "New, rename, duplicate, import, export", P2GIcons.Profiles, onClick = { vm.navigate(Screen.Profiles) })
        }
    }
    if (isLandscapePhone()) {
        Page("Customize", onBack = { vm.back() }, maxWidth = 1100.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) { previewPane() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) { optionsPane() }
            }
        }
    } else {
        Page("Customize", onBack = { vm.back() }) {
            previewPane()
            optionsPane()
        }
    }

    if (appearanceOpen) {
        AppearanceDialog(
            vm, profile, skin,
            onChange = { np -> vm.editProfile(profile.id) { it.copy(controllerType = np.controllerType, background = np.background) } },
            onClose = { appearanceOpen = false },
        )
    }
}
