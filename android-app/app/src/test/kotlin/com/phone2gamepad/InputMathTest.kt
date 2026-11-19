package com.phone2gamepad

import com.phone2gamepad.protocol.Dpad
import com.phone2gamepad.protocol.InputMath
import com.phone2gamepad.protocol.ResponseCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMathTest {
    private val eps = 1e-4f

    @Test
    fun insideDeadZoneIsZero() {
        val (x, y) = InputMath.normalizeStick(5f, 5f, 100f, 0.1f)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun fullDeflectionIsOne_andYIsUpPositive() {
        val (x, y) = InputMath.normalizeStick(0f, -100f, 100f, 0.1f)
        assertEquals(0f, x, eps)
        assertEquals(1f, y, eps)
        val (x2, y2) = InputMath.normalizeStick(100f, 0f, 100f, 0.1f)
        assertEquals(1f, x2, eps)
        assertEquals(0f, y2, eps)
    }

    @Test
    fun beyondRadiusIsClamped() {
        val (x, y) = InputMath.normalizeStick(500f, 0f, 100f, 0.1f)
        assertEquals(1f, x, eps)
        assertEquals(0f, y, eps)
    }

    @Test
    fun deadZoneIsRescaledSoOutputStartsAtZero() {
        // 55% travel with a 10% dead zone => (0.55-0.1)/0.9 = 0.5
        val (x, _) = InputMath.normalizeStick(55f, 0f, 100f, 0.1f)
        assertEquals(0.5f, x, eps)
    }

    @Test
    fun sensitivityScalesAndClamps() {
        val (x, _) = InputMath.normalizeStick(40f, 0f, 100f, 0f, 1.5f)
        assertEquals(0.6f, x, eps)
        val (x2, _) = InputMath.normalizeStick(80f, 0f, 100f, 0f, 2f)
        assertEquals(1f, x2, eps)
    }

    @Test
    fun radialMagnitudeNeverExceedsOne() {
        val (x, y) = InputMath.normalizeStick(100f, 100f, 100f, 0f)
        assertTrue(Math.hypot(x.toDouble(), y.toDouble()) <= 1.0001)
    }

    @Test
    fun zeroRadiusIsSafe() {
        assertEquals(0f to 0f, InputMath.normalizeStick(1f, 1f, 0f, 0.1f))
    }

    @Test
    fun axisConversionIsSymmetricAndBounded() {
        assertEquals(0, InputMath.toAxis(0f))
        assertEquals(32767, InputMath.toAxis(1f))
        assertEquals(-32767, InputMath.toAxis(-1f))
        assertEquals(32767, InputMath.toAxis(5f))
        assertEquals(-32767, InputMath.toAxis(-5f))
    }

    @Test
    fun triggerRange() {
        assertEquals(0, InputMath.toTrigger(0f))
        assertEquals(255, InputMath.toTrigger(1f))
        assertEquals(128, InputMath.toTrigger(0.5f))
        assertEquals(0, InputMath.toTrigger(-1f))
        assertEquals(255, InputMath.toTrigger(9f))
    }

    @Test
    fun triggerFromPositionAnalogAndDigital() {
        assertEquals(0, InputMath.triggerFromPosition(100f, 100f, 200f, false))
        assertEquals(128, InputMath.triggerFromPosition(200f, 100f, 200f, false))
        assertEquals(255, InputMath.triggerFromPosition(400f, 100f, 200f, false))
        assertEquals(255, InputMath.triggerFromPosition(100f, 100f, 200f, true))
        assertEquals(0, InputMath.triggerFromPosition(100f, 100f, 0f, false))
    }

    @Test
    fun dpadSectors() {
        assertEquals(0, InputMath.dpadFromOffset(1f, 1f, 100f))
        assertEquals(Dpad.UP, InputMath.dpadFromOffset(0f, -50f, 100f))
        assertEquals(Dpad.DOWN, InputMath.dpadFromOffset(0f, 50f, 100f))
        assertEquals(Dpad.LEFT, InputMath.dpadFromOffset(-50f, 0f, 100f))
        assertEquals(Dpad.RIGHT, InputMath.dpadFromOffset(50f, 0f, 100f))
        assertEquals(Dpad.UP or Dpad.RIGHT, InputMath.dpadFromOffset(40f, -40f, 100f))
        assertEquals(Dpad.DOWN or Dpad.LEFT, InputMath.dpadFromOffset(-40f, 40f, 100f))
    }

    @Test
    fun responseCurvesShapeMagnitudeAfterDeadZone() {
        // 55 px of 100 with a 10 % dead zone rescales to 0.5 before the curve.
        val lin = InputMath.normalizeStick(55f, 0f, 100f, 0.1f, 1f, ResponseCurve.Linear).first
        val quad = InputMath.normalizeStick(55f, 0f, 100f, 0.1f, 1f, ResponseCurve.Quadratic).first
        val cub = InputMath.normalizeStick(55f, 0f, 100f, 0.1f, 1f, ResponseCurve.Cubic).first
        assertEquals(0.5f, lin, eps)
        assertEquals(0.25f, quad, eps)
        assertEquals(0.125f, cub, eps)
        // Full deflection is still full scale for every curve; curves never exceed linear.
        for (c in ResponseCurve.entries) assertEquals(1f, InputMath.normalizeStick(100f, 0f, 100f, 0.1f, 1f, c).first, eps)
        assertTrue(quad <= lin && cub <= quad)
    }

    @Test
    fun curveIsAppliedBeforeSensitivity() {
        assertEquals(0.5f, InputMath.normalizeStick(50f, 0f, 100f, 0f, 2f, ResponseCurve.Quadratic).first, eps)
    }

    @Test
    fun curvesKeepDirection() {
        val (x, y) = InputMath.normalizeStick(-50f, -50f, 100f, 0f, 1f, ResponseCurve.Cubic)
        assertTrue(x < 0f && y > 0f) // left and up (screen y grows downward)
    }
}
