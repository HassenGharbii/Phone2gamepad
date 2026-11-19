package com.phone2gamepad.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.TriggerMode
import com.phone2gamepad.protocol.Buttons
import com.phone2gamepad.protocol.ControllerState
import com.phone2gamepad.protocol.InputMath
import com.phone2gamepad.protocol.ResponseCurve
import kotlin.math.hypot

enum class Kind { Button, Stick, Dpad, Trigger }

/** Input tuning taken from the active profile (dead zone, sensitivity, curve, trigger mode, stick latching). */
data class Tuning(
    val deadZone: Float = 0.10f,
    val sensitivity: Float = 1f,
    val curve: ResponseCurve = ResponseCurve.Linear,
    val digitalTriggers: Boolean = false,
    val stickReturnToCenter: Boolean = true,
)

fun Profile.tuning() = Tuning(deadZone, sensitivity, curve, triggerMode == TriggerMode.Digital, stickReturnToCenter)

/** A touch control. Circular when [rect] is null (radius [r]), otherwise the rectangle l,t,r,b. */
class Control(
    val id: String,
    val kind: Kind,
    val cx: Float,
    val cy: Float,
    val r: Float,
    val bit: Int = 0,
    val label: String = "",
    val color: Color = Color.Unspecified,
    val rect: FloatArray? = null, // l, t, r, b for rectangular controls
    val hitScale: Float = 1.25f,
    val opacity: Float = 1f,
    val visible: Boolean = true,
) {
    val isRound get() = rect == null

    /** Copy centred on ([nx],[ny]) with the same size. */
    fun withCentre(nx: Float, ny: Float): Control = Control(
        id, kind, nx, ny, r, bit, label, color,
        rect = rect?.let { val hw = (it[2] - it[0]) / 2f; val hh = (it[3] - it[1]) / 2f; floatArrayOf(nx - hw, ny - hh, nx + hw, ny + hh) },
        hitScale = hitScale, opacity = opacity, visible = visible,
    )

    /** Copy with the size multiplied by [f] around the same centre. */
    fun withSizeFactor(f: Float): Control = Control(
        id, kind, cx, cy, r * f, bit, label, color,
        rect = rect?.let { val hw = (it[2] - it[0]) / 2f * f; val hh = (it[3] - it[1]) / 2f * f; floatArrayOf(cx - hw, cy - hh, cx + hw, cy + hh) },
        hitScale = hitScale, opacity = opacity, visible = visible,
    )

    /** Visual bounding box l,t,r,b. */
    fun box(): FloatArray = rect?.copyOf() ?: floatArrayOf(cx - r, cy - r, cx + r, cy + r)

    /** Normalized hit distance (<= 1 means inside the touch target); large when outside. */
    fun hitDistance(p: Offset): Float {
        if (rect != null) {
            val pad = 10f
            val inside = p.x >= rect[0] - pad && p.x <= rect[2] + pad && p.y >= rect[1] - pad && p.y <= rect[3] + pad
            return if (inside) hypot(p.x - cx, p.y - cy) / hypot(rect[2] - rect[0], rect[3] - rect[1]) else Float.MAX_VALUE
        }
        val d = hypot(p.x - cx, p.y - cy)
        return if (d <= r * hitScale) d / r else Float.MAX_VALUE
    }
}

/** Pure geometry used by the layout editor. Boxes are l,t,r,b. */
object LayoutMath {
    fun overlaps(a: FloatArray, b: FloatArray, gap: Float = 0f): Boolean =
        a[0] < b[2] + gap && b[0] < a[2] + gap && a[1] < b[3] + gap && b[1] < a[3] + gap

    /**
     * Shape-aware overlap of two controls (circle/circle, circle/rectangle or rectangle/rectangle) with a [gap] margin.
     * Bounding boxes would flag diagonal neighbours (e.g. the face buttons) that do not actually touch.
     */
    fun controlsOverlap(a: Control, b: Control, gap: Float = 0f): Boolean {
        val ra = a.rect
        val rb = b.rect
        return when {
            ra == null && rb == null -> hypot(a.cx - b.cx, a.cy - b.cy) < a.r + b.r + gap
            ra != null && rb != null -> overlaps(ra, rb, gap)
            else -> {
                val c = if (ra == null) a else b
                val r = (if (ra == null) rb else ra)!!
                val nx = c.cx.coerceIn(r[0], r[2])
                val ny = c.cy.coerceIn(r[1], r[3])
                hypot(c.cx - nx, c.cy - ny) < c.r + gap
            }
        }
    }

    /** Moves [box] (by changing its centre) so it lies inside 0..w x 0..h. Returns the new centre. */
    fun clampCentre(cx: Float, cy: Float, halfW: Float, halfH: Float, w: Float, h: Float): Pair<Float, Float> =
        cx.coerceIn(minOf(halfW, w / 2f), maxOf(w - halfW, w / 2f)) to cy.coerceIn(minOf(halfH, h / 2f), maxOf(h - halfH, h / 2f))

