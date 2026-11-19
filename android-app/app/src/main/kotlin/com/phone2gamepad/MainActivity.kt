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

    BackHandler { if (!vm.back()) finish() }

    // Connection flow drives navigation: connected -> gamepad; pairing needed/failed while playing -> connect screen.
    LaunchedEffect(conn, vm.screen) {
        when {
            vm.screen == Screen.Connect && conn is ConnState.Connected -> vm.replace(Screen.Gamepad)
            vm.screen == Screen.Gamepad && (conn is ConnState.NeedsPairing || conn is ConnState.Failed || conn is ConnState.Pairing) ->
                vm.replace(Screen.Connect)
        }
    }

    when (vm.screen) {
        Screen.Welcome -> WelcomeScreen(vm)
        Screen.Discover -> DiscoverScreen(vm)
        Screen.Manual -> ManualScreen(vm)
        Screen.Connect -> ConnectScreen(vm)
        Screen.Gamepad -> GamepadScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Profiles -> ProfilesScreen(vm)
        Screen.Tuning -> TuningScreen(vm)
        Screen.LayoutEditor -> LayoutEditorScreen(vm)
        Screen.Skins -> SkinGalleryScreen(vm)
        Screen.SkinEditor -> SkinEditorScreen(vm)
        Screen.About -> AboutScreen(vm)
    }
}
