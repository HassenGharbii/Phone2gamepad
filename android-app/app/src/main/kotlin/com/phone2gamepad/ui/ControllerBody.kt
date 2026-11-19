package com.phone2gamepad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.phone2gamepad.data.Background
import com.phone2gamepad.data.BackgroundKind
import com.phone2gamepad.data.BuiltinBackgrounds
import com.phone2gamepad.data.ColorSlot
import com.phone2gamepad.data.ControllerType
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.Skin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

// Vector-only controller bodies: a glossy shell silhouette per controller type, with the chosen art "printed" on it.
// Nothing here is bitmap art except the image the user picks; the built-in backgrounds are drawn in code.

private fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

private fun mix(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t)

/** Normalised (0..1 of the canvas) outline of the shell for [type], scaled to a [w] x [h] canvas. */
fun bodyPath(type: ControllerType, w: Float, h: Float): Path {
    fun x(v: Float) = v * w
    fun y(v: Float) = v * h
    return Path().apply {
        when (type) {
            ControllerType.Xbox -> {
                // Rounded shoulders, side grips and a gently dipped underside (the pad's controls reach y = 0.95).
                moveTo(x(0.06f), y(0.25f))
                cubicTo(x(0.10f), y(0.17f), x(0.22f), y(0.17f), x(0.30f), y(0.195f))
                cubicTo(x(0.40f), y(0.21f), x(0.60f), y(0.21f), x(0.70f), y(0.195f))
                cubicTo(x(0.78f), y(0.17f), x(0.90f), y(0.17f), x(0.94f), y(0.25f))
                cubicTo(x(0.985f), y(0.34f), x(0.995f), y(0.62f), x(0.985f), y(0.80f))
                cubicTo(x(0.980f), y(0.96f), x(0.93f), y(0.995f), x(0.88f), y(0.99f))
                cubicTo(x(0.80f), y(0.985f), x(0.74f), y(0.965f), x(0.66f), y(0.975f))
                cubicTo(x(0.58f), y(0.985f), x(0.42f), y(0.985f), x(0.34f), y(0.975f))
                cubicTo(x(0.26f), y(0.965f), x(0.20f), y(0.985f), x(0.12f), y(0.99f))
                cubicTo(x(0.07f), y(0.995f), x(0.02f), y(0.96f), x(0.015f), y(0.80f))
                cubicTo(x(0.005f), y(0.62f), x(0.015f), y(0.34f), x(0.06f), y(0.25f))
                close()
            }
            ControllerType.Racing -> {
                // A wide dashboard-style panel with chamfered top corners and a raised centre.
                moveTo(x(0.03f), y(0.20f))
                cubicTo(x(0.03f), y(0.12f), x(0.06f), y(0.09f), x(0.12f), y(0.09f))
                lineTo(x(0.40f), y(0.09f))
                cubicTo(x(0.45f), y(0.09f), x(0.47f), y(0.05f), x(0.52f), y(0.05f))
                lineTo(x(0.70f), y(0.05f))
                cubicTo(x(0.75f), y(0.05f), x(0.77f), y(0.09f), x(0.82f), y(0.09f))
                lineTo(x(0.92f), y(0.09f))
                cubicTo(x(0.96f), y(0.09f), x(0.98f), y(0.13f), x(0.98f), y(0.20f))
                lineTo(x(0.98f), y(0.90f))
                cubicTo(x(0.98f), y(0.95f), x(0.96f), y(0.98f), x(0.92f), y(0.98f))
                lineTo(x(0.08f), y(0.98f))
                cubicTo(x(0.05f), y(0.98f), x(0.03f), y(0.95f), x(0.03f), y(0.90f))
                close()
            }
            ControllerType.Fighting -> {
                // Fight-pad shell: high shoulders, slim grips and a smooth scoop underneath.
                moveTo(x(0.06f), y(0.20f))
                cubicTo(x(0.10f), y(0.12f), x(0.30f), y(0.10f), x(0.45f), y(0.13f))
                cubicTo(x(0.48f), y(0.135f), x(0.52f), y(0.135f), x(0.55f), y(0.13f))
                cubicTo(x(0.70f), y(0.10f), x(0.90f), y(0.12f), x(0.94f), y(0.20f))
                cubicTo(x(0.98f), y(0.35f), x(0.99f), y(0.60f), x(0.97f), y(0.80f))
                cubicTo(x(0.955f), y(0.95f), x(0.85f), y(1.00f), x(0.72f), y(0.93f))
                cubicTo(x(0.62f), y(0.88f), x(0.38f), y(0.88f), x(0.28f), y(0.93f))
                cubicTo(x(0.15f), y(1.00f), x(0.045f), y(0.95f), x(0.03f), y(0.80f))
                cubicTo(x(0.01f), y(0.60f), x(0.02f), y(0.35f), x(0.06f), y(0.20f))
                close()
            }
        }
    }
}

