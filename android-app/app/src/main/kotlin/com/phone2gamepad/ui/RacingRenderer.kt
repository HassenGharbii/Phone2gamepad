package com.phone2gamepad.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import com.phone2gamepad.data.ColorSlot
import com.phone2gamepad.data.Skin
import kotlin.math.min

// Vector drawing of the racing controls: steering wheel, metal pedals, paddle shifters and the handbrake lever.
// The wheel is split in three so the live screen can turn only the middle part with a GPU layer rotation (no per-frame drawing):
// [drawWheelUnderlay] (fixed shadow/dish), [drawWheelBody] (turns with the finger) and [drawWheelGloss] (fixed light reflection).

private val metalLight = Color(0xFFD5D9DE)
private val metalDark = Color(0xFF5B6168)

private fun metal(skin: Skin, tint: Float = 0.5f): Color = lerp(skin.col(ColorSlot.Trigger), Color(0xFF9AA1A9), tint)

/** Reused outline path. Drawing happens on the UI thread only, so one scratch instance is safe and avoids per-frame garbage. */
private val scratch = Path()

/** Fixed part under the wheel: a soft drop shadow and the shallow dish the wheel sits in. */
internal fun DrawScope.drawWheelUnderlay(ctr: Offset, r: Float, skin: Skin) {
    val so = ctr + Offset(r * 0.03f, r * 0.07f)
    drawCircle(
        Brush.radialGradient(0.80f to Color.Black.copy(alpha = 0.38f), 1f to Color.Transparent, center = so, radius = r * 1.12f),
        r * 1.12f, so,
    )
    drawCircle(skin.col(ColorSlot.StickBase).copy(alpha = 0.40f), r * 0.98f, ctr)
    drawCircle(Color.Black.copy(alpha = 0.35f), r * 0.99f, ctr, style = Stroke(r * 0.02f))
}

/** The turning part: rim with grip texture and stitching, three tapered spokes, hub and the 12 o'clock marker stripe. */
internal fun DrawScope.drawWheelBody(ctr: Offset, r: Float, skin: Skin) {
    val rimW = r * 0.17f
    val rimMid = r - rimW / 2f
    val rim = skin.col(ColorSlot.StickKnob)
    val rimDark = lerp(rim, Color.Black, 0.45f)
    val rimLight = lerp(rim, Color.White, 0.30f)
    val accent = skin.col(ColorSlot.Accent)
    val topLeft = Offset(ctr.x - rimMid, ctr.y - rimMid)
    val ringSize = Size(rimMid * 2f, rimMid * 2f)

    // Rim: dark under-edge, then a leather-like sweep gradient so it looks round.
    drawCircle(Color.Black.copy(alpha = 0.55f), rimMid, ctr, style = Stroke(rimW * 1.06f))
    drawCircle(
        Brush.sweepGradient(listOf(rimLight, rim, rimDark, rim, rimLight), ctr),
        rimMid, ctr, style = Stroke(rimW * 0.9f),
    )
    // Thumb grips at 3 and 9 o'clock: darker pads.
    drawArc(Color.Black.copy(alpha = 0.32f), -34f, 68f, false, topLeft, ringSize, style = Stroke(rimW * 0.86f))
    drawArc(Color.Black.copy(alpha = 0.32f), 146f, 68f, false, topLeft, ringSize, style = Stroke(rimW * 0.86f))
    // Fine perforation pattern around the whole rim.
    drawCircle(
        Color.Black.copy(alpha = 0.22f), rimMid, ctr,
        style = Stroke(rimW * 0.56f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(rimW * 0.20f, rimW * 0.20f))),
    )
    // Double stitching along the rim.
    val stitch = Color.White.copy(alpha = 0.55f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(rimW * 0.26f, rimW * 0.18f))
    drawCircle(stitch, rimMid - rimW * 0.30f, ctr, style = Stroke(rimW * 0.055f, pathEffect = dash))
    drawCircle(stitch, rimMid + rimW * 0.30f, ctr, style = Stroke(rimW * 0.055f, pathEffect = dash))
    // Inner and outer edge lines.
    drawCircle(Color.Black.copy(alpha = 0.45f), r - rimW, ctr, style = Stroke(r * 0.012f))
    drawCircle(Color.White.copy(alpha = 0.12f), r - rimW * 0.04f, ctr, style = Stroke(r * 0.012f))

    // Three tapered spokes (right, bottom, left) in dark metal.
    val inner = r - rimW * 0.85f
    val hubR = r * 0.27f
    val spokeFill = Brush.linearGradient(listOf(lerp(rim, Color.Black, 0.30f), lerp(rim, Color.Black, 0.55f)), ctr + Offset(-r, -r), ctr + Offset(r, r))
    for (angle in floatArrayOf(0f, 90f, 180f)) {
        rotate(angle, ctr) {
            scratch.reset()
            scratch.moveTo(ctr.x + hubR * 0.6f, ctr.y - r * 0.115f)
            scratch.lineTo(ctr.x + inner, ctr.y - r * 0.075f)
            scratch.lineTo(ctr.x + inner, ctr.y + r * 0.075f)
            scratch.lineTo(ctr.x + hubR * 0.6f, ctr.y + r * 0.115f)
            scratch.close()
            drawPath(scratch, spokeFill)
            drawLine(
                Color.White.copy(alpha = 0.14f), Offset(ctr.x + hubR, ctr.y - r * 0.035f), Offset(ctr.x + inner - rimW * 0.1f, ctr.y - r * 0.028f),
                strokeWidth = r * 0.012f, cap = StrokeCap.Round,
            )
            // Lightening holes.
            drawCircle(Color.Black.copy(alpha = 0.45f), r * 0.030f, Offset(ctr.x + (hubR + inner) * 0.55f, ctr.y))
            drawCircle(Color.Black.copy(alpha = 0.45f), r * 0.024f, Offset(ctr.x + (hubR + inner) * 0.71f, ctr.y))
        }
    }

    // Hub: domed centre pad with a ring and a small emblem circle.
    drawCircle(Color.Black.copy(alpha = 0.5f), hubR * 1.07f, ctr)
    drawCircle(
        Brush.radialGradient(
            listOf(lerp(rim, Color.White, 0.35f), rim, lerp(rim, Color.Black, 0.55f)),
            center = ctr + Offset(-hubR * 0.35f, -hubR * 0.4f), radius = hubR * 1.4f,
        ),
        hubR, ctr,
    )
    drawCircle(accent.copy(alpha = 0.9f), hubR * 0.80f, ctr, style = Stroke(r * 0.012f))
    drawCircle(Color.Black.copy(alpha = 0.35f), hubR * 0.38f, ctr)
    drawCircle(accent, hubR * 0.28f, ctr)

    // Marker stripe at 12 o'clock so the turn can be read at a glance.
    drawArc(accent, -97f, 14f, false, topLeft, ringSize, style = Stroke(rimW * 0.9f))
    drawArc(Color.White.copy(alpha = 0.85f), -91f, 2f, false, topLeft, ringSize, style = Stroke(rimW * 0.9f))
}

