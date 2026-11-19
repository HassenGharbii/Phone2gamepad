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

/** Pedal maths: the finger's position along the pedal decides how far it is pressed (like a real pedal, lower = more by default). */
object PedalMath {
    /** Time a released pedal takes to spring back from full travel. */
    const val RETURN_SECONDS = 0.14f

    /** Pedal travel 0..1 for a finger at [y] on a pedal spanning [top]..[top]+[height]. */
    fun fraction(y: Float, top: Float, height: Float, pressDown: Boolean): Float {
        if (height <= 0f) return 0f
        val f = ((y - top) / height).coerceIn(0f, 1f)
        return if (pressDown) f else 1f - f
    }

    /**
     * Trigger byte 0..255: [fullPress] sends 255 on any touch; otherwise the travel is shaped by [curve] and [sensitivity]
     * (a gain: 2 reaches full output at half travel; clamped like the stick sensitivity).
     */
    fun value(
        y: Float, top: Float, height: Float, pressDown: Boolean = true, fullPress: Boolean = false,
        curve: ResponseCurve = ResponseCurve.Linear, sensitivity: Float = 1f,
    ): Int {
        if (height <= 0f) return 0
        if (fullPress) return 255
        val shaped = curve.apply(fraction(y, top, height, pressDown)) * sensitivity.coerceIn(0.1f, 4f)
        return InputMath.toTrigger(shaped)
    }

    /** One spring-back step for a released pedal: [current] falls to 0 so that full travel takes [RETURN_SECONDS]. */
    fun easeOut(current: Int, dtSeconds: Float): Int {
        val step = (255f * dtSeconds.coerceAtLeast(0f) / RETURN_SECONDS).toInt().coerceAtLeast(1)
        return (current - step).coerceAtLeast(0)
    }

    /** Coarse pressure band 0..4 used for light haptic ticks: a tick fires whenever the band changes. */
    fun hapticBand(value: Int): Int = (value.coerceIn(0, 255) * 4 + 127) / 255
}

/**
 * Tilt (gravity sensor) steering. The phone is held in landscape like a wheel; the angle of the gravity vector inside
 * the screen plane is the roll. [rollDeg] is positive when the phone is turned clockwise as seen on the screen.
 */
object TiltMath {
    /** Roll angle that gives full lock at sensitivity 1. */
    const val FULL_LOCK_TILT_DEG = 45f

    /** Gravity in the screen plane below this (m/s^2) means the phone is lying flat: the roll is meaningless. */
    const val MIN_PLANE_G = 2.5f

    /**
     * Roll in degrees from the accelerometer/gravity reading ([ax],[ay] in device axes, m/s^2) and the display rotation
     * (0, 1, 2, 3 = Surface.ROTATION_0/90/180/270), or null while the phone is flat.
     */
    fun rollDeg(ax: Float, ay: Float, displayRotation: Int): Float? {
        val (gx, gy) = when (((displayRotation % 4) + 4) % 4) {
            1 -> ay to -ax
            2 -> -ax to -ay
            3 -> -ay to ax
            else -> ax to ay
        }
        if (kotlin.math.hypot(gx, gy) < MIN_PLANE_G) return null
        return Math.toDegrees(atan2(-gx, gy).toDouble()).toFloat()
    }
}

/** Turns successive roll readings into a wheel rotation in degrees (low-pass smoothed, recentrable). Not thread-safe. */
class TiltSteering {
    private var offset = 0f
    private var hasOffset = false
    private var smoothed = 0f
    private var lastRoll = 0f
    var wheelDeg = 0f
        private set

    /** Treat the current pose as straight ahead. */
    fun recenter() { hasOffset = false; smoothed = 0f; wheelDeg = 0f }

    fun reset() { recenter() }

    /**
     * Feeds a new [roll] (from [TiltMath.rollDeg]; null = unusable, the wheel holds its angle) and returns the wheel rotation
     * for a lock-to-lock range of [lockDeg]. [smoothing] 0..0.9 is the low-pass strength; [invert] flips the direction.
     */
    fun update(roll: Float?, lockDeg: Float, sensitivity: Float, smoothing: Float, invert: Boolean): Float {
        if (roll == null) return wheelDeg
        lastRoll = roll
        if (!hasOffset) { offset = roll; hasOffset = true; smoothed = 0f }
        val rel = WheelMath.deltaDeg(offset, roll)
        val a = 1f - smoothing.coerceIn(0f, TILT_MAX_SMOOTHING)
        smoothed += (rel - smoothed) * a
        val half = lockDeg.coerceIn(0f, 3600f) / 2f
        val gain = sensitivity.coerceIn(0.1f, 6f) * half / TiltMath.FULL_LOCK_TILT_DEG
        val v = (smoothed * gain * (if (invert) -1f else 1f)).coerceIn(-half, half)
        wheelDeg = v
        return v
    }

    private companion object { const val TILT_MAX_SMOOTHING = 0.95f }
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