/**
 * Draws the shell for [type] filling the canvas: drop shadow, rim, glossy base, the background art clipped to the
 * outline (with the darkness scrim), a sheen highlight and an inner shadow. [image] is the user's picture (or null).
 */
fun DrawScope.drawControllerBody(type: ControllerType, skin: Skin, bg: Background, image: ImageBitmap?) {
    val w = size.width
    val h = size.height
    val path = bodyPath(type, w, h)
    val bounds = path.getBounds()
    val page = skin.col(ColorSlot.Background)
    val light = luminance(page) > 0.5f
    val base = if (light) mix(page, Color.Black, 0.14f) else mix(page, Color.White, 0.11f)
    val unit = minOf(w, h)

    if (type == ControllerType.Fighting) drawFightPlates(w, h, base)
    // Soft drop shadow: a few offset copies, darker closer to the shell.
    for (i in 3 downTo 1) translate(0f, unit * 0.008f * i) { drawPath(path, Color.Black.copy(alpha = 0.10f)) }
    // Glossy base.
    drawPath(path, Brush.verticalGradient(listOf(mix(base, Color.White, 0.10f), base, mix(base, Color.Black, 0.30f)), bounds.top, bounds.bottom))
    clipPath(path) {
        when (bg.kind) {
            BackgroundKind.Image -> if (image != null) drawCoverImage(image, bounds, bg)
            BackgroundKind.Builtin -> drawBuiltinBackground(bg.ref, bounds)
            BackgroundKind.None -> {}
        }
        if (bg.kind != BackgroundKind.None && !(bg.kind == BackgroundKind.Image && image == null)) {
            drawRect(Color.Black.copy(alpha = bg.scrim), bounds.topLeft, bounds.size)
        }
        // Curved sheen across the upper part of the shell.
        val sheen = Path().apply {
            moveTo(bounds.left, bounds.top)
            lineTo(bounds.right, bounds.top)
            lineTo(bounds.right, bounds.top + bounds.height * 0.30f)
            cubicTo(
                bounds.left + bounds.width * 0.75f, bounds.top + bounds.height * 0.42f,
                bounds.left + bounds.width * 0.30f, bounds.top + bounds.height * 0.30f,
                bounds.left, bounds.top + bounds.height * 0.46f,
            )
            close()
        }
        drawPath(sheen, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.17f), Color.White.copy(alpha = 0.02f)), bounds.top, bounds.top + bounds.height * 0.46f))
        // Inner shadow along the edge.
        drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(unit * 0.03f, join = StrokeJoin.Round))
    }
    drawPath(path, skin.col(ColorSlot.Outline).copy(alpha = 0.75f), style = Stroke(unit * 0.006f, join = StrokeJoin.Round))
}

/** Shoulder plates on top of the fight pad (decorative, like the bumper housings of a real one). */
private fun DrawScope.drawFightPlates(w: Float, h: Float, base: Color) {
    val dark = mix(base, Color.Black, 0.55f)
    val pw = w * 0.17f
    val ph = h * 0.075f
    for (left in listOf(w * 0.075f, w * 0.755f)) {
        drawRoundRect(dark, Offset(left, h * 0.065f), Size(pw, ph), CornerRadius(ph * 0.45f))
        drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(left + pw * 0.06f, h * 0.072f), Size(pw * 0.88f, ph * 0.28f), CornerRadius(ph * 0.14f))
    }
}

/** Cover-fits [image] into [r] with the profile's zoom and offsets. */
private fun DrawScope.drawCoverImage(image: ImageBitmap, r: Rect, bg: Background) {
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    val s = max(r.width / iw, r.height / ih) * bg.zoom
    val dw = iw * s
    val dh = ih * s
    val dx = r.left + (r.width - dw) / 2f + bg.offsetX * (dw - r.width) / 2f
    val dy = r.top + (r.height - dh) / 2f + bg.offsetY * (dh - r.height) / 2f
    drawImage(
        image, IntOffset.Zero, IntSize(image.width, image.height),
        IntOffset(dx.toInt(), dy.toInt()), IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)),
    )
}