/** Fixed light reflection over the rim (light does not turn with the wheel). */
internal fun DrawScope.drawWheelGloss(ctr: Offset, r: Float) {
    val rimW = r * 0.17f
    val mid = r - rimW * 0.30f
    val tl = Offset(ctr.x - mid, ctr.y - mid)
    val sz = Size(mid * 2f, mid * 2f)
    drawArc(Color.White.copy(alpha = 0.20f), 196f, 72f, false, tl, sz, style = Stroke(rimW * 0.28f, cap = StrokeCap.Round))
    drawArc(Color.White.copy(alpha = 0.07f), 16f, 52f, false, tl, sz, style = Stroke(rimW * 0.22f, cap = StrokeCap.Round))
}

/** Static drawing of the whole wheel at [angle] degrees (layout editor, thumbnails): shadow + body + gloss. */
internal fun DrawScope.drawWheelStatic(ctr: Offset, r: Float, skin: Skin, angle: Float) {
    drawWheelUnderlay(ctr, r, skin)
    rotate(angle, ctr) { drawWheelBody(ctr, r, skin) }
    drawWheelGloss(ctr, r)
}

private fun DrawScope.engraved(m: TextMeasurer, text: String, center: Offset, px: Float, color: Color) {
    if (text.isEmpty()) return
    val r = m.measure(text, TextStyle(fontSize = px.toSp(), color = color, fontWeight = FontWeight.Bold))
    drawText(r, topLeft = Offset(center.x - r.size.width / 2f, center.y - r.size.height / 2f))
}

/**
 * Metal pedal: dark housing with a travel gauge, a brushed-metal plate with raised grip ribs and corner bolts. The plate moves
 * in as the pedal is pressed ([v] 0..1): it gets shorter and narrower toward the top and darker, like a pedal tilting away.
 */
