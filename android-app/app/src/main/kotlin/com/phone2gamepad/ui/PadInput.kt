package com.phone2gamepad.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Multi-touch handling shared by the gamepad and the editor's preview: each finger is bound to the control it
 * landed on and keeps driving that control until lifted, so lifting one finger never affects another control.
 * [onChanged] runs after every event batch (publish state / request redraw); [onButtonDown] is for haptics.
 */
fun Modifier.padTouch(
    controls: List<Control>,
    active: Boolean,
    generation: Int,
    pad: PadState,
    tuning: () -> Tuning,
    onButtonDown: () -> Unit,
    onChanged: () -> Unit,
): Modifier = pointerInput(controls, generation, active) {
    if (controls.isEmpty() || !active) return@pointerInput
    awaitPointerEventScope {
        val owner = HashMap<PointerId, Control>()
        val held = HashSet<Control>()
        while (true) {
            val ev = awaitPointerEvent()
            for (ch in ev.changes) {
                val down = ch.pressed && !ch.previousPressed
                val up = !ch.pressed && ch.previousPressed
                val t = tuning()
                if (down) {
                    val hit = controls.filter { it !in held }.minByOrNull { it.hitDistance(ch.position) }
                    if (hit != null && hit.hitDistance(ch.position) != Float.MAX_VALUE) {
                        owner[ch.id] = hit
                        held += hit
                        pad.apply(hit, ch.position, t)
                        if (hit.kind == Kind.Button) onButtonDown()
                    }
                } else if (ch.pressed) {
                    owner[ch.id]?.let { pad.apply(it, ch.position, t) }
                } else if (up) {
                    owner.remove(ch.id)?.let { pad.release(it, t); held -= it }
                }
                ch.consume()
            }
            onChanged()
        }
    }
}