/** Original abstract art for the built-in backgrounds. Deterministic; no external data. */
private fun DrawScope.drawBuiltinBackground(id: String, r: Rect) {
    val w = r.width
    val h = r.height
    when (id) {
        BuiltinBackgrounds.AURORA -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFF0B1B3A), Color(0xFF14506B), Color(0xFF1F7A6A)), Offset(r.left, r.top), Offset(r.right, r.bottom)), r.topLeft, r.size)
            val bands = listOf(Color(0x5538E0C0) to 0.18f, Color(0x4460A8FF) to 0.34f, Color(0x40B070FF) to 0.52f)
            for ((c, t) in bands) {
                val p = Path().apply {
                    moveTo(r.left, r.top + h * (t + 0.30f))
                    cubicTo(r.left + w * 0.30f, r.top + h * (t - 0.10f), r.left + w * 0.60f, r.top + h * (t + 0.40f), r.right, r.top + h * (t - 0.05f))
                    lineTo(r.right, r.top + h * (t + 0.12f))
                    cubicTo(r.left + w * 0.60f, r.top + h * (t + 0.55f), r.left + w * 0.30f, r.top + h * (t + 0.05f), r.left, r.top + h * (t + 0.50f))
                    close()
                }
                drawPath(p, c)
            }
        }
        BuiltinBackgrounds.CIRCUIT -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0A1020), Color(0xFF111C33)), r.top, r.bottom), r.topLeft, r.size)
            val step = h / 9f
            val line = Color(0x3338A0FF)
            var gx = r.left
            while (gx <= r.right) { drawLine(line, Offset(gx, r.top), Offset(gx, r.bottom), strokeWidth = 1.5f); gx += step }
            var gy = r.top
            while (gy <= r.bottom) { drawLine(line, Offset(r.left, gy), Offset(r.right, gy), strokeWidth = 1.5f); gy += step }
            // A few traces with nodes.
            val trace = Color(0x8838D0FF)
            val pts = listOf(0.1f to 0.3f, 0.28f to 0.3f, 0.28f to 0.6f, 0.5f to 0.6f, 0.5f to 0.25f, 0.8f to 0.25f, 0.8f to 0.7f)
            for (i in 0 until pts.size - 1) {
                drawLine(trace, Offset(r.left + w * pts[i].first, r.top + h * pts[i].second), Offset(r.left + w * pts[i + 1].first, r.top + h * pts[i + 1].second), strokeWidth = 4f, cap = StrokeCap.Round)
            }
            for ((px, py) in pts) {
                val c = Offset(r.left + w * px, r.top + h * py)
                drawCircle(Color(0xFF0A1020), step * 0.16f, c)
                drawCircle(trace, step * 0.16f, c, style = Stroke(3f))
            }
        }
        BuiltinBackgrounds.WAVES -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1240), Color(0xFF8A2F5A), Color(0xFFE08A4A)), r.top, r.bottom), r.topLeft, r.size)
            for (k in 0 until 5) {
                val base = r.top + h * (0.35f + 0.13f * k)
                val amp = h * (0.05f + 0.01f * k)
                val p = Path().apply {
                    moveTo(r.left, r.bottom)
                    lineTo(r.left, base)
                    var xx = 0f
                    while (xx <= 1.001f) {
                        lineTo(r.left + w * xx, base + amp * sin((xx * 2.2f + k * 0.45f) * 2f * PI.toFloat()))
                        xx += 0.02f
                    }
                    lineTo(r.right, r.bottom)
                    close()
                }
                drawPath(p, Color(0xFF1A0A2E).copy(alpha = 0.16f + 0.12f * k))
            }
        }
    }
}

/** Small preview of [type] (shell, art and controls) for the type picker and appearance dialog. */
@Composable
fun ControllerPreview(
    type: ControllerType, skin: Skin, bg: Background, image: ImageBitmap?, modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        drawRect(skin.col(ColorSlot.Background))
        drawControllerBody(type, skin, bg, image)
        val idle = PadState()
        for (c in GamepadLayout.resolve(Profile("preview", "preview", controllerType = type), size.width, size.height)) {
            drawControlAlpha(c, idle, measurer, skin, 1f, type.domedButtons)
        }
    }
}