internal fun DrawScope.drawPedal(c: Control, v: Float, m: TextMeasurer, skin: Skin) {
    val b = c.box()
    val w = b[2] - b[0]
    val h = b[3] - b[1]
    val unit = min(size.width, size.height)
    val outline = skin.col(ColorSlot.Outline)
    val accent = skin.col(ColorSlot.Accent)
    val base = metal(skin)
    val cr = CornerRadius(min(w, h) * 0.16f)
    // Housing.
    drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(b[0], b[1]), Size(w, h), cr)
    drawRoundRect(outline.copy(alpha = 0.7f), Offset(b[0], b[1]), Size(w, h), cr, style = Stroke(unit * 0.003f + 1.5f))
    // Travel gauge on the right edge (the real analog value, filled from the bottom).
    val gx = b[2] - w * 0.075f
    val gw = w * 0.035f
    drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(gx, b[1] + h * 0.05f), Size(gw, h * 0.90f), CornerRadius(gw / 2f))
    if (v > 0f) drawRoundRect(accent, Offset(gx, b[1] + h * (0.95f - 0.90f * v)), Size(gw, h * 0.90f * v), CornerRadius(gw / 2f))

    // Plate geometry: anchored at the bottom hinge, shrinking with travel.
    val plateL = b[0] + w * 0.07f
    val plateR = b[2] - w * 0.15f
    val plateW = plateR - plateL
    val bottom = b[3] - h * 0.04f
    val restTop = b[1] + h * 0.04f
    val top = restTop + (bottom - restTop) * 0.18f * v
    val plateH = bottom - top
    val topInset = plateW * 0.12f * v
    val pcx = (plateL + plateR) / 2f
    scratch.reset()
    scratch.moveTo(plateL + topInset, top)
    scratch.lineTo(plateR - topInset, top)
    scratch.lineTo(plateR, bottom)
    scratch.lineTo(plateL, bottom)
    scratch.close()
    val shade = 0.30f * v
    val plateBrush = Brush.horizontalGradient(
        listOf(
            lerp(metalDark, Color.Black, shade), lerp(base, Color.Black, shade * 0.6f), lerp(metalLight, Color.Black, shade),
            lerp(base, Color.Black, shade * 0.6f), lerp(metalDark, Color.Black, shade),
        ),
        plateL, plateR,
    )
    // Contact shadow under the plate fades as it goes in.
    drawPath(scratch, Color.Black.copy(alpha = 0.35f * (1f - v * 0.7f)), style = Stroke(unit * 0.012f))
    drawPath(scratch, plateBrush)
    drawPath(scratch, Color.Black.copy(alpha = 0.55f), style = Stroke(unit * 0.0025f + 1.2f))

    // Raised grip ribs (dark groove with a light edge) following the plate.
    val ribs = if (w > h * 0.2f) 11 else 9
    for (i in 1 until ribs) {
        val f = i / ribs.toFloat()
        val y = top + plateH * f
        val inset = topInset * (1f - f)
        val x0 = plateL + plateW * 0.10f + inset
        val x1 = plateR - plateW * 0.10f - inset
        val sw = unit * 0.0045f + 1.5f
        drawLine(Color.Black.copy(alpha = 0.45f), Offset(x0, y), Offset(x1, y), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.28f), Offset(x0, y + sw * 1.2f), Offset(x1, y + sw * 1.2f), strokeWidth = sw * 0.5f, cap = StrokeCap.Round)
    }
    // Corner bolts.
    val bolt = min(plateW, h) * 0.035f
    val by0 = top + plateH * 0.04f
    val by1 = bottom - plateH * 0.04f
    for (p in arrayOf(
        Offset(plateL + plateW * 0.07f + topInset * 0.6f, by0), Offset(plateR - plateW * 0.07f - topInset * 0.6f, by0),
        Offset(plateL + plateW * 0.07f, by1), Offset(plateR - plateW * 0.07f, by1),
    )) {
        drawCircle(Color.Black.copy(alpha = 0.5f), bolt * 1.25f, p)
        drawCircle(lerp(metalLight, Color.Black, 0.2f), bolt, p)
    }
    // Engraved name and, while pressed, the real percentage.
    val tagY = bottom - plateH * 0.10f
    val tagW = plateW * 0.80f
    val tagH = unit * 0.05f
    drawRoundRect(Color.Black.copy(alpha = 0.60f), Offset(pcx - tagW / 2f, tagY - tagH / 2f), Size(tagW, tagH), CornerRadius(tagH * 0.3f))
    engraved(m, c.label, Offset(pcx, tagY), unit * 0.03f, Color.White.copy(alpha = 0.88f))
    if (v > 0.02f) {
        val pc = Offset(pcx, top + plateH * 0.09f)
        drawRoundRect(Color.Black.copy(alpha = 0.60f), Offset(pc.x - tagW * 0.3f, pc.y - tagH * 0.5f), Size(tagW * 0.6f, tagH), CornerRadius(tagH * 0.3f))
        engraved(m, "${(v * 100f).toInt()}%", pc, unit * 0.028f, Color.White.copy(alpha = 0.92f))
    }
}

