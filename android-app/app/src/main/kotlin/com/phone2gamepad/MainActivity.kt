package com.phone2gamepad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.phone2gamepad.ui.CustomizeScreen
import com.phone2gamepad.ui.HomeScreen
import com.phone2gamepad.ui.Space
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.phone2gamepad.net.ConnState
import com.phone2gamepad.ui.AboutScreen
import com.phone2gamepad.ui.ConnectScreen
import com.phone2gamepad.ui.DiscoverScreen
import com.phone2gamepad.ui.GamepadScreen
import com.phone2gamepad.ui.ManualScreen
import com.phone2gamepad.ui.P2GTheme
import com.phone2gamepad.ui.LayoutEditorScreen
import com.phone2gamepad.ui.ProfilesScreen
import com.phone2gamepad.ui.TuningScreen
import com.phone2gamepad.ui.SettingsScreen
import com.phone2gamepad.ui.SkinEditorScreen
import com.phone2gamepad.ui.SkinGalleryScreen
import com.phone2gamepad.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Re-hide the system bars whenever the window regains focus while playing (some ROMs restore them). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && (vm.screen == Screen.Gamepad || vm.screen == Screen.LayoutEditor)) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = vm.onBackgrounded()
            override fun onStart(owner: LifecycleOwner) = vm.onForegrounded()
            // Release controls as soon as we lose focus (e.g. notification shade, multi-window).
            override fun onPause(owner: LifecycleOwner) = vm.connection.input.releaseAll()
        })
        setContent { P2GTheme { Root(vm) { finish() } } }
    }
}

@Composable
private fun Root(vm: AppViewModel, finish: () -> Unit) {
    val conn by vm.connection.state.collectAsState()
    val ctx = LocalContext.current
    if (!vm.ready) return // settings not read yet: keep the plain background for a frame instead of flashing onboarding

    BackHandler { if (!vm.back()) finish() }

    // The connection drives a few transitions; everything else is user navigation.
    LaunchedEffect(conn, vm.screen) {
        when {
            // Pairing finished / already paired: onboarding is over, land on Home (or the pad if Play was tapped).
            vm.screen == Screen.Connect && conn is ConnState.Connected -> {
                vm.goHome()
                if (vm.playPending) { vm.playPending = false; vm.navigate(Screen.Gamepad) }
            }
            vm.screen == Screen.Home && conn is ConnState.Connected && vm.playPending -> {
                vm.playPending = false
                vm.navigate(Screen.Gamepad)
            }
            // The PC wants a PIN (first time, or the pairing was revoked): show the pair screen.
            (vm.screen == Screen.Home || vm.screen == Screen.Gamepad) && conn is ConnState.NeedsPairing -> vm.navigate(Screen.Connect)
            vm.screen == Screen.Home && conn is ConnState.Failed -> vm.playPending = false
            vm.screen == Screen.Gamepad && conn is ConnState.Failed -> vm.goHome()
        }
    }

    // Short cross-fade between screens; none when the user turned animations off in the system settings.
    val animScale = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice) {
        val msg = vm.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.notice = null
    }
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = vm.screen, animationSpec = tween(if (animScale == 0f) 0 else 180), label = "screen") { screen ->
            when (screen) {
                Screen.Welcome -> WelcomeScreen(vm)
                Screen.Discover -> DiscoverScreen(vm)
                Screen.Manual -> ManualScreen(vm)
                Screen.Connect -> ConnectScreen(vm)
                Screen.Home -> HomeScreen(vm)
                Screen.Gamepad -> GamepadScreen(vm)
                Screen.Customize -> CustomizeScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Profiles -> ProfilesScreen(vm)
                Screen.Tuning -> TuningScreen(vm)
                Screen.LayoutEditor -> LayoutEditorScreen(vm)
                Screen.Skins -> SkinGalleryScreen(vm)
                Screen.SkinEditor -> SkinEditorScreen(vm)
                Screen.About -> AboutScreen(vm)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Space.l))
    }
}
