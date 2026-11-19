package com.phone2gamepad

import androidx.compose.ui.geometry.Offset
import com.phone2gamepad.data.ControllerType
import com.phone2gamepad.data.HandbrakeButton
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.ProfileBook
import com.phone2gamepad.data.ProfileCodec
import com.phone2gamepad.data.ProfileCodec.BookResult
import com.phone2gamepad.data.TriggerMode
import com.phone2gamepad.protocol.Buttons
import com.phone2gamepad.protocol.PedalMath
import com.phone2gamepad.protocol.ResponseCurve
import com.phone2gamepad.protocol.TiltMath
import com.phone2gamepad.protocol.TiltSteering
import com.phone2gamepad.protocol.WheelMath
import com.phone2gamepad.ui.GamepadLayout
import com.phone2gamepad.ui.LayoutMath
import com.phone2gamepad.ui.PadState
import com.phone2gamepad.ui.Tuning
import com.phone2gamepad.ui.tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Racing controller round 5: wheel response, pedals, handbrake and tilt steering (pure JVM). */
class RacingTest {
    private val racing = Profile("p", "p", controllerType = ControllerType.Racing)
    private fun controls(p: Profile = racing, w: Float = 2400f, h: Float = 1080f) = GamepadLayout.resolve(p, w, h).associateBy { it.id }

    // ---- wheel ----

    @Test fun wheelSteersWithItsOwnDeadZoneSensitivityAndCurve() {
        val c = controls().getValue("WHEEL")
        val lock = 540f
        fun axisAfter(deg: Int, t: Tuning): Float {
            val pad = PadState()
            fun at(d: Double) = Offset(c.cx + c.r * 0.8f * cos(Math.toRadians(d)).toFloat(), c.cy + c.r * 0.8f * sin(Math.toRadians(d)).toFloat())
            pad.press(c, at(0.0), t)
            for (d in 5..deg step 5) pad.apply(c, at(d.toDouble()), t)
            return pad.toControllerState().lx / 32767f
        }
        // The stick dead zone is not used for the wheel any more: only the wheel's own.
        assertEquals(0.5f, axisAfter(135, Tuning(deadZone = 0.5f, wheelLockDeg = lock)), 0.01f)
        // Wheel dead zone 10% of half-lock (27 degrees): 20 degrees is still 0, 135 degrees is rescaled.
        assertEquals(0f, axisAfter(20, Tuning(wheelDeadZone = 0.1f, wheelLockDeg = lock)), 0f)
        assertEquals((0.5f - 0.1f) / 0.9f, axisAfter(135, Tuning(wheelDeadZone = 0.1f, wheelLockDeg = lock)), 0.01f)
        // Sensitivity 2: full output at half the travel.
        assertEquals(1f, axisAfter(135, Tuning(wheelSensitivity = 2f, wheelLockDeg = lock)), 0.001f)
        // Quadratic curve gives finer control near centre: 135 deg (half of the half-range) -> 0.25.
        assertEquals(0.25f, axisAfter(135, Tuning(wheelCurve = ResponseCurve.Quadratic, wheelLockDeg = lock)), 0.01f)
    }

    @Test fun everyLockPresetReachesFullScaleAtHalfLock() {
        for (lock in Profile.WHEEL_LOCK_CHOICES) {
            assertEquals(1f, WheelMath.axis(lock / 2f, lock, 0f), 1e-6f)
            assertEquals(-1f, WheelMath.axis(-lock / 2f, lock, 0f), 1e-6f)
            assertEquals(0f, WheelMath.axis(0f, lock, 0f), 0f)
            assertEquals(lock / 2f, WheelMath.clampRotation(5000f, lock), 0f)
        }
        // Full scale on the wire is the symmetric +/-32767.
        val pad = PadState()
        pad.setTilt(900f, Tuning(wheelLockDeg = 360f))
        assertEquals(180f, pad.wheelRot, 0f)
        assertEquals(32767, pad.toControllerState().lx)
        pad.setTilt(-900f, Tuning(wheelLockDeg = 360f))
        assertEquals(-32767, pad.toControllerState().lx)
    }

