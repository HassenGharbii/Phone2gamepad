package com.phone2gamepad.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phone2gamepad.AppViewModel
import com.phone2gamepad.net.ConnState
import com.phone2gamepad.protocol.Dpad
import com.phone2gamepad.data.ColorSlot
import com.phone2gamepad.data.ControllerType
import com.phone2gamepad.data.ProfileBook
import com.phone2gamepad.protocol.ControllerState
import kotlin.math.min

private fun transportLabel(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "---"
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "---") ?: return "---"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        else -> "---"
    }
}

@Composable
fun GamepadScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val book by vm.profiles.collectAsState()
    val profile = book.active
    val library by vm.skins.collectAsState()
    val skin = library.resolve(profile.skinId)
    val conn by vm.connection.state.collectAsState()
    val metrics by vm.connection.metrics.collectAsState()
    val view = LocalView.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pad = remember { PadState() }
    var redraw by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(settings.showOverlay) }
    var menuOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    val bgImage = rememberBackgroundImage(vm, profile.background)

    // Landscape + immersive.
    LandscapeImmersiveEffect()
    DisposableEffect(activity, settings.keepScreenOn) {
        val w = activity?.window
        if (settings.keepScreenOn) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // Release every control when the screen loses focus or is left so nothing stays stuck.
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE || e == Lifecycle.Event.ON_STOP) {
                pad.reset()
                vm.connection.input.releaseAll()
                generation++
                redraw++
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            pad.reset()
            vm.connection.input.releaseAll()
        }
    }
    // Switching controller type rebuilds every control: release everything so no input can stay stuck.
    LaunchedEffect(profile.controllerType) {
        pad.reset(); vm.connection.input.releaseAll(); generation++; redraw++
    }
    // Neutralize on any loss of connection.
    DisposableEffect(conn) {
        if (conn !is ConnState.Connected) {
            pad.reset(); vm.connection.input.releaseAll(); generation++; redraw++
        }
        onDispose { }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    val controls = remember(size, profile) {
        if (size == IntSize.Zero) emptyList() else GamepadLayout.resolve(profile, size.width.toFloat(), size.height.toFloat())
    }
    val live = rememberUpdatedState(settings)
    val liveTuning = rememberUpdatedState(profile.tuning())
    val connected = conn is ConnState.Connected
    val measurer = rememberTextMeasurer()

    // Frame ticker for the controllers that animate by themselves: the racing wheel springs back when released and the
    // fighting turbo pulses held buttons. Only changed states are sent.
    LaunchedEffect(profile.controllerType, connected) {
        if (!connected || profile.controllerType == ControllerType.Xbox) return@LaunchedEffect
        var last = 0L
        var sent: ControllerState? = null
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                val moved = pad.tick(dt, liveTuning.value)
                val st = pad.toControllerState()
                if (moved || st != sent) {
                    sent = st
                    vm.connection.input.set(st)
                    redraw++
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(skin.col(ColorSlot.Background))) {
        Canvas(Modifier.fillMaxSize()) { drawControllerBody(profile.controllerType, skin, profile.background, bgImage) }
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .alpha(profile.opacity)
                .padTouch(
                    controls, connected, generation, pad, { liveTuning.value },
                    onButtonDown = { if (live.value.haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) },
                    onChanged = { vm.connection.input.set(pad.toControllerState()); redraw++ },
                )
        ) {
            redraw // read to subscribe to redraws
            for (c in controls) drawControlAlpha(c, pad, measurer, skin, 1f, profile.controllerType.domedButtons)
        }

        // Status overlay (real values or "---").
        val ctx = view.context
        val transport = remember(conn) { transportLabel(ctx) }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth(0.66f).padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (overlayVisible) {
                Box(
                    Modifier.weight(1f).background(Palette.Surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        statusText(conn, metrics, transport), color = if (connected) Palette.Text else Palette.Warn,
                        fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp,
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = { overlayVisible = !overlayVisible }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (overlayVisible) "Hide" else "Info", fontSize = 12.sp)
            }
            // Layout/skin entries live behind a menu (two deliberate taps) at the top, away from the controls, so they
            // cannot be hit by accident while playing. Opening an editor releases every control first.
            Box {
                TextButton(onClick = { menuOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Menu", fontSize = 12.sp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit layout") }, onClick = {
                        menuOpen = false
                        pad.reset(); redraw++
                        vm.openLayoutEditorFromGamepad()
                    })
                    DropdownMenuItem(text = { Text("Controller & background") }, onClick = {
                        menuOpen = false
                        pad.reset(); redraw++
                        vm.connection.input.releaseAll()
                        appearanceOpen = true
                    })
                    DropdownMenuItem(text = { Text("Skins") }, onClick = {
                        menuOpen = false
                        pad.reset(); redraw++
                        vm.connection.input.releaseAll()
                        vm.openSkins(profile.id)
                    })
                }
            }
            TextButton(onClick = { vm.back() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Exit", fontSize = 12.sp, color = Palette.Danger)
            }
        }
    }

    if (appearanceOpen) {
        // Edits go straight to the stored profile (only the changed look fields), like the tuning screen.
        AppearanceDialog(
            vm, profile, skin,
            onChange = { np ->
                vm.editProfile(profile.id) { it.copy(controllerType = np.controllerType, background = np.background) }
            },
            onClose = { appearanceOpen = false },
        )
    }
}

private fun statusText(conn: ConnState, m: com.phone2gamepad.net.Metrics, transport: String): String = when (conn) {
    is ConnState.Connected -> {
        val rtt = m.rttMs?.let { "$it ms" } ?: "---"
        val rate = m.sendRateHz?.let { "$it pkt/s" } ?: "---"
        val age = m.lastPeerPacketAgeMs?.let { "$it ms ago" } ?: "---"
        "${conn.target.name} · $transport · RTT $rtt · $rate · PC seen $age"
    }
    is ConnState.Reconnecting -> "Disconnected from ${conn.target.name} · reconnecting (attempt ${conn.attempt}) · ${conn.reason}"
    is ConnState.Connecting -> "Connecting to ${conn.target.name}…"
    is ConnState.Idle -> "Not connected"
    is ConnState.Pairing -> "Pairing…"
    is ConnState.NeedsPairing -> "Pairing required"
    is ConnState.Failed -> "Failed: ${conn.message}"
}
