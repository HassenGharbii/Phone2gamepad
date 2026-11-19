package com.phone2gamepad.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.phone2gamepad.data.ColorSlot
import com.phone2gamepad.data.LabelStyle
import com.phone2gamepad.data.Skin
import com.phone2gamepad.data.SkinColor
import com.phone2gamepad.data.SkinShape
import com.phone2gamepad.data.ShapeGeometry
import com.phone2gamepad.protocol.Dpad
import kotlin.math.min

// Vector-only skin renderer. Everything is drawn with Compose paths/shapes from the skin's colours; there are no bitmaps.

internal fun Skin.col(slot: ColorSlot) = Color(color(slot))

private fun Skin.labelTextStyle(): TextStyle = when (labelStyle) {
    LabelStyle.Bold -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
    LabelStyle.Light -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal)
    LabelStyle.Mono -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
}

private fun DrawScope.label(m: TextMeasurer, skin: Skin, text: String, center: Offset, sizePx: Float, color: Color) {
    if (text.isEmpty()) return
    val r = m.measure(text, skin.labelTextStyle().copy(fontSize = sizePx.toSp(), color = color))
    drawText(r, topLeft = Offset(center.x - r.size.width / 2f, center.y - r.size.height / 2f))
}

private fun readable(bg: Color): Color = Color(SkinColor.readableOn(bg.toArgb()))

private fun polygonPath(shape: SkinShape, b: FloatArray): Path? {
    val pts = ShapeGeometry.polygon(shape, b[0], b[1], b[2], b[3]) ?: return null
    return Path().apply {
        moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
        close()
    }
}

/** Fills and/or outlines [shape] inside the box [b] (l,t,r,b). */
private fun DrawScope.drawSkinShape(shape: SkinShape, b: FloatArray, fill: Color?, line: Color?, lineW: Float) {
    val w = b[2] - b[0]
    val h = b[3] - b[1]
    val tl = Offset(b[0], b[1])
    val sz = Size(w, h)
    val stroke = Stroke(lineW, join = StrokeJoin.Round)
    val poly = polygonPath(shape, b)
    when {
        poly != null -> {
            if (fill != null) drawPath(poly, fill)
            if (line != null && lineW > 0f) drawPath(poly, line, style = stroke)
        }
        shape == SkinShape.Circle -> {
            if (fill != null) drawOval(fill, tl, sz)
            if (line != null && lineW > 0f) drawOval(line, tl, sz, style = stroke)
        }
        else -> {
            val cr = CornerRadius(min(w, h) * 0.3f)
            if (fill != null) drawRoundRect(fill, tl, sz, cr)
            if (line != null && lineW > 0f) drawRoundRect(line, tl, sz, cr, style = stroke)
        }
    }
}

/** Draws [c] with its per-control opacity (a layer is only used when it is not fully opaque). */
internal fun DrawScope.drawControlAlpha(c: Control, pad: PadState, m: TextMeasurer, skin: Skin, extraAlpha: Float = 1f, dome: Boolean = false) {
    val a = (c.opacity * extraAlpha).coerceIn(0f, 1f)
    if (a >= 0.999f) {
        drawControl(c, pad, m, skin, dome)
        return
    }
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = a })
    drawControl(c, pad, m, skin, dome)
    canvas.restore()
}

/** Arcade-button look for a round control: a highlight on the upper left, a dark rim and a lower shade. */
private fun DrawScope.drawDome(center: Offset, r: Float, on: Boolean) {
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = if (on) 0.18f else 0.42f), Color.Transparent),
            center = center + Offset(-r * 0.30f, -r * 0.38f), radius = r * 1.05f,
        ),
        r, center,
    )
    drawCircle(
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (on) 0.15f else 0.32f)), center.y - r, center.y + r),
        r, center,
    )
    drawCircle(Color.Black.copy(alpha = 0.45f), r, center, style = Stroke(r * 0.07f))
}

private fun faceSlot(id: String): ColorSlot = when (id) {
    "A" -> ColorSlot.FaceA
    "B" -> ColorSlot.FaceB
    "X" -> ColorSlot.FaceX
    else -> ColorSlot.FaceY
}