    @Test fun multiTurnWheelHasNoGlitchWhenTheFingerCrossesTheAngleWrap() {
        val c = controls().getValue("WHEEL")
        val t = Tuning(wheelLockDeg = 900f)       // +/-450 degrees: more than one full turn
        val pad = PadState()
        fun at(d: Double) = Offset(c.cx + c.r * 0.8f * cos(Math.toRadians(d)).toFloat(), c.cy + c.r * 0.8f * sin(Math.toRadians(d)).toFloat())
        pad.press(c, at(0.0), t)
        var last = 0f
        for (d in 2..400 step 2) {                // 400 degrees clockwise, crossing 180 (the atan2 wrap) on the way
            pad.apply(c, at(d.toDouble()), t)
            assertTrue("monotonic at $d", pad.wheelRot >= last - 1e-3f)
            assertTrue("no jump at $d", pad.wheelRot - last < 2.5f)
            last = pad.wheelRot
        }
        assertEquals(400f, pad.wheelRot, 0.1f)
        for (d in 398 downTo 0 step 2) pad.apply(c, at(d.toDouble()), t)   // and all the way back
        assertEquals(0f, pad.wheelRot, 0.1f)
    }

    @Test fun fingerKeepsTheWheelAfterSlidingOffTheRim() {
        val c = controls().getValue("WHEEL")
        val t = Tuning(wheelLockDeg = 540f)
        val pad = PadState()
        pad.press(c, Offset(c.cx + c.r * 0.9f, c.cy), t)
        // Far outside the wheel: still the same pointer, so the angle keeps steering.
        pad.apply(c, Offset(c.cx + c.r * 3f * cos(Math.toRadians(45.0)).toFloat(), c.cy + c.r * 3f * sin(Math.toRadians(45.0)).toFloat()), t)
        assertEquals(45f, pad.wheelRot, 0.01f)
    }

    @Test fun wheelHandAndPedalHandWorkTogether() {
        val c = controls()
        val pad = PadState()
        val t = Tuning(wheelLockDeg = 540f)
        val wheel = c.getValue("WHEEL"); val gas = c.getValue("GAS"); val a = c.getValue("A")
        pad.press(wheel, Offset(wheel.cx + wheel.r * 0.8f, wheel.cy), t)
        pad.apply(wheel, Offset(wheel.cx, wheel.cy + wheel.r * 0.8f), t)      // 90 degrees
        pad.press(gas, Offset(gas.cx, gas.box()[3]), t)
        pad.press(a, Offset(a.cx, a.cy), t)
        val s = pad.toControllerState()
        assertTrue(s.lx > 0); assertEquals(255, s.rt); assertEquals(Buttons.A, s.buttons)
        pad.release(gas, t)
        assertTrue(pad.toControllerState().lx > 0)                            // lifting the pedal hand does not touch the wheel
        assertEquals(Buttons.A, pad.toControllerState().buttons)
    }

    // ---- pedals ----

    @Test fun brakeCurveAndSensitivityShapeOnlyTheBrake() {
        val c = controls()
        val brake = c.getValue("BRAKE"); val gas = c.getValue("GAS")
        val t = Tuning(brakeCurve = ResponseCurve.Quadratic, brakeSensitivity = 1f)
        val pad = PadState()
        pad.apply(brake, Offset(brake.cx, (brake.box()[1] + brake.box()[3]) / 2f), t)
        pad.apply(gas, Offset(gas.cx, (gas.box()[1] + gas.box()[3]) / 2f), t)
        assertEquals(64, pad.toControllerState().lt)       // 0.5^2 * 255
        assertEquals(128, pad.toControllerState().rt)      // gas stays linear
        pad.apply(brake, Offset(brake.cx, (brake.box()[1] + brake.box()[3]) / 2f), Tuning(brakeSensitivity = 2f))
        assertEquals(255, pad.toControllerState().lt)
    }

    @Test fun fullPressOnTouchAndReversedDirection() {
        val gas = controls().getValue("GAS")
        val pad = PadState()
        pad.press(gas, Offset(gas.cx, gas.box()[1] + 1f), Tuning(pedalFullPress = true))
        assertEquals(255, pad.toControllerState().rt)
        pad.apply(gas, Offset(gas.cx, gas.box()[1] + 1f), Tuning(pedalPressDown = false))   // up = more pressed
        assertEquals(255, pad.toControllerState().rt)
        pad.apply(gas, Offset(gas.cx, gas.box()[3] - 1f), Tuning(pedalPressDown = false))
        assertEquals(0, pad.toControllerState().rt)
        // The profile's digital-trigger mode also means full press.
        assertTrue(racing.copy(triggerMode = TriggerMode.Digital).tuning().pedalFullPress)
    }

