package com.phone2gamepad.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.phone2gamepad.data.ColorSlot
import com.phone2gamepad.data.Skin
import com.phone2gamepad.protocol.TiltMath
import com.phone2gamepad.protocol.TiltSteering
import kotlin.math.roundToInt

/**
 * The live steering wheel. The wheel artwork is drawn once (it only redraws when its size or the skin changes) and turns with
 * a GPU layer rotation read from [PadState.wheelRot], so following the finger costs no drawing and creates no garbage. The
 * shadow underneath is drawn by the main canvas; the light reflection stays fixed on top because light does not turn.
 * Touch input is handled by the gamepad canvas below; this layer takes no pointer events.
 */
@Composable
fun WheelLayer(c: Control, pad: PadState, skin: Skin, alpha: Float, showAngle: Boolean) {
    val d = with(LocalDensity.current) { (c.r * 2f).toDp() }
    Box(
        Modifier
            .offset { IntOffset((c.cx - c.r).roundToInt(), (c.cy - c.r).roundToInt()) }
            .size(d)
            .graphicsLayer { this.alpha = alpha },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = pad.wheelRot }) {
            drawWheelBody(Offset(size.width / 2f, size.height / 2f), size.minDimension / 2f, skin)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawWheelGloss(Offset(size.width / 2f, size.height / 2f), size.minDimension / 2f)
        }
        if (showAngle) {
            // Only recomposes when the whole-degree value changes.
            val deg by remember(pad) { derivedStateOf { pad.wheelRot.roundToInt() } }
            Text(
                "${if (deg > 0) "+" else ""}$deg°",
                color = skin.col(ColorSlot.LabelText), fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = -(d * 0.2f))
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Reads the gravity sensor (no permission needed) while the screen is resumed and [enabled], and turns the phone's roll into
 * wheel rotation through [steering]. [onWheel] receives the wheel angle in degrees. Unregisters on pause so tilting never
 * steers from the background. [onNoSensor] is called once when the phone has no gravity/accelerometer sensor.
 */
@Composable
fun TiltSensorEffect(
    enabled: Boolean,
    owner: LifecycleOwner,
    view: View,
    steering: TiltSteering,
    lockDeg: () -> Float,
    sensitivity: () -> Float,
    smoothing: () -> Float,
    invert: () -> Boolean,
    onWheel: (Float) -> Unit,
    onNoSensor: () -> Unit,
) {
    DisposableEffect(enabled, owner) {
        if (!enabled) return@DisposableEffect onDispose { }
        val sm = view.context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null) {
            onNoSensor()
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_90
                val roll = TiltMath.rollDeg(e.values[0], e.values[1], rotation)
                onWheel(steering.update(roll, lockDeg(), sensitivity(), smoothing(), invert()))
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        var registered = false
        fun register() {
            if (!registered) { steering.recenter(); registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME) }
        }
        fun unregister() {
            if (registered) { sm.unregisterListener(listener); registered = false }
        }
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        onDispose {
            owner.lifecycle.removeObserver(obs)
            unregister()
        }
    }
}
