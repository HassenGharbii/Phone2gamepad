package com.phone2gamepad

import androidx.compose.ui.geometry.Offset
import com.phone2gamepad.data.Background
import com.phone2gamepad.data.BackgroundKind
import com.phone2gamepad.data.BackgroundLimits
import com.phone2gamepad.data.BuiltinBackgrounds
import com.phone2gamepad.data.ControlOverride
import com.phone2gamepad.data.ControllerType
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.ProfileBook
import com.phone2gamepad.data.ProfileCodec
import com.phone2gamepad.data.ProfileCodec.BookResult
import com.phone2gamepad.data.SkinCodec
import com.phone2gamepad.data.Skins
import com.phone2gamepad.protocol.Buttons
import com.phone2gamepad.protocol.ControllerState
import com.phone2gamepad.protocol.Dpad
import com.phone2gamepad.protocol.InputMath
import com.phone2gamepad.protocol.PedalMath
import com.phone2gamepad.protocol.Turbo
import com.phone2gamepad.protocol.WheelMath
import com.phone2gamepad.ui.Control
import com.phone2gamepad.ui.GamepadLayout
import com.phone2gamepad.ui.Kind
import com.phone2gamepad.ui.LayoutMath
import com.phone2gamepad.ui.PadState
import com.phone2gamepad.ui.Tuning
import com.phone2gamepad.ui.tuning
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ControllerTypeTest {
    private val W = 2200f
    private val H = 1000f
    private fun profile(t: ControllerType) = Profile("p", "p", controllerType = t)
    private fun controls(t: ControllerType) = GamepadLayout.resolve(profile(t), W, H).associateBy { it.id }
    private val t = Tuning()

    private fun press(pad: PadState, c: Control, p: Offset = c.center(), tuning: Tuning = t) {
        pad.press(c, p, tuning)
    }

    private fun Control.center() = Offset(cx, cy)

    // ---- presets ----

    @Test fun everyTypeLaysOutExactlyItsControlIds() {
        for (type in ControllerType.entries) {
            val specIds = GamepadLayout.specsFor(type).map { it.id }
            assertEquals(type.name, type.controlIds.toSet(), specIds.toSet())
            assertEquals(type.name, type.controlIds.size, specIds.size)
            assertEquals(type.name, type.controlIds.size, controls(type).size)
        }
    }

    @Test fun xboxIsStillTheOriginalLayout() {
        assertEquals(GamepadLayout.specs, GamepadLayout.specsFor(ControllerType.Xbox))
        assertEquals(15, controls(ControllerType.Xbox).size)
    }

    @Test fun layoutsHaveNoOverlapsAndStayInsideWidePhoneScreens() {
        for (type in listOf(ControllerType.Racing, ControllerType.Fighting)) {
            for ((w, h) in listOf(2400f to 1080f, 2460f to 1080f, 2200f to 1000f, 2340f to 1080f)) {
                val cs = GamepadLayout.resolve(profile(type), w, h)
                for (c in cs) {
                    val b = c.box()
                    assertTrue("${type.name} ${c.id} outside at ${w}x$h", b[0] >= 0f && b[1] >= 0f && b[2] <= w && b[3] <= h)
                }
                for (i in cs.indices) for (j in i + 1 until cs.size) {
                    assertFalse("${type.name}: ${cs[i].id} overlaps ${cs[j].id} at ${w}x$h", LayoutMath.controlsOverlap(cs[i], cs[j]))
                }
            }
        }
    }

    // ---- racing ----

    @Test fun racingControlsDriveTheRightOutputs() {
        val c = controls(ControllerType.Racing)
        val pad = PadState()
        val gas = c.getValue("GAS")
        val gb = gas.box()
        press(pad, gas, Offset(gas.cx, gb[1] + 1f))       // top of the pedal = flat out
        assertEquals(255, pad.toControllerState().rt)
        pad.apply(gas, Offset(gas.cx, gb[3]), t)           // bottom = released
        assertEquals(0, pad.toControllerState().rt)
        pad.apply(gas, Offset(gas.cx, (gb[1] + gb[3]) / 2f), t)
        assertTrue(kotlin.math.abs(pad.toControllerState().rt - 128) <= 1)
        pad.release(gas, t)
        assertEquals(0, pad.toControllerState().rt)

        val brake = c.getValue("BRAKE")
        press(pad, brake, Offset(brake.cx, brake.box()[1] + 1f))
        assertEquals(255, pad.toControllerState().lt)
        assertEquals(0, pad.toControllerState().rt)
        pad.release(brake, t)

        press(pad, c.getValue("SHIFT_UP")); assertEquals(Buttons.RB, pad.toControllerState().buttons)
        press(pad, c.getValue("SHIFT_DOWN")); assertEquals(Buttons.RB or Buttons.LB, pad.toControllerState().buttons)
        press(pad, c.getValue("A")); press(pad, c.getValue("START"))
        assertEquals(Buttons.RB or Buttons.LB or Buttons.A or Buttons.START, pad.toControllerState().buttons)
    }

    @Test fun pedalRangesAndDigitalMode() {
        assertEquals(255, PedalMath.fromPosition(100f, 100f, 400f, false))
        assertEquals(0, PedalMath.fromPosition(500f, 100f, 400f, false))
        assertEquals(128, PedalMath.fromPosition(300f, 100f, 400f, false))
        assertEquals(255, PedalMath.fromPosition(10f, 100f, 400f, false))   // above the pedal: clamped
        assertEquals(0, PedalMath.fromPosition(900f, 100f, 400f, false))    // below the pedal: clamped
        assertEquals(255, PedalMath.fromPosition(480f, 100f, 400f, true))   // digital: any touch is a full press
        assertEquals(0, PedalMath.fromPosition(1f, 100f, 0f, false))
    }

    @Test fun wheelAngleDeltaWrapsTheShortWay() {
        assertEquals(20f, WheelMath.deltaDeg(170f, -170f), 1e-4f)
        assertEquals(-20f, WheelMath.deltaDeg(-170f, 170f), 1e-4f)
        assertEquals(90f, WheelMath.angleDeg(0f, 10f), 1e-4f)      // straight down on screen = +90 (clockwise)
        assertEquals(0f, WheelMath.angleDeg(10f, 0f), 1e-4f)
        assertEquals(-90f, WheelMath.angleDeg(0f, -10f), 1e-4f)
    }

    @Test fun wheelRotationMapsToAxisAndIsClampedToLock() {
        val lock = 540f
        assertEquals(0f, WheelMath.axis(0f, lock, 0f), 1e-6f)
        assertEquals(1f, WheelMath.axis(270f, lock, 0f), 1e-6f)
        assertEquals(-1f, WheelMath.axis(-270f, lock, 0f), 1e-6f)
        assertEquals(0.5f, WheelMath.axis(135f, lock, 0f), 1e-6f)
        assertEquals(1f, WheelMath.axis(1000f, lock, 0f), 1e-6f)
        assertEquals(270f, WheelMath.rotate(250f, 100f, lock), 1e-4f)
        assertEquals(-270f, WheelMath.rotate(-250f, -100f, lock), 1e-4f)
        // A smaller lock reaches full steering sooner; sensitivity and dead zone follow the stick tuning.
        assertEquals(1f, WheelMath.axis(90f, 180f, 0f), 1e-6f)
        assertEquals(0f, WheelMath.axis(10f, 540f, 0.1f), 1e-6f)
        assertEquals(1f, WheelMath.axis(200f, 540f, 0f, 2f), 1e-6f)
    }

    @Test fun selfCenteringReturnsToZeroWithoutOvershoot() {
        var rot = 270f
        var steps = 0
        while (rot != 0f && steps < 1000) { val n = WheelMath.centerStep(rot, 1f / 60f, 540f); assertTrue(n in 0f..rot); rot = n; steps++ }
        assertEquals(0f, rot, 0f)
        assertTrue("full lock should take about 0.3 s", steps in 17..20)
        assertEquals(0f, WheelMath.centerStep(-5f, 0.5f, 540f), 0f)
        assertEquals(-200f, WheelMath.centerStep(-270f, 70f / 270f * 0.3f, 540f), 0.01f)
    }

    @Test fun draggingTheWheelSteersAndSpringsBack() {
        val wheel = controls(ControllerType.Racing).getValue("WHEEL")
        val pad = PadState()
        val tn = Tuning(deadZone = 0f, wheelLockDeg = 540f, wheelSelfCenter = true)
        fun at(deg: Float) = Offset(wheel.cx + wheel.r * 0.8f * cos(Math.toRadians(deg.toDouble())).toFloat(), wheel.cy + wheel.r * 0.8f * sin(Math.toRadians(deg.toDouble())).toFloat())
        pad.press(wheel, at(0f), tn)                          // grab at 3 o'clock
        assertEquals(0, pad.toControllerState().lx)
        for (d in 10..135 step 5) pad.apply(wheel, at(d.toFloat()), tn)   // turn 135 degrees clockwise
        assertEquals(135f, pad.wheelRot, 0.01f)
        assertEquals(InputMath.toAxis(0.5f), pad.toControllerState().lx)
        assertEquals(0, pad.toControllerState().ly)
        for (d in 140..260 step 5) pad.apply(wheel, at(d.toFloat()), tn)  // keep going past the half way point of the lock
        for (d in 265..500 step 5) pad.apply(wheel, at(d.toFloat()), tn)
        assertEquals(270f, pad.wheelRot, 0.01f)               // clamped at half of the lock-to-lock range
        assertEquals(32767, pad.toControllerState().lx)
        // Reversing from the limit turns the wheel back immediately (the reference follows the finger).
        pad.apply(wheel, at(490f), tn)
        assertEquals(260f, pad.wheelRot, 0.01f)
        // Release: nothing happens until the frame ticker runs, then it springs back to exactly zero.
        pad.release(wheel, tn)
        assertEquals(260f, pad.wheelRot, 0.01f)
        var n = 0
        while (pad.tick(1f / 60f, tn) && n < 200) n++
        assertEquals(0f, pad.wheelRot, 0f)
        assertEquals(0, pad.toControllerState().lx)
        assertFalse(pad.tick(1f / 60f, tn))
    }

    @Test fun wheelStaysPutWhenSelfCenteringIsOff() {
        val wheel = controls(ControllerType.Racing).getValue("WHEEL")
        val pad = PadState()
        val tn = Tuning(deadZone = 0f, wheelLockDeg = 360f, wheelSelfCenter = false)
        fun at(deg: Double) = Offset(wheel.cx + wheel.r * 0.7f * cos(Math.toRadians(deg)).toFloat(), wheel.cy + wheel.r * 0.7f * sin(Math.toRadians(deg)).toFloat())
        pad.press(wheel, at(0.0), tn)
        for (d in 5..90 step 5) pad.apply(wheel, at(d.toDouble()), tn)
        pad.release(wheel, tn)
        assertFalse(pad.tick(1f, tn))
        assertEquals(90f, pad.wheelRot, 0.01f)
        assertEquals(InputMath.toAxis(0.5f), pad.toControllerState().lx)
        // Touching again continues from where it was left.
        pad.press(wheel, at(0.0), tn)
        pad.apply(wheel, at(30.0), tn)
        assertEquals(120f, pad.wheelRot, 0.01f)
    }

    @Test fun touchesNearTheWheelHubDoNotSteer() {
        val wheel = controls(ControllerType.Racing).getValue("WHEEL")
        val pad = PadState()
        pad.press(wheel, Offset(wheel.cx + wheel.r * 0.5f, wheel.cy), Tuning(deadZone = 0f))
        pad.apply(wheel, Offset(wheel.cx + 2f, wheel.cy + 2f), Tuning(deadZone = 0f))     // inside the hub: ignored
        pad.apply(wheel, Offset(wheel.cx, wheel.cy + wheel.r * 0.5f), Tuning(deadZone = 0f)) // 90 degrees from the grab point
        assertEquals(90f, pad.wheelRot, 0.01f)
    }

    // ---- fighting ----

    @Test fun fightingAttackButtonsAndExtraTriggersMapToXboxState() {
        val c = controls(ControllerType.Fighting)
        val pad = PadState()
        for ((id, bit) in listOf("X" to Buttons.X, "Y" to Buttons.Y, "RB" to Buttons.RB, "A" to Buttons.A, "B" to Buttons.B, "LB" to Buttons.LB)) {
            pad.reset(); press(pad, c.getValue(id))
            assertEquals(id, bit, pad.toControllerState().buttons)
        }
        pad.reset()
        press(pad, c.getValue("RT")); assertEquals(255, pad.toControllerState().rt); assertEquals(0, pad.toControllerState().lt)
        press(pad, c.getValue("LT")); assertEquals(255, pad.toControllerState().lt)
        pad.release(c.getValue("RT"), t); assertEquals(0, pad.toControllerState().rt)
        pad.release(c.getValue("LT"), t); assertEquals(0, pad.toControllerState().lt)
        // Six attack buttons together, exactly like a six-button arcade layout.
        pad.reset()
        for (id in listOf("X", "Y", "RB", "A", "B")) press(pad, c.getValue(id))
        press(pad, c.getValue("RT"))
        assertEquals(Buttons.X or Buttons.Y or Buttons.RB or Buttons.A or Buttons.B, pad.toControllerState().buttons)
        assertEquals(255, pad.toControllerState().rt)
        press(pad, c.getValue("START")); press(pad, c.getValue("BACK"))
        assertEquals(Buttons.START or Buttons.BACK, pad.toControllerState().buttons and (Buttons.START or Buttons.BACK))
    }

    @Test fun fightingDpadHasEightSectors() {
        val d = controls(ControllerType.Fighting).getValue("DPAD")
        assertEquals(Kind.Dpad, d.kind)
        val pad = PadState()
        val r = d.r * 0.8f
        val expected = mapOf(
            0.0 to Dpad.RIGHT, 45.0 to (Dpad.RIGHT or Dpad.DOWN), 90.0 to Dpad.DOWN, 135.0 to (Dpad.LEFT or Dpad.DOWN),
            180.0 to Dpad.LEFT, 225.0 to (Dpad.LEFT or Dpad.UP), 270.0 to Dpad.UP, 315.0 to (Dpad.RIGHT or Dpad.UP),
        )
        for ((deg, bits) in expected) {
            val p = Offset(d.cx + r * cos(Math.toRadians(deg)).toFloat(), d.cy + r * sin(Math.toRadians(deg)).toFloat())
            pad.apply(d, p, t)
            assertEquals("$deg degrees", bits, pad.toControllerState().dpad)
        }
        pad.apply(d, Offset(d.cx + 3f, d.cy + 3f), t)
        assertEquals("dead centre", 0, pad.toControllerState().dpad)
        pad.release(d, t)
        assertEquals(0, pad.toControllerState().dpad)
    }

    @Test fun turboPulsesHeldAttackButtonsOnlyWhileLatchedOn() {
        assertTrue(Turbo.pressedPhase(0)); assertTrue(Turbo.pressedPhase(49)); assertFalse(Turbo.pressedPhase(50)); assertFalse(Turbo.pressedPhase(99)); assertTrue(Turbo.pressedPhase(100))
        val c = controls(ControllerType.Fighting)
        val pad = PadState()
        press(pad, c.getValue("X")); press(pad, c.getValue("RT")); press(pad, c.getValue("START"))
        // Off by default: the held state is sent unchanged at any time.
        assertFalse(pad.turboOn)
        assertEquals(pad.toControllerState(0L), pad.toControllerState(75L))
        press(pad, c.getValue("TURBO"))
        assertTrue(pad.turboOn)
        val down = pad.toControllerState(10L)
        val up = pad.toControllerState(60L)
        assertEquals(Buttons.X or Buttons.START, down.buttons); assertEquals(255, down.rt)
        assertEquals(Buttons.START, up.buttons)           // attack button released in the "up" half; Start is not repeated
        assertEquals(0, up.rt)
        pad.apply(c.getValue("TURBO"), c.getValue("TURBO").center(), t)  // dragging a finger over it must not flip it again
        assertTrue(pad.turboOn)
        press(pad, c.getValue("TURBO"))
        assertFalse(pad.turboOn)
        assertEquals(Buttons.X or Buttons.START, pad.toControllerState(60L).buttons)
        // Turbo never survives a reset (focus loss, disconnect, type switch).
        press(pad, c.getValue("TURBO")); pad.reset()
        assertFalse(pad.turboOn)
    }

    @Test fun turboLeavesSticksAndDpadAlone() {
        val s = ControllerState(buttons = Buttons.A or Buttons.L3, dpad = Dpad.UP, lx = 1234, ly = -5, rx = 7, ry = 8, lt = 9, rt = 10)
        val up = Turbo.apply(s, 60L)
        assertEquals(Buttons.L3, up.buttons)
        assertEquals(Dpad.UP, up.dpad); assertEquals(1234, up.lx); assertEquals(-5, up.ly); assertEquals(7, up.rx); assertEquals(8, up.ry)
        assertEquals(0, up.lt); assertEquals(0, up.rt)
        assertEquals(s, Turbo.apply(s, 20L))
    }

    @Test fun resetReleasesEveryTypeOfControl() {
        for (type in ControllerType.entries) {
            val pad = PadState()
            for (c in controls(type).values) pad.press(c, c.center(), t)
            pad.reset()
            assertEquals(type.name, ControllerState(), pad.toControllerState(0L))
            assertEquals(0f, pad.wheelRot, 0f)
        }
    }

    // ---- profiles: per-type overrides, schema, background ----

    @Test fun overridesAreKeptPerControllerType() {
        val xbox = Profile("p", "p").withOverride("A", ControlOverride(fx = 0.5f, fy = 0.5f))
        val racing = xbox.copy(controllerType = ControllerType.Racing)
        assertTrue(racing.override("A").isDefault)                 // Xbox's A is not the racing A
        val moved = racing.withOverride("A", ControlOverride(scale = 1.5f)).withOverride("GAS", ControlOverride(opacity = 0.5f))
        assertEquals(setOf("A", "racing.A", "racing.GAS"), moved.controls.keys)
        val back = moved.copy(controllerType = ControllerType.Xbox)
        assertEquals(0.5f, back.override("A").fx)                  // untouched by the racing edits
        assertEquals(1f, back.override("A").scale)
        // Restoring the default layout only touches the current type.
        val racingReset = moved.resetLayout()
        assertEquals(setOf("A"), racingReset.controls.keys)
        assertEquals(setOf("racing.A", "racing.GAS"), back.resetLayout().controls.keys)
    }

    @Test fun profileRoundTripsTypeBackgroundAndWheelSettings() {
        val p = Profile(
            "p", "Race", controllerType = ControllerType.Racing,
            background = Background(BackgroundKind.Builtin, BuiltinBackgrounds.WAVES, 0.5f, 1.5f, 0.25f, -0.5f),
            wheelLockDeg = 720f, wheelSelfCenter = false,
        ).withOverride("GAS", ControlOverride(scale = 1.2f))
        val book = ProfileBook.of(listOf(p), "p")
        val r = ProfileCodec.decodeBook(ProfileCodec.encodeBook(book)) as BookResult.Ok
        assertEquals(book, r.book)
        assertEquals(ControllerType.Racing, r.book.active.controllerType)
        assertEquals(720f, r.book.active.wheelLockDeg)
        assertFalse(r.book.active.wheelSelfCenter)
    }

    @Test fun existingProfilesMigrateToXboxWithNoBackground() {
        val old = """{"schema":2,"active":"a","profiles":[{"id":"a","name":"Mine","skin":"tactical","leftHanded":true,
            "controls":{"A":{"fx":0.4,"fy":0.5}}}]}"""
        val r = ProfileCodec.decodeBook(old) as BookResult.Ok
        val p = r.book.active
        assertEquals(ControllerType.Xbox, p.controllerType)
        assertEquals(Background.None, p.background)
        assertEquals(Profile.WHEEL_DEFAULT_LOCK, p.wheelLockDeg)
        assertEquals("tactical", p.skinId)
        assertTrue(p.leftHanded)
        assertEquals(0.4f, p.override("A").fx)
        assertEquals(3, ProfileCodec.SCHEMA)
        // v1 files still upgrade all the way.
        val v1 = ProfileCodec.decodeBook("""{"schema":1,"profiles":[{"id":"a","name":"old"}]}""") as BookResult.Ok
        assertEquals(ControllerType.Xbox, v1.book.active.controllerType)
    }

    @Test fun unknownTypeAndJunkOverridesAreRepaired() {
        val text = """{"schema":3,"profiles":[{"id":"a","name":"x","type":"hovercraft",
            "controls":{"racing.GAS":{"scale":9},"fighting.NOPE":{"scale":2},"bogus":{"scale":2}}}]}"""
        val p = (ProfileCodec.decodeBook(text) as BookResult.Ok).book.active
        assertEquals(ControllerType.Xbox, p.controllerType)
        assertEquals(setOf("racing.GAS"), p.controls.keys)
        assertEquals(Profile.MAX_SCALE, p.controls.getValue("racing.GAS").scale, 0f)
        assertEquals(Profile.WHEEL_MIN_LOCK, Profile("a", "a", wheelLockDeg = 5f).sanitized().wheelLockDeg)
        assertEquals(Profile.WHEEL_MAX_LOCK, Profile("a", "a", wheelLockDeg = 5000f).sanitized().wheelLockDeg)
    }

    @Test fun backgroundReferencesAreValidatedAndClamped() {
        for (bad in listOf("../x.jpg", "a/b.jpg", "a\\b.jpg", "x.png", ".jpg", "", "x".repeat(70) + ".jpg", "a b.jpg", "..jpg")) {
            assertEquals(bad, Background.None.kind, Background(BackgroundKind.Image, bad).sanitized().kind)
        }
        assertEquals(BackgroundKind.Image, Background(BackgroundKind.Image, "0123abcd-EF_9.jpg").sanitized().kind)
        assertEquals(BackgroundKind.None, Background(BackgroundKind.Builtin, "nope").sanitized().kind)
        assertEquals(BackgroundKind.Builtin, Background(BackgroundKind.Builtin, BuiltinBackgrounds.CIRCUIT).sanitized().kind)
        val c = Background(BackgroundKind.Builtin, BuiltinBackgrounds.AURORA, Float.NaN, 99f, -7f, Float.POSITIVE_INFINITY).sanitized()
        assertEquals(Background.DEFAULT_SCRIM, c.scrim, 0f); assertEquals(Background.MAX_ZOOM, c.zoom, 0f)
        assertEquals(-1f, c.offsetX, 0f); assertEquals(0f, c.offsetY, 0f)
        assertEquals(Background.MAX_SCRIM, Background(BackgroundKind.Builtin, BuiltinBackgrounds.AURORA, 5f).sanitized().scrim, 0f)
        assertEquals(1f, Background(BackgroundKind.Builtin, BuiltinBackgrounds.AURORA, 0.1f, 0.1f).sanitized().zoom, 0f)
    }

    @Test fun decodedProfilesNeverCarryAPathTraversalReference() {
        val text = """{"schema":3,"profiles":[{"id":"a","name":"x","background":{"kind":"Image","ref":"../../shared_prefs/secrets.jpg"}}]}"""
        assertEquals(BackgroundKind.None, (ProfileCodec.decodeBook(text) as BookResult.Ok).book.active.background.kind)
    }

    @Test fun exportDropsPrivateImagesButKeepsBuiltInArt() {
        val img = Profile("p", "Pic", controllerType = ControllerType.Fighting, background = Background(BackgroundKind.Image, "abc123.jpg", 0.4f, 2f))
        val text = ProfileCodec.encodeExport(img)
        assertFalse("the picture reference must not be exported", text.contains("abc123"))
        val back = ProfileCodec.decodeExport(text)!!
        assertEquals(BackgroundKind.None, back.background.kind)
        assertEquals(ControllerType.Fighting, back.controllerType)

        val art = img.copy(background = Background(BackgroundKind.Builtin, BuiltinBackgrounds.AURORA, 0.2f))
        assertEquals(BackgroundKind.Builtin, ProfileCodec.decodeExport(ProfileCodec.encodeExport(art))!!.background.kind)

        // A hand-edited export that names a local picture is not trusted either.
        val forged = JSONObject(ProfileCodec.encodeExport(art))
        forged.getJSONObject("profile").put("background", JSONObject().put("kind", "Image").put("ref", "abc123.jpg"))
        assertEquals(BackgroundKind.None, ProfileCodec.decodeExport(forged.toString())!!.background.kind)
        // Importing into a book keeps the picture out as well.
        val imported = ProfileBook.default().importProfile(back)!!.active
        assertNull(imported.background.imageRef)
    }

    @Test fun skinFilesStayDataOnlyWithNoPicturesOrBackgrounds() {
        for (s in Skins.BUNDLED) {
            val text = SkinCodec.encodeExport(s)
            assertFalse(text, text.contains("background", ignoreCase = true) && text.contains("\"image\"", ignoreCase = true))
            assertFalse(text.contains(".jpg"))
        }
        // A skin file that tries to smuggle a picture reference in is rejected or stripped: nothing of it survives a re-export.
        val tampered = JSONObject(SkinCodec.encodeExport(Skins.TACTICAL.copy(id = "x", name = "Mine")))
        tampered.getJSONObject("skin").put("image", "abc123.jpg").put("background", JSONObject().put("kind", "Image").put("ref", "abc123.jpg"))
        val r = SkinCodec.decodeImport(tampered.toString())
        if (r is SkinCodec.ImportResult.Ok) assertFalse(SkinCodec.encodeExport(r.skin).contains("abc123"))
    }

    // ---- images ----

    @Test fun imageSizeLimitsAreEnforcedAndLargeImagesAreScaledDown() {
        assertNull(BackgroundLimits.targetSize(0, 0))
        assertNull(BackgroundLimits.targetSize(8, 4000))
        assertNull(BackgroundLimits.targetSize(20000, 100))
        assertNull(BackgroundLimits.targetSize(16385, 16385))
        assertNull(BackgroundLimits.targetSize(16000, 16000))      // 256 MP exceeds the pixel cap
        assertEquals(1000 to 600, BackgroundLimits.targetSize(1000, 600))        // small: untouched
        assertEquals(2048 to 2048, BackgroundLimits.targetSize(2048, 2048))
        assertEquals(2048 to 1024, BackgroundLimits.targetSize(4096, 2048))
        assertEquals(1024 to 2048, BackgroundLimits.targetSize(3000, 6000))
        val big = BackgroundLimits.targetSize(12000, 9000)!!
        assertEquals(2048, big.first); assertTrue(big.second in 1535..1537)
        assertTrue(BackgroundLimits.fileSizeAllowed(1)); assertTrue(BackgroundLimits.fileSizeAllowed(24L * 1024 * 1024))
        assertFalse(BackgroundLimits.fileSizeAllowed(0)); assertFalse(BackgroundLimits.fileSizeAllowed(24L * 1024 * 1024 + 1))
    }

    @Test fun layoutEditorKeepsWorkingForEveryType() {
        for (type in ControllerType.entries) {
            val p = profile(type).withOverride(type.controlIds.first(), ControlOverride(fx = 0.5f, fy = 0.5f, scale = 1.3f))
            val all = GamepadLayout.resolveAll(p, W, H)
            assertEquals(type.controlIds.size, all.size)
            val moved = all.first { it.id == type.controlIds.first() }
            assertEquals(W * 0.5f, moved.cx, 1f)
            assertEquals(H * 0.5f, moved.cy, 1f)
            val hidden = p.withOverride(type.controlIds.last(), ControlOverride(visible = false))
            assertEquals(type.controlIds.size - 1, GamepadLayout.resolve(hidden, W, H).size)
            assertEquals(type.controlIds.size, GamepadLayout.resolveAll(hidden, W, H).size)
            assertNotNull(p.tuning())
        }
    }
}