    @Test fun releasedPedalSpringsBackSmoothlyAndNeverSticks() {
        val gas = controls().getValue("GAS")
        val t = Tuning(pedalEase = true)
        val down = Offset(gas.cx, gas.box()[3] - 1f)
        val pad = PadState()
        pad.press(gas, down, t)
        assertEquals(255, pad.toControllerState().rt)
        pad.release(gas, t)
        assertEquals(255, pad.toControllerState().rt)      // eases instead of dropping at once
        var prev = 255; var frames = 0
        while (pad.toControllerState().rt > 0 && frames < 100) {
            assertTrue(pad.tick(1f / 60f, t))
            val v = pad.toControllerState().rt
            assertTrue(v < prev); prev = v; frames++
        }
        assertEquals(0, pad.toControllerState().rt)
        assertTrue("about ${PedalMath.RETURN_SECONDS}s, took $frames frames", frames in 6..12)
        assertFalse(pad.tick(1f / 60f, t))                 // nothing left to do
        // Pressing again while easing takes over immediately and is not pulled down any more.
        pad.press(gas, down, t); pad.release(gas, t); pad.tick(1f / 60f, t)
        pad.press(gas, down, t)
        assertEquals(255, pad.toControllerState().rt)
        pad.tick(1f / 60f, t)
        assertEquals(255, pad.toControllerState().rt)
        // Without easing (previews, tests) release is immediate.
        pad.release(gas, Tuning()); assertEquals(0, pad.toControllerState().rt)
        // reset() clears an easing pedal too.
        pad.press(gas, down, t); pad.release(gas, t); pad.reset()
        assertFalse(pad.tick(1f / 60f, t))
    }

    @Test fun hapticBandsChangeInFourSteps() {
        assertEquals(0, PedalMath.hapticBand(0)); assertEquals(4, PedalMath.hapticBand(255))
        val bands = (0..255).map { PedalMath.hapticBand(it) }
        assertEquals(setOf(0, 1, 2, 3, 4), bands.toSet())
        assertTrue(bands.zipWithNext().all { (a, b) -> b >= a })
    }

    // ---- handbrake ----

    @Test fun handbrakeSendsBByDefaultAndFollowsTheSetting() {
        val default = controls().getValue("HANDBRAKE")
        assertEquals(Buttons.B, default.bit)
        val pad = PadState()
        pad.press(default, Offset(default.cx, default.cy), Tuning())
        assertEquals(Buttons.B, pad.toControllerState().buttons)
        pad.release(default, Tuning())
        assertEquals(0, pad.toControllerState().buttons)
        val bits = mapOf(
            HandbrakeButton.A to Buttons.A, HandbrakeButton.X to Buttons.X, HandbrakeButton.Y to Buttons.Y,
            HandbrakeButton.RB to Buttons.RB, HandbrakeButton.L3 to Buttons.L3, HandbrakeButton.R3 to Buttons.R3,
        )
        for ((h, bit) in bits) assertEquals(h.name, bit, controls(racing.copy(handbrake = h)).getValue("HANDBRAKE").bit)
    }

    @Test fun racingLayoutHasTheExtrasAndNoFakeGearIndicator() {
        val c = controls()
        assertEquals(setOf("WHEEL", "SHIFT_DOWN", "SHIFT_UP", "BRAKE", "GAS", "HANDBRAKE", "DPAD", "Y", "X", "A", "BACK", "START"), c.keys)
        assertEquals("CAM", c.getValue("Y").label)
        assertEquals("NITRO", c.getValue("X").label)
        assertTrue(c.keys.none { it.contains("GEAR", ignoreCase = true) })
        // Wheel on the left, pedals on the right of a landscape phone.
        assertTrue(c.getValue("WHEEL").cx < 2400f / 2f)
        assertTrue(c.getValue("BRAKE").cx > 2400f / 2f && c.getValue("GAS").cx > c.getValue("BRAKE").cx)
        // Paddles flank the wheel rim.
        assertTrue(c.getValue("SHIFT_DOWN").cx < c.getValue("WHEEL").cx && c.getValue("SHIFT_UP").cx > c.getValue("WHEEL").cx)
    }

