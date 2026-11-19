package com.phone2gamepad.protocol

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Stick response curve applied to the post-dead-zone magnitude (0..1). Linear is the default. */
enum class ResponseCurve {
    Linear, Quadratic, Cubic;

    fun apply(m: Float): Float {
        val x = m.coerceIn(0f, 1f)
        return when (this) {
            Linear -> x
            Quadratic -> x * x
            Cubic -> x * x * x
        }
    }
}

/** Pure input-conversion helpers (no Android dependencies) so they can be unit tested on the JVM. */
object InputMath {
    /**
     * Converts a stick displacement to a normalized (-1..1) vector with a radial dead zone.
     * [dx],[dy] are offsets from the stick centre in pixels (screen coordinates: y grows downward) and
     * [radius] is the full-travel distance. The returned y is positive when the stick is pushed up.
     * [deadZone] is a 0..0.9 fraction of travel; the live range is re-scaled so output starts at 0 right
     * after the dead zone, then [curve] shapes it, then [sensitivity] multiplies the magnitude (clamped to 1).
     */
    fun normalizeStick(
        dx: Float, dy: Float, radius: Float, deadZone: Float, sensitivity: Float = 1f,
        curve: ResponseCurve = ResponseCurve.Linear,
    ): Pair<Float, Float> {
        if (radius <= 0f) return 0f to 0f
        val dist = hypot(dx, dy)
        var mag = (dist / radius).coerceIn(0f, 1f)
        val dz = deadZone.coerceIn(0f, 0.9f)
        if (mag <= dz || dist == 0f) return 0f to 0f
        mag = curve.apply((mag - dz) / (1f - dz)) * sensitivity.coerceIn(0.1f, 4f)
        mag = mag.coerceIn(0f, 1f)
        val ux = dx / dist
        val uy = dy / dist
        return (ux * mag) to (-uy * mag)
    }

    /** Normalized -1..1 to signed 16-bit, full scale +/-32767 (symmetric, neutral 0). */
    fun toAxis(v: Float): Int = (v.coerceIn(-1f, 1f) * 32767f).roundToInt()

    /** Fraction 0..1 to trigger byte 0..255. */
    fun toTrigger(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 255f).roundToInt()

    /**
     * Analog trigger pad: value grows as the finger moves down the pad (top edge = 0, bottom edge = full).
     * In [digital] mode any touch on the pad is a full press (255).
     */
    fun triggerFromPosition(y: Float, top: Float, height: Float, digital: Boolean): Int {
        if (height <= 0f) return 0
        if (digital) return 255
        return toTrigger((y - top) / height)
    }

    /** Maps a touch offset on the D-pad to direction bits using 8 sectors; returns 0 inside the centre dead area. */
    fun dpadFromOffset(dx: Float, dy: Float, radius: Float): Int {
        if (radius <= 0f || hypot(dx, dy) < radius * 0.2f) return 0
        var bits = 0
        val ax = abs(dx)
        val ay = abs(dy)
        // Diagonals when both axes are within a factor of ~2.4 of each other (22.5 degree sectors).
        val horizontal = ax > ay * 0.414f
        val vertical = ay > ax * 0.414f
        if (horizontal) bits = bits or (if (dx > 0) Dpad.RIGHT else Dpad.LEFT)
        if (vertical) bits = bits or (if (dy > 0) Dpad.DOWN else Dpad.UP)
        return bits
    }
}
