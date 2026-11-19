package com.phone2gamepad.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Forces landscape and hides the system bars while the calling screen is shown; restores both afterwards. */
@Composable
internal fun LandscapeImmersiveEffect() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val act = activity
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val w = act?.window
        if (w != null) {
            val c = WindowInsetsControllerCompat(w, w.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            w?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}