    @Test fun layoutIsOverlapFreeOnWidePhones() {
        for ((w, h) in listOf(2400f to 1080f, 2460f to 1080f, 2200f to 1000f, 2340f to 1080f, 2160f to 1080f)) {
            val cs = GamepadLayout.resolve(racing, w, h)
            for (c in cs) {
                val b = c.box()
                assertTrue("${c.id} inside at ${w}x$h", b[0] >= 0f && b[1] >= 0f && b[2] <= w && b[3] <= h)
            }
            for (i in cs.indices) for (j in i + 1 until cs.size) {
                assertFalse("${cs[i].id}/${cs[j].id} at ${w}x$h", LayoutMath.controlsOverlap(cs[i], cs[j]))
            }
        }
    }

    // ---- tilt steering ----

    @Test fun rollFromGravityInEveryDisplayRotation() {
        val g = 9.81f
        // Portrait upright (rotation 0): the gravity reading points along +y.
        assertEquals(0f, TiltMath.rollDeg(0f, g, 0)!!, 0.01f)
        // Landscape held upright: ROTATION_90 reads along device -x, ROTATION_270 along +x.
        assertEquals(0f, TiltMath.rollDeg(-g, 0f, 1)!!, 0.01f)
        assertEquals(0f, TiltMath.rollDeg(g, 0f, 3)!!, 0.01f)
        assertEquals(0f, TiltMath.rollDeg(0f, -g, 2)!!, 0.01f)
        // Turn a ROTATION_90 phone 30 degrees clockwise on screen: the true "up" moves towards the screen's left (x' = ay < 0).
        val a = Math.toRadians(30.0)
        val ax = (-g * cos(a)).toFloat(); val ay = (-g * sin(a)).toFloat()
        assertEquals(30f, TiltMath.rollDeg(ax, ay, 1)!!, 0.01f)
        // The same physical tilt in the opposite landscape orientation (readings are mirrored) gives the same roll.
        assertEquals(30f, TiltMath.rollDeg(-ax, -ay, 3)!!, 0.01f)
        // Lying flat: no usable in-plane gravity.
        assertNull(TiltMath.rollDeg(0.3f, 0.2f, 1))
        assertNotNull(TiltMath.rollDeg(0f, 3f, 0))
    }

    @Test fun tiltMapsToWheelWithRecenterSmoothingAndInvert() {
        val s = TiltSteering()
        val lock = 540f   // half = 270 degrees of wheel for 45 degrees of tilt at 1.0x
        assertEquals(0f, s.update(10f, lock, 1f, 0f, false), 0f)                 // first sample = straight ahead
        assertEquals(135f, s.update(32.5f, lock, 1f, 0f, false), 0.01f)         // 22.5 degrees of tilt = half way
        assertEquals(270f, s.update(100f, lock, 1f, 0f, false), 0.01f)          // clamped at lock
        assertEquals(-135f, s.update(-12.5f, lock, 1f, 0f, false), 0.01f)
        assertEquals(-135f, s.update(32.5f, lock, 1f, 0f, true), 0.01f)         // invert flips
        assertEquals(270f, s.update(32.5f, lock, 2f, 0f, false), 0.01f)         // sensitivity 2: full at 22.5 degrees
        // Recenter: the current pose becomes zero.
        s.update(30f, lock, 1f, 0f, false)
        s.recenter()
        assertEquals(0f, s.update(30f, lock, 1f, 0f, false), 0f)
        assertEquals(60f, s.update(40f, lock, 1f, 0f, false), 0.01f)
        // Unusable (flat) readings hold the angle.
        assertEquals(60f, s.update(null, lock, 1f, 0f, false), 0f)
    }

    @Test fun tiltSmoothingIsALowPassThatConverges() {
        val s = TiltSteering()
        s.update(0f, 540f, 1f, 0.8f, false)
        val first = s.update(45f, 540f, 1f, 0.8f, false)
        assertTrue("one step covers only part of the move", first in 1f..135f)
        var v = first
        repeat(60) { v = s.update(45f, 540f, 1f, 0.8f, false) }
        assertEquals(270f, v, 1f)                                              // settles on full lock
        // Roll wrap-around (+179 -> -179) is a 2 degree move, not 358.
        val w = TiltSteering()
        w.update(179f, 540f, 1f, 0f, false)
        assertEquals(12f, w.update(-179f, 540f, 1f, 0f, false), 0.01f)
    }

