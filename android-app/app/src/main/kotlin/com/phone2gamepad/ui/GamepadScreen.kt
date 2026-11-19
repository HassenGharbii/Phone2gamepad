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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.OutlinedButton
import com.phone2gamepad.protocol.ControllerState
import com.phone2gamepad.protocol.PedalMath
import com.phone2gamepad.protocol.TiltSteering
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    val pad = remember { PadState() }
    var redraw by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    val bgImage = rememberBackgroundImage(vm, profile.background)
    val steering = remember { TiltSteering() }
    var noTiltSensor by remember { mutableStateOf(false) }
    val pedalBands = remember { IntArray(2) }
    val tiltActive = profile.controllerType == ControllerType.Racing && profile.tiltEnabled

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
    val liveProfile = rememberUpdatedState(profile)
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
                    pedalBands[0] = PedalMath.hapticBand(pad.lt); pedalBands[1] = PedalMath.hapticBand(pad.rt)
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
                    onChanged = {
                        vm.connection.input.set(pad.toControllerState()); redraw++
                        // Light tick whenever a pedal crosses a pressure band (racing only: bands stay 0 elsewhere).
                        if (profile.controllerType == ControllerType.Racing) {
                            val bl = PedalMath.hapticBand(pad.lt); val br = PedalMath.hapticBand(pad.rt)
                            if ((bl != pedalBands[0] || br != pedalBands[1]) && live.value.haptics) {
                                view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.VIRTUAL_KEY)
                            }
                            pedalBands[0] = bl; pedalBands[1] = br
                        }
                    },
                )
        ) {
            redraw // read to subscribe to redraws
            for (c in controls) {
                // The live wheel is its own rotating layer (below); only its fixed shadow/dish is drawn here.
                if (c.kind == Kind.Wheel) drawWheelUnderlay(c.center(), c.r, skin)
                else drawControlAlpha(c, pad, measurer, skin, 1f, profile.controllerType.domedButtons)
            }
        }
        for (c in controls) if (c.kind == Kind.Wheel) WheelLayer(c, pad, skin, profile.opacity * c.opacity, profile.wheelShowAngle)

        // Tilt steering: the phone's roll drives the wheel. Registered only while resumed; off unless the profile enables it.
        TiltSensorEffect(
            enabled = tiltActive, owner = lifecycleOwner, view = view, steering = steering,
            lockDeg = { liveProfile.value.wheelLockDeg }, sensitivity = { liveProfile.value.tiltSensitivity },
            smoothing = { liveProfile.value.tiltSmoothing }, invert = { liveProfile.value.tiltInvert },
            onWheel = { deg -> pad.setTilt(deg, liveTuning.value) },
            onNoSensor = { noTiltSensor = true },
        )
        DisposableEffect(tiltActive) {
            if (tiltActive) { noTiltSensor = false; steering.recenter() }
            onDispose { if (tiltActive) { pad.setTilt(0f, liveTuning.value); steering.reset() } }
        }
        if (tiltActive) {
            Column(Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.background(Palette.Surface.copy(alpha = 0.88f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        if (noTiltSensor) "No tilt sensor on this phone" else "● TILT STEERING ON",
                        color = if (noTiltSensor) Palette.Warn else skin.col(ColorSlot.Accent), fontSize = 10.sp,
                    )
                }
                OutlinedButton(onClick = { steering.recenter() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Recenter", fontSize = 11.sp)
                }
            }
        }

        // HUD: a compact status pill (tap for link details) and a menu button. Both sit on the top edge, away from the
        // controls; leaving and editing live behind the menu (two deliberate taps) so they cannot be hit mid-game.
        val ctx = view.context
        val transport = remember(conn) { transportLabel(ctx) }
        val summary = ConnText.summarize(conn, metrics)
        var details by remember { mutableStateOf(false) }
        LaunchedEffect(details) { if (details) { kotlinx.coroutines.delay(5000); details = false } }
        fun releaseForOverlay() { pad.reset(); redraw++; vm.connection.input.releaseAll() }
        if (settings.showOverlay || !connected) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.clip(CircleShape).background(Palette.Bg.copy(alpha = 0.78f))
                        .clickable(onClickLabel = "Show connection details") { details = !details }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(summary.tone)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (connected) (summary.detail ?: summary.label) else summary.label,
                        color = Palette.Text, fontSize = 12.sp, maxLines = 1,
                    )
                }
                if (details || !connected) {
                    Text(
                        if (connected) ConnText.linkDetails(metrics, transport) else "${(conn as? ConnState.Reconnecting)?.target?.name ?: vm.lastPc?.name ?: "PC"} · ${summary.detail ?: "controls are released until the link is back"}",
                        color = Palette.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Bg.copy(alpha = 0.94f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp)) {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.clip(CircleShape).background(Palette.Bg.copy(alpha = 0.6f)),
            ) { Icon(P2GIcons.Menu, contentDescription = "Controller menu", tint = Palette.Text) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Edit layout") }, leadingIcon = { Icon(P2GIcons.Layout, null) },
                    onClick = { menuOpen = false; releaseForOverlay(); vm.openLayoutEditorFromGamepad() },
                )
                DropdownMenuItem(
                    text = { Text("Controller type & art") }, leadingIcon = { Icon(P2GIcons.Gamepad, null) },
                    onClick = { menuOpen = false; releaseForOverlay(); appearanceOpen = true },
                )
                DropdownMenuItem(
                    text = { Text("Skins") }, leadingIcon = { Icon(P2GIcons.Palette, null) },
                    onClick = { menuOpen = false; releaseForOverlay(); vm.openSkins(profile.id) },
                )
                DropdownMenuItem(
                    text = { Text("Tuning") }, leadingIcon = { Icon(P2GIcons.Tune, null) },
                    onClick = { menuOpen = false; releaseForOverlay(); vm.openTuning(profile.id) },
                )
                DropdownMenuItem(
                    text = { Text(if (settings.showOverlay) "Hide status" else "Show status") }, leadingIcon = { Icon(P2GIcons.Info, null) },
                    onClick = { menuOpen = false; vm.updateSettings(settings.copy(showOverlay = !settings.showOverlay)) },
                )
                HorizontalDivider(color = Palette.OutlineSoft)
                DropdownMenuItem(
                    text = { Text("Exit to home") }, leadingIcon = { Icon(P2GIcons.Close, null) },
                    onClick = { menuOpen = false; releaseForOverlay(); vm.back() },
                )
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