internal fun DrawScope.drawControl(c: Control, pad: PadState, m: TextMeasurer, skin: Skin, dome: Boolean = false) {
    val unit = min(size.width, size.height)
    val k = unit / 1080f // outline widths are defined for a 1080 px short side
    val ow = skin.outlineWidth * k
    val outline = skin.col(ColorSlot.Outline)
    val labelColor = skin.col(ColorSlot.LabelText)
    val pressed = skin.col(ColorSlot.ButtonPressed)
    val box = c.box()
    // Racing controls have their own (metal) look.
    if (c.kind == Kind.Button && (c.id == "SHIFT_DOWN" || c.id == "SHIFT_UP")) { drawPaddle(c, (pad.buttons and c.bit) != 0, m, skin); return }
    if (c.kind == Kind.Button && c.id == "HANDBRAKE") { drawHandbrake(c, (pad.buttons and c.bit) != 0, m, skin); return }
    when (c.kind) {
        Kind.Button -> {
            val on = (pad.buttons and c.bit) != 0
            if (c.id.length == 1) {
                val slot = faceSlot(c.id)
                val face = skin.col(slot)
                val idleFill = Color(SkinColor.faceIdleFill(skin, slot))
                // Domed controllers show solid, bright caps (like arcade buttons) instead of the outlined idle look.
                val fill = if (on) face else if (dome) mixColor(face, Color.Black, 0.10f) else idleFill
                drawSkinShape(if (dome) SkinShape.Circle else skin.faceShape, box, fill, face, ow * 2f)
                if (dome) drawDome(c.center(), c.r, on)
                // Long names (CAM, NITRO) shrink to stay inside the round button.
                val faceLabelPx = min(unit * (if (dome) 0.05f else 0.06f), c.r * 1.7f / (0.62f * c.label.length.coerceAtLeast(1)))
                label(m, skin, c.label, c.center(), faceLabelPx, if (on || dome) readable(fill) else labelColor)
            } else {
                val idle = skin.col(if (c.id == "LB" || c.id == "RB") ColorSlot.Bumper else ColorSlot.ButtonIdle)
                val fill = if (on) pressed else idle
                drawSkinShape(if (dome && c.isRound) SkinShape.Circle else skin.buttonShape, box, fill, outline, ow)
                if (dome && c.isRound) drawDome(c.center(), c.r, on)
                val labelPx = if (c.isRound) unit * 0.032f else unit * 0.04f
                label(m, skin, c.label, c.center(), labelPx, if (on) readable(pressed) else labelColor)
            }
        }
        Kind.Stick -> {
            val knob = if (c.target == "LS") pad.lKnob else pad.rKnob
            val active = knob != Offset.Zero
            drawSkinShape(skin.padShape, box, skin.col(ColorSlot.StickBase), skin.col(ColorSlot.StickRing), ow * 1.5f)
            val knobFill = if (active) skin.col(ColorSlot.Accent) else skin.col(ColorSlot.StickKnob)
            val kc = c.center() + knob
            val kr = c.r * 0.42f
            val onKnob = readable(knobFill)
            drawCircle(knobFill, kr, kc)
            drawCircle(onKnob.copy(alpha = 0.25f), kr, kc, style = Stroke(2f * k.coerceAtLeast(0.5f)))
            label(m, skin, c.label, kc, unit * 0.05f, onKnob.copy(alpha = 0.85f))
        }
        Kind.Dpad -> {
            drawSkinShape(if (dome) SkinShape.Circle else skin.padShape, box, skin.col(ColorSlot.Dpad), skin.col(ColorSlot.StickRing), ow * 1.5f)
            if (dome) {
                // Recessed disc: dark outer ring and a subtle bevel so it reads as a moulded 8-way pad.
                drawCircle(Color.Black.copy(alpha = 0.55f), c.r, c.center(), style = Stroke(c.r * 0.07f))
                drawCircle(Color.White.copy(alpha = 0.10f), c.r * 0.93f, c.center(), style = Stroke(c.r * 0.02f))
            }
            val arm = c.r * 0.34f
            val len = c.r * 0.78f
            val idle = skin.col(ColorSlot.StickKnob)
            val active = skin.col(ColorSlot.Accent)
            fun armRect(dx: Float, dy: Float, on: Boolean) {
                val cx = c.cx + dx * len * 0.55f
                val cy = c.cy + dy * len * 0.55f
                val w = if (dx != 0f) len * 0.9f else arm * 2
                val h = if (dy != 0f) len * 0.9f else arm * 2
                drawRoundRect(if (on) active else idle, Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(arm * 0.5f))
            }
            armRect(0f, -1f, (pad.dpad and Dpad.UP) != 0)
            armRect(0f, 1f, (pad.dpad and Dpad.DOWN) != 0)
            armRect(-1f, 0f, (pad.dpad and Dpad.LEFT) != 0)
            armRect(1f, 0f, (pad.dpad and Dpad.RIGHT) != 0)
        }
        Kind.Trigger -> {
            val v = (if (c.target == "LT") pad.lt else pad.rt) / 255f
            drawSkinShape(skin.buttonShape, box, skin.col(ColorSlot.Trigger), null, 0f)
            if (v > 0f) clipRect(box[0], box[1], box[2], box[1] + (box[3] - box[1]) * v) {
                drawSkinShape(skin.buttonShape, box, pressed, null, 0f)
            }
            drawSkinShape(skin.buttonShape, box, null, outline, ow)
            label(m, skin, c.label, c.center(), unit * 0.04f, if (v > 0.5f) readable(pressed) else labelColor)
        }
        Kind.TriggerButton -> {
            val on = (if (c.target == "LT") pad.lt else pad.rt) > 0
            val idle = skin.col(ColorSlot.Bumper)
            drawSkinShape(SkinShape.Circle, box, if (on) pressed else idle, outline, ow)
            if (dome) drawDome(c.center(), c.r, on)
            label(m, skin, c.label, c.center(), if (c.r > unit * 0.07f) unit * 0.05f else unit * 0.032f, if (on) readable(pressed) else labelColor)
        }
        Kind.Toggle -> {
            val on = pad.turboOn
            val accent = skin.col(ColorSlot.Accent)
            val fill = if (on) accent else skin.col(ColorSlot.ButtonIdle)
            drawSkinShape(SkinShape.Circle, box, fill, if (on) accent else outline, ow * 1.5f)
            if (dome) drawDome(c.center(), c.r, on)
            label(m, skin, c.label, c.center(), unit * 0.028f, if (on) readable(accent) else labelColor)
        }
        Kind.Pedal -> drawPedal(c, (if (c.target == "LT") pad.lt else pad.rt) / 255f, m, skin)
        Kind.Wheel -> drawWheelStatic(c.center(), c.r, skin, pad.wheelRot)
    }
}

private fun mixColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t)

internal fun Control.center() = Offset(cx, cy)