    @Test fun tiltOwnsTheWheelAndTheTouchWheelIsIgnoredWhileOn() {
        val c = controls().getValue("WHEEL")
        val pad = PadState()
        val t = Tuning(wheelLockDeg = 540f, tiltEnabled = true)
        pad.press(c, Offset(c.cx + c.r * 0.8f, c.cy), t)
        pad.apply(c, Offset(c.cx, c.cy + c.r * 0.8f), t)
        assertEquals(0f, pad.wheelRot, 0f)
        pad.setTilt(135f, t)
        assertEquals(135f, pad.wheelRot, 0f)
        assertEquals(WheelMath.axis(135f, 540f, 0f), pad.toControllerState().lx / 32767f, 0.001f)
        // The wheel does not spring back by itself while tilt steering is on...
        assertFalse(pad.tick(1f, t)); assertEquals(135f, pad.wheelRot, 0f)
        // ...but does once it is off.
        assertTrue(pad.tick(1f / 60f, t.copy(tiltEnabled = false)))
        pad.reset()
        assertEquals(0f, pad.wheelRot, 0f)
    }

    // ---- profile ----

    @Test fun racingSettingsRoundTripAndAreClamped() {
        val p = racing.copy(
            wheelLockDeg = 720f, wheelDeadZone = 0.05f, wheelSensitivity = 1.4f, wheelCurve = ResponseCurve.Cubic, wheelShowAngle = false,
            brakeCurve = ResponseCurve.Quadratic, brakeSensitivity = 1.5f, pedalFullPress = true, pedalPressDown = false,
            handbrake = HandbrakeButton.RB, tiltEnabled = true, tiltSensitivity = 2.5f, tiltSmoothing = 0.7f, tiltInvert = true,
        )
        val r = ProfileCodec.decodeBook(ProfileCodec.encodeBook(ProfileBook.of(listOf(p), "p"))) as BookResult.Ok
        assertEquals(p.sanitized(), r.book.active)
        val wild = racing.copy(wheelDeadZone = 9f, wheelSensitivity = Float.NaN, brakeSensitivity = -3f, tiltSensitivity = 99f, tiltSmoothing = 5f).sanitized()
        assertEquals(Profile.WHEEL_MAX_DEADZONE, wild.wheelDeadZone, 0f)
        assertEquals(1f, wild.wheelSensitivity, 0f)
        assertEquals(Profile.MIN_SENS, wild.brakeSensitivity, 0f)
        assertEquals(Profile.TILT_MAX_SENS, wild.tiltSensitivity, 0f)
        assertEquals(Profile.TILT_MAX_SMOOTHING, wild.tiltSmoothing, 0f)
    }

    @Test fun tiltIsOffByDefaultAndMigrationKeepsTheOldWheelFeel() {
        assertFalse(Profile("p", "p").tiltEnabled)
        assertFalse(ProfileBook.default().active.tiltEnabled)
        val old = """{"schema":3,"active":"a","profiles":[{"id":"a","name":"Race","type":"racing","deadZone":0.2,"sensitivity":1.5,"curve":"Quadratic",
            "wheelLock":720,"wheelSelfCenter":false}]}"""
        val p = (ProfileCodec.decodeBook(old) as BookResult.Ok).book.active
        assertEquals(0.2f, p.wheelDeadZone, 1e-6f)          // the wheel used the stick tuning until now: unchanged
        assertEquals(1.5f, p.wheelSensitivity, 1e-6f)
        assertEquals(ResponseCurve.Quadratic, p.wheelCurve)
        assertEquals(720f, p.wheelLockDeg, 0f)
        assertFalse(p.wheelSelfCenter)
        assertFalse(p.tiltEnabled)
        assertEquals(HandbrakeButton.B, p.handbrake)
        assertTrue(p.pedalPressDown)
        // A brand-new profile gets the small default dead zone instead.
        assertEquals(Profile.WHEEL_DEFAULT_DEADZONE, Profile("n", "n").wheelDeadZone, 0f)
    }
}
