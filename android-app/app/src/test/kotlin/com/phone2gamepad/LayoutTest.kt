package com.phone2gamepad

import androidx.compose.ui.geometry.Offset
import com.phone2gamepad.data.ControlIds
import com.phone2gamepad.data.ControlOverride
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.TriggerMode
import com.phone2gamepad.protocol.ResponseCurve
import com.phone2gamepad.ui.GamepadLayout
import com.phone2gamepad.ui.LayoutMath
import com.phone2gamepad.ui.PadState
import com.phone2gamepad.ui.Tuning
import com.phone2gamepad.ui.tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val std = Profile("s", "Standard")

    @Test fun specsCoverExactlyTheProfileControlIds() {
        assertEquals(ControlIds.ALL.toSet(), GamepadLayout.specs.map { it.id }.toSet())
        assertEquals(ControlIds.ALL.size, GamepadLayout.specs.size)
    }

    @Test fun defaultLayoutMatchesTheOriginalHandPlacedLayout() {
        val w = 2200f
        val h = 1000f
        val c = GamepadLayout.standard(w, h).associateBy { it.id }
        assertEquals(15, c.size)
        assertEquals(h * 0.20f, c.getValue("LS").r, 0.01f)
        assertEquals(w * 0.27f, c.getValue("LS").cx, 0.01f)
        assertEquals(h * 0.40f, c.getValue("LS").cy, 0.01f)
        assertEquals(h * 0.085f, c.getValue("A").r, 0.01f)
        assertEquals(w * 0.80f, c.getValue("A").cx, 0.01f)
        assertEquals(h * (0.58f + 0.165f), c.getValue("A").cy, 0.01f)
        assertEquals(w * 0.80f - h * 0.165f, c.getValue("X").cx, 0.01f)
        assertEquals(w * 0.80f + h * 0.165f, c.getValue("B").cx, 0.01f)
        val lt = c.getValue("LT").rect!!
        assertEquals(w * 0.02f, lt[0], 0.01f)
        assertEquals(h * 0.03f, lt[1], 0.01f)
        assertEquals(w * 0.15f, lt[2], 0.01f)
        assertEquals(h * 0.22f, lt[3], 0.01f)
        val back = c.getValue("BACK").rect!!
        assertEquals(w * 0.395f, back[0], 0.01f)
        assertEquals(w * 0.485f, back[2], 0.01f)
    }

    // Wide modern phones (about 2:1 and wider). On 16:9 screens the original layout has a small, pre-existing overlap
    // between the right stick ring and the X button (circles ~60 px into each other at 1920x1080); it was kept
    // unchanged on purpose and the editor tolerates it (see placementBlocksNewOverlapsButToleratesExistingOnes).
    @Test fun defaultLayoutHasNoOverlapsOnWidePhoneAspectRatios() {
        for ((w, h) in listOf(2400f to 1080f, 2460f to 1080f, 2200f to 1000f, 2340f to 1080f)) {
            val cs = GamepadLayout.standard(w, h)
            for (i in cs.indices) for (j in i + 1 until cs.size) {
                assertFalse("${cs[i].id} overlaps ${cs[j].id} at ${w}x$h", LayoutMath.controlsOverlap(cs[i], cs[j]))
            }
        }
    }

    @Test fun defaultLayoutStaysInsideTheScreen() {
        val w = 2400f
        val h = 1080f
        for (c in GamepadLayout.standard(w, h)) {
            val b = c.box()
            assertTrue(c.id, b[0] >= 0f && b[1] >= 0f && b[2] <= w && b[3] <= h)
        }
    }

    @Test fun hiddenControlsAreNotResolvedForPlayButStillForEditing() {
        val p = std.withOverride("L3", ControlOverride(visible = false)).withOverride("R3", ControlOverride(visible = false))
        assertEquals(13, GamepadLayout.resolve(p, 2000f, 1000f).size)
        assertEquals(15, GamepadLayout.resolveAll(p, 2000f, 1000f).size)
        assertFalse(GamepadLayout.resolve(p, 2000f, 1000f).any { it.id == "L3" })
    }

    @Test fun overridesMoveScaleAndKeepFractionsAcrossScreenSizes() {
        val p = std.withOverride("A", ControlOverride(fx = 0.5f, fy = 0.25f, scale = 2f))
        for ((w, h) in listOf(2000f to 1000f, 1600f to 900f)) {
            val a = GamepadLayout.resolve(p, w, h).first { it.id == "A" }
            assertEquals(w * 0.5f, a.cx, 0.01f)
            assertEquals(h * 0.25f, a.cy, 0.01f)
            assertEquals(h * 0.085f * 2f, a.r, 0.01f)
        }
    }

    @Test fun globalScaleScalesSizesAroundTheCentre() {
        val a1 = GamepadLayout.resolve(std, 2000f, 1000f).first { it.id == "BACK" }
        val a2 = GamepadLayout.resolve(std.copy(globalScale = 1.5f), 2000f, 1000f).first { it.id == "BACK" }
        assertEquals(a1.cx, a2.cx, 0.01f)
        assertEquals((a1.rect!![2] - a1.rect!![0]) * 1.5f, a2.rect!![2] - a2.rect!![0], 0.01f)
    }

    @Test fun leftHandedMirrorsPositionsHorizontally() {
        val w = 2000f
        val h = 1000f
        val n = GamepadLayout.resolve(std, w, h).associateBy { it.id }
        val m = GamepadLayout.resolve(std.copy(leftHanded = true), w, h).associateBy { it.id }
        for (id in ControlIds.ALL) {
            assertEquals(id, w - n.getValue(id).cx, m.getValue(id).cx, 0.01f)
            assertEquals(id, n.getValue(id).cy, m.getValue(id).cy, 0.01f)
            assertEquals(id, n.getValue(id).box().let { it[2] - it[0] }, m.getValue(id).box().let { it[2] - it[0] }, 0.01f)
        }
    }

    @Test fun perControlOpacityIsCarried() {
        val a = GamepadLayout.resolve(std.withOverride("B", ControlOverride(opacity = 0.4f)), 2000f, 1000f).first { it.id == "B" }
        assertEquals(0.4f, a.opacity, 1e-6f)
    }

    @Test fun oversizedControlsAreKeptOnScreen() {
        val p = std.withOverride("RS", ControlOverride(fx = 1f, fy = 1f, scale = 2f))
        val b = GamepadLayout.resolve(p, 2000f, 1000f).first { it.id == "RS" }.box()
        assertTrue(b[0] >= 0f && b[1] >= 0f && b[2] <= 2000f && b[3] <= 1000f)
    }

    // ---- editor geometry ----

    @Test fun overlapAndGap() {
        val a = floatArrayOf(0f, 0f, 10f, 10f)
        assertTrue(LayoutMath.overlaps(a, floatArrayOf(9f, 9f, 20f, 20f)))
        assertFalse(LayoutMath.overlaps(a, floatArrayOf(10f, 0f, 20f, 10f))) // touching edges only
        assertTrue(LayoutMath.overlaps(a, floatArrayOf(11f, 0f, 20f, 10f), gap = 2f))
    }

    @Test fun shapeAwareOverlapIsTighterThanBoundingBoxes() {
        val cs = GamepadLayout.standard(2400f, 1080f).associateBy { it.id }
        // Diagonal face buttons: bounding boxes touch, the circles do not.
        assertTrue(LayoutMath.overlaps(cs.getValue("A").box(), cs.getValue("X").box(), 2f))
        assertFalse(LayoutMath.controlsOverlap(cs.getValue("A"), cs.getValue("X"), 2f))
        // Circle vs rectangle: LB sits above the left stick without touching; dragging it down onto the stick does.
        val lb = cs.getValue("LB")
        val ls = cs.getValue("LS")
        assertFalse(LayoutMath.controlsOverlap(lb, ls))
        assertTrue(LayoutMath.controlsOverlap(lb.withCentre(ls.cx, ls.cy), ls))
        // Two circles exactly r1+r2 apart do not overlap; slightly closer do.
        val a = cs.getValue("A")
        assertFalse(LayoutMath.controlsOverlap(a, a.withCentre(a.cx + 2 * a.r, a.cy)))
        assertTrue(LayoutMath.controlsOverlap(a, a.withCentre(a.cx + 2 * a.r - 1f, a.cy)))
    }

    @Test fun clampCentreKeepsBoxInside() {
        assertEquals(50f to 40f, LayoutMath.clampCentre(-100f, -100f, 50f, 40f, 1000f, 500f))
        assertEquals(950f to 460f, LayoutMath.clampCentre(5000f, 5000f, 50f, 40f, 1000f, 500f))
        assertEquals(500f to 250f, LayoutMath.clampCentre(0f, 0f, 900f, 900f, 1000f, 500f)) // bigger than screen: centred
    }

    @Test fun placementBlocksNewOverlapsButToleratesExistingOnes() {
        val cs = GamepadLayout.standard(2400f, 1080f)
        val a = cs.first { it.id == "A" }
        val b = cs.first { it.id == "B" }
        // Moving A onto B is blocked; moving it to free space is allowed.
        assertFalse(LayoutMath.placementAllowed(a, a.withCentre(b.cx - 5f, b.cy), cs))
        assertTrue(LayoutMath.placementAllowed(a, a.withCentre(a.cx, a.cy - 20f), cs))
        // Growing A until it hits a neighbour is blocked.
        assertFalse(LayoutMath.placementAllowed(a, a.withSizeFactor(2.5f), cs))
        assertTrue(LayoutMath.placementAllowed(a, a.withSizeFactor(1.1f), cs))
        // A control that already overlaps may move as long as it adds no new overlap.
        val overlapped = cs.map { if (it.id == "B") it.withCentre(a.cx + 10f, a.cy) else it }
        val b2 = overlapped.first { it.id == "B" }
        assertTrue(LayoutMath.placementAllowed(b2, b2.withCentre(b2.cx + 3f, b2.cy), overlapped))
    }

    @Test fun hiddenControlsDoNotBlockPlacement() {
        val p = std.withOverride("B", ControlOverride(visible = false))
        val cs = GamepadLayout.resolveAll(p, 2400f, 1080f)
        val a = cs.first { it.id == "A" }
        val b = cs.first { it.id == "B" }
        assertTrue(LayoutMath.placementAllowed(a, a.withCentre(b.cx, b.cy + 1f), cs))
    }

    // ---- profile tuning applied on the gamepad input path ----

    @Test fun padStateAppliesDeadZoneSensitivityAndCurveFromTheProfile() {
        val c = GamepadLayout.standard(2000f, 1000f).first { it.id == "LS" }
        fun lx(t: Tuning, frac: Float): Float {
            val s = PadState()
            s.apply(c, Offset(c.cx + c.r * frac, c.cy), t)
            return s.lAxis.first
        }
        assertEquals(0f, lx(Tuning(deadZone = 0.2f), 0.15f), 0f) // inside the dead zone
        assertEquals(0.5f, lx(Tuning(deadZone = 0f), 0.5f), 1e-4f) // linear
        assertEquals(0.25f, lx(Tuning(deadZone = 0f, curve = ResponseCurve.Quadratic), 0.5f), 1e-4f)
        assertEquals(0.125f, lx(Tuning(deadZone = 0f, curve = ResponseCurve.Cubic), 0.5f), 1e-4f)
        assertEquals(0.75f, lx(Tuning(deadZone = 0f, sensitivity = 1.5f), 0.5f), 1e-4f)
        assertEquals(1f, lx(Tuning(deadZone = 0f, sensitivity = 2f), 0.9f), 1e-4f) // clamped
    }

    @Test fun triggerModeFromProfileSelectsDigitalOrAnalog() {
        val lt = GamepadLayout.standard(2000f, 1000f).first { it.id == "LT" }
        val rc = lt.rect!!
        val mid = Offset(lt.cx, rc[1] + (rc[3] - rc[1]) * 0.5f)
        val analog = PadState().also { it.apply(lt, mid, std.tuning()) }
        assertTrue("analog value was ${analog.lt}", analog.lt in 120..135)
        val digital = PadState().also { it.apply(lt, mid, std.copy(triggerMode = TriggerMode.Digital).tuning()) }
        assertEquals(255, digital.lt)
    }

    @Test fun stickLatchesWhenProfileDisablesReturnToCenter() {
        val c = GamepadLayout.standard(2000f, 1000f).first { it.id == "RS" }
        val latched = Tuning(deadZone = 0f, stickReturnToCenter = false)
        val s = PadState()
        s.apply(c, Offset(c.cx + c.r * 0.8f, c.cy), latched)
        val held = s.rAxis
        s.release(c, latched)
        assertEquals(held, s.rAxis)
        assertTrue(s.rAxis.first > 0.7f)
        s.release(c, Tuning(stickReturnToCenter = true))
        assertEquals(0f to 0f, s.rAxis)
        // Releasing buttons is unaffected by latching.
        val a = GamepadLayout.standard(2000f, 1000f).first { it.id == "A" }
        s.apply(a, Offset(a.cx, a.cy), latched)
        assertTrue(s.buttons != 0)
        s.release(a, latched)
        assertEquals(0, s.buttons)
    }

    @Test fun resetClearsLatchedSticks() {
        val c = GamepadLayout.standard(2000f, 1000f).first { it.id == "LS" }
        val s = PadState()
        s.apply(c, Offset(c.cx + c.r, c.cy), Tuning(deadZone = 0f, stickReturnToCenter = false))
        s.reset()
        assertEquals(0f to 0f, s.lAxis)
        assertEquals(Offset.Zero, s.lKnob)
    }
}