    /**
     * Whether moving/resizing control [c] to [candidate] is acceptable when overlapping is not allowed: it must not
     * newly overlap any other visible control (controls that already overlapped before the change are tolerated).
     */
    fun placementAllowed(c: Control, candidate: Control, all: List<Control>, gap: Float = 2f): Boolean {
        for (o in all) {
            if (o.id == c.id || !o.visible) continue
            if (controlsOverlap(candidate, o, gap) && !controlsOverlap(c, o, gap)) return false
        }
        return true
    }
}

/** Default placement/size of one control. Centres are fractions of the screen; sizes are fractions of height (round) or width/height half-extents (rect). */
class ControlSpec(
    val id: String,
    val kind: Kind,
    val label: String,
    val color: Color,
    val bit: Int = 0,
    val hitScale: Float = 1.25f,
    val radiusOfHeight: Float = 0f,
    val halfW: Float = 0f,
    val halfH: Float = 0f,
    val center: (w: Float, h: Float) -> Pair<Float, Float>,
) {
    val isRect get() = radiusOfHeight == 0f
}

object GamepadLayout {
    private const val FACE_OFF = 0.165f // of height
    private const val FACE_X = 0.80f
    private const val FACE_Y = 0.58f

    private fun fixed(x: Float, y: Float): (Float, Float) -> Pair<Float, Float> = { _, _ -> x to y }

    val specs: List<ControlSpec> = listOf(
        ControlSpec("LT", Kind.Trigger, "LT", Palette.SurfaceHigh, halfW = 0.065f, halfH = 0.095f, center = fixed(0.085f, 0.125f)),
        ControlSpec("LB", Kind.Button, "LB", Palette.SurfaceHigh, Buttons.LB, halfW = 0.065f, halfH = 0.06f, center = fixed(0.085f, 0.30f)),
        ControlSpec("RT", Kind.Trigger, "RT", Palette.SurfaceHigh, halfW = 0.065f, halfH = 0.095f, center = fixed(0.915f, 0.125f)),
        ControlSpec("RB", Kind.Button, "RB", Palette.SurfaceHigh, Buttons.RB, halfW = 0.065f, halfH = 0.06f, center = fixed(0.915f, 0.30f)),
        ControlSpec("LS", Kind.Stick, "L", Palette.SurfaceHigh, hitScale = 1.3f, radiusOfHeight = 0.20f, center = fixed(0.27f, 0.40f)),
        ControlSpec("DPAD", Kind.Dpad, "", Palette.SurfaceHigh, hitScale = 1.1f, radiusOfHeight = 0.15f, center = fixed(0.22f, 0.80f)),
        ControlSpec("RS", Kind.Stick, "R", Palette.SurfaceHigh, hitScale = 1.3f, radiusOfHeight = 0.20f, center = fixed(0.58f, 0.74f)),
        ControlSpec("Y", Kind.Button, "Y", Palette.ButtonY, Buttons.Y, radiusOfHeight = 0.085f, center = fixed(FACE_X, FACE_Y - FACE_OFF)),
        ControlSpec("A", Kind.Button, "A", Palette.ButtonA, Buttons.A, radiusOfHeight = 0.085f, center = fixed(FACE_X, FACE_Y + FACE_OFF)),
        ControlSpec("X", Kind.Button, "X", Palette.ButtonX, Buttons.X, radiusOfHeight = 0.085f, center = { w, h -> (FACE_X - FACE_OFF * h / w) to FACE_Y }),
        ControlSpec("B", Kind.Button, "B", Palette.ButtonB, Buttons.B, radiusOfHeight = 0.085f, center = { w, h -> (FACE_X + FACE_OFF * h / w) to FACE_Y }),
        ControlSpec("BACK", Kind.Button, "Back", Palette.SurfaceHigh, Buttons.BACK, halfW = 0.045f, halfH = 0.06f, center = fixed(0.44f, 0.30f)),
        ControlSpec("START", Kind.Button, "Start", Palette.SurfaceHigh, Buttons.START, halfW = 0.045f, halfH = 0.06f, center = fixed(0.56f, 0.30f)),
        ControlSpec("L3", Kind.Button, "L3", Palette.SurfaceHigh, Buttons.L3, hitScale = 1.15f, radiusOfHeight = 0.05f, center = fixed(0.40f, 0.90f)),
        ControlSpec("R3", Kind.Button, "R3", Palette.SurfaceHigh, Buttons.R3, hitScale = 1.15f, radiusOfHeight = 0.05f, center = fixed(0.48f, 0.90f)),
    )

