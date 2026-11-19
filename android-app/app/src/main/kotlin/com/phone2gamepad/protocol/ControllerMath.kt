package com.phone2gamepad.protocol

import kotlin.math.atan2

/**
 * Touch steering wheel maths (no Android dependencies). The finger drags around the wheel; the wheel turns by the angle the
 * finger moved (unwrapped, so several turns are possible), limited to half of the lock-to-lock range each way, and the
 * result becomes the left stick X axis (-1 = full left, +1 = full right).
 */
object WheelMath {
    /** Touches closer to the hub than this fraction of the wheel radius do not steer (the angle is unstable there). */
    const val MIN_RADIUS_FRACTION = 0.15f

    /** Time the wheel takes to spring back from full lock when released. */
    const val RETURN_SECONDS = 0.30f

    /** Angle of the offset ([dx],[dy] in screen pixels, y down) in degrees; clockwise on screen is increasing. */
    fun angleDeg(dx: Float, dy: Float): Float = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()

    /** Smallest signed rotation from angle [prev] to [cur] (both degrees) in (-180, 180]. */
    fun deltaDeg(prev: Float, cur: Float): Float {
        var d = cur - prev
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        return d
    }

    private fun half(lockDeg: Float) = lockDeg.coerceIn(0f, 3600f) / 2f

    fun clampRotation(rotationDeg: Float, lockDeg: Float): Float = rotationDeg.coerceIn(-half(lockDeg), half(lockDeg))

    /** New wheel rotation after the finger moved by [deltaDeg]; stays inside the lock range. */
    fun rotate(rotationDeg: Float, deltaDeg: Float, lockDeg: Float): Float = clampRotation(rotationDeg + deltaDeg, lockDeg)

    /** Stick X (-1..1) for a wheel at [rotationDeg]; dead zone, curve and sensitivity are the profile's stick tuning. */
    fun axis(
        rotationDeg: Float, lockDeg: Float, deadZone: Float, sensitivity: Float = 1f, curve: ResponseCurve = ResponseCurve.Linear,
    ): Float {
        val h = half(lockDeg)
        if (h <= 0f) return 0f
        val v = (rotationDeg / h).coerceIn(-1f, 1f)
        val x = InputMath.normalizeStick(v, 0f, 1f, deadZone, sensitivity, curve).first
        return if (x == 0f) 0f else x // never return -0.0
    }

    /** One self-centering step: moves [rotationDeg] towards 0 so that full lock takes [RETURN_SECONDS]; never overshoots. */
    fun centerStep(rotationDeg: Float, dtSeconds: Float, lockDeg: Float): Float {
        val step = half(lockDeg) * (dtSeconds.coerceAtLeast(0f) / RETURN_SECONDS)
        return when {
            rotationDeg > step -> rotationDeg - step
            rotationDeg < -step -> rotationDeg + step
            else -> 0f
        }
    }
}

/** Pedal maths: pushing the finger forward (towards the top of the pedal) presses it further. */
object PedalMath {
    /** 0..255 for a finger at [y] on a pedal spanning [top]..[top]+[height]; the bottom edge is 0 and the top edge is full. */
    fun fromPosition(y: Float, top: Float, height: Float, digital: Boolean): Int {
        if (height <= 0f) return 0
        if (digital) return 255
        return InputMath.toTrigger((top + height - y) / height)
    }
}

/** Fighting-pad turbo: while on, a held attack button is pressed and released repeatedly. */
object Turbo {
    /** One press-release cycle; 100 ms gives 10 presses per second (50 ms down, 50 ms up) which every game accepts. */
    const val PERIOD_MS = 100L

    /** Buttons that repeat (face buttons and bumpers); sticks, D-pad, Start and Back never do. */
    const val BUTTON_MASK = Buttons.A or Buttons.B or Buttons.X or Buttons.Y or Buttons.LB or Buttons.RB

    /** True during the "pressed" half of the cycle at time [nowMs]. */
    fun pressedPhase(nowMs: Long): Boolean = (nowMs.mod(PERIOD_MS)) < PERIOD_MS / 2

    /** [state] as it should be sent at [nowMs] when turbo is on: held repeat buttons/triggers are released in the "up" half. */
    fun apply(state: ControllerState, nowMs: Long): ControllerState {
        if (pressedPhase(nowMs)) return state
        return state.copy(buttons = state.buttons and BUTTON_MASK.inv(), lt = 0, rt = 0)
    }
}