/** Curved metal paddle shifter. The left paddle curves towards its right (the wheel); the right one is its mirror image. */
internal fun DrawScope.drawPaddle(c: Control, on: Boolean, m: TextMeasurer, skin: Skin) {
    val b = c.box()
    val w = b[2] - b[0]
    val h = b[3] - b[1]
    val leftSide = c.id == "SHIFT_DOWN"
    fun px(x: Float) = if (leftSide) x else b[0] + b[2] - x
    val unit = min(size.width, size.height)
    scratch.reset()
    scratch.moveTo(px(b[2]), b[1] + h * 0.03f)
    scratch.cubicTo(px(b[0] + w * 0.15f), b[1], px(b[0]), b[1] + h * 0.10f, px(b[0]), b[1] + h * 0.30f)
    scratch.lineTo(px(b[0]), b[3] - h * 0.30f)
    scratch.cubicTo(px(b[0]), b[3] - h * 0.10f, px(b[0] + w * 0.15f), b[3], px(b[2]), b[3] - h * 0.03f)
    // Inner edge hugs the wheel: concave.
    scratch.cubicTo(px(b[2] - w * 0.55f), b[3] - h * 0.30f, px(b[2] - w * 0.55f), b[1] + h * 0.30f, px(b[2]), b[1] + h * 0.03f)
    scratch.close()
    val accent = skin.col(ColorSlot.Accent)
    val base = metal(skin)
    val brush = Brush.horizontalGradient(
        listOf(lerp(metalDark, Color.Black, 0.15f), if (on) accent else lerp(base, Color.White, 0.18f), lerp(metalDark, Color.Black, 0.2f)),
        b[0], b[2],
    )
    drawPath(scratch, Color.Black.copy(alpha = 0.40f), style = Stroke(unit * 0.012f))
    drawPath(scratch, brush)
    drawPath(scratch, skin.col(ColorSlot.Outline).copy(alpha = 0.8f), style = Stroke(unit * 0.0035f + 1.2f))
    // Grip lines.
    val cx = (b[0] + b[2]) / 2f
    for (i in -2..2) {
        val y = (b[1] + b[3]) / 2f + i * h * 0.075f
        drawLine(
            Color.Black.copy(alpha = 0.35f), Offset(cx - w * 0.22f, y + h * 0.12f), Offset(cx + w * 0.22f, y + h * 0.12f),
            strokeWidth = unit * 0.003f + 1f, cap = StrokeCap.Round,
        )
    }
    engraved(m, c.label, Offset(cx, b[1] + h * 0.14f), unit * 0.05f, if (on) Color.Black else Color.White.copy(alpha = 0.92f))
}

/** Handbrake lever in a slot: the lever tilts back and its grip lights up while pulled. */
internal fun DrawScope.drawHandbrake(c: Control, on: Boolean, m: TextMeasurer, skin: Skin) {
    val b = c.box()
    val w = b[2] - b[0]
    val h = b[3] - b[1]
    val unit = min(size.width, size.height)
    val accent = skin.col(ColorSlot.Accent)
    val cx = (b[0] + b[2]) / 2f
    // Housing slot.
    drawRoundRect(Color.Black.copy(alpha = 0.5f), Offset(b[0], b[1]), Size(w, h), CornerRadius(w * 0.4f))
    drawRoundRect(skin.col(ColorSlot.Outline).copy(alpha = 0.6f), Offset(b[0], b[1]), Size(w, h), CornerRadius(w * 0.4f), style = Stroke(unit * 0.003f + 1.2f))
    val pivot = Offset(cx, b[3] - h * 0.13f)
    val len = h * 0.70f
    val base = metal(skin)
    rotate(if (on) -22f else 0f, pivot) {
        val armW = w * 0.34f
        drawRoundRect(
            Brush.horizontalGradient(listOf(metalDark, lerp(base, Color.White, 0.2f), metalDark), cx - armW / 2f, cx + armW / 2f),
            Offset(cx - armW / 2f, pivot.y - len), Size(armW, len), CornerRadius(armW / 2f),
        )
        val gripH = h * 0.26f
        val gripW = w * 0.74f
        val gripColor = if (on) accent else lerp(skin.col(ColorSlot.ButtonIdle), Color.Black, 0.25f)
        drawRoundRect(gripColor, Offset(cx - gripW / 2f, pivot.y - len - gripH * 0.35f), Size(gripW, gripH), CornerRadius(gripW * 0.35f))
        for (i in 1..3) {
            val y = pivot.y - len - gripH * 0.35f + gripH * i / 4f
            drawLine(Color.Black.copy(alpha = 0.35f), Offset(cx - gripW * 0.32f, y), Offset(cx + gripW * 0.32f, y), strokeWidth = unit * 0.003f + 1f, cap = StrokeCap.Round)
        }
    }
    drawCircle(Color.Black.copy(alpha = 0.6f), w * 0.2f, pivot)
    drawCircle(lerp(metalLight, Color.Black, 0.25f), w * 0.13f, pivot)
    // Name under the slot.
    engraved(m, c.label, Offset(cx, b[3] + unit * 0.032f), unit * 0.021f, Color.White.copy(alpha = 0.6f))
}