    fun spec(id: String) = specs.first { it.id == id }

    /** Default centre of [id] as screen fractions (before handedness mirroring). */
    fun defaultCentre(id: String, w: Float, h: Float): Pair<Float, Float> = spec(id).center(w, h)

    /**
     * Builds the pixel-space controls for [profile] on a [w] x [h] screen, hidden ones included (flagged
     * invisible). Handedness mirrors horizontally; sizes are scaled by the profile and per-control scale;
     * controls are kept inside the screen.
     */
    fun resolveAll(profile: Profile, w: Float, h: Float): List<Control> = specs.map { s ->
        val o = profile.override(s.id)
        val (dfx, dfy) = s.center(w, h)
        val storedFx = o.fx ?: dfx
        val storedFy = o.fy ?: dfy
        val fx = if (profile.leftHanded) 1f - storedFx else storedFx
        val k = profile.globalScale * o.scale
        if (s.isRect) {
            val hw = s.halfW * w * k
            val hh = s.halfH * h * k
            val (cx, cy) = LayoutMath.clampCentre(fx * w, storedFy * h, hw, hh, w, h)
            Control(
                s.id, s.kind, cx, cy, 0f, s.bit, s.label, s.color, rect = floatArrayOf(cx - hw, cy - hh, cx + hw, cy + hh),
                hitScale = s.hitScale, opacity = o.opacity, visible = o.visible,
            )
        } else {
            val r = s.radiusOfHeight * h * k
            val (cx, cy) = LayoutMath.clampCentre(fx * w, storedFy * h, r, r, w, h)
            Control(s.id, s.kind, cx, cy, r, s.bit, s.label, s.color, hitScale = s.hitScale, opacity = o.opacity, visible = o.visible)
        }
    }

    /** Visible controls only (what the gamepad hit-tests and draws). */
    fun resolve(profile: Profile, w: Float, h: Float): List<Control> = resolveAll(profile, w, h).filter { it.visible }

    /** The standard layout (default profile) for a screen size. */
    fun standard(w: Float, h: Float): List<Control> = resolve(Profile(id = "standard", name = "Standard"), w, h)
}

/**
 * Mutable pad state shared between the touch handler and the renderer. Not thread-safe: touched only on the UI thread.
 */
class PadState {
    var buttons = 0
    var dpad = 0
    var lKnob = Offset.Zero
    var rKnob = Offset.Zero
    var lAxis = 0f to 0f
    var rAxis = 0f to 0f
    var lt = 0
    var rt = 0

    fun reset() {
        buttons = 0; dpad = 0; lKnob = Offset.Zero; rKnob = Offset.Zero
        lAxis = 0f to 0f; rAxis = 0f to 0f; lt = 0; rt = 0
    }

    fun toControllerState() = ControllerState(
        buttons = buttons, dpad = dpad,
        lx = InputMath.toAxis(lAxis.first), ly = InputMath.toAxis(lAxis.second),
        rx = InputMath.toAxis(rAxis.first), ry = InputMath.toAxis(rAxis.second),
        lt = lt, rt = rt,
    )

    /** Applies the finger position [p] to control [c] (called on down and move). */
    fun apply(c: Control, p: Offset, t: Tuning) {
        when (c.kind) {
            Kind.Button -> buttons = buttons or c.bit
            Kind.Stick -> {
                val dx = p.x - c.cx
                val dy = p.y - c.cy
                val len = hypot(dx, dy)
                val knob = if (len > c.r) Offset(dx / len * c.r, dy / len * c.r) else Offset(dx, dy)
                val axis = InputMath.normalizeStick(dx, dy, c.r, t.deadZone, t.sensitivity, t.curve)
                if (c.id == "LS") { lKnob = knob; lAxis = axis } else { rKnob = knob; rAxis = axis }
            }
            Kind.Dpad -> dpad = InputMath.dpadFromOffset(p.x - c.cx, p.y - c.cy, c.r)
            Kind.Trigger -> {
                val rc = c.rect!!
                val v = InputMath.triggerFromPosition(p.y, rc[1], rc[3] - rc[1], t.digitalTriggers)
                if (c.id == "LT") lt = v else rt = v
            }
        }
    }

    /** Finger lifted from [c]. Sticks return to centre unless the profile latches them. */
    fun release(c: Control, t: Tuning = Tuning()) {
        when (c.kind) {
            Kind.Button -> buttons = buttons and c.bit.inv()
            Kind.Stick -> if (t.stickReturnToCenter) {
                if (c.id == "LS") { lKnob = Offset.Zero; lAxis = 0f to 0f } else { rKnob = Offset.Zero; rAxis = 0f to 0f }
            }
            Kind.Dpad -> dpad = 0
            Kind.Trigger -> if (c.id == "LT") lt = 0 else rt = 0
        }
    }
}
