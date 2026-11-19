package com.phone2gamepad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phone2gamepad.AppViewModel
import com.phone2gamepad.data.ColorSlot
import com.phone2gamepad.data.ControlOverride
import com.phone2gamepad.data.Profile
import kotlin.math.hypot

/**
 * Layout editor: drag a control to move it, pinch (two fingers) or use the size slider to resize it, with options for
 * opacity, visibility, per-control reset, left-handed mirroring and overlap. Edits are made on a working copy of the
 * profile; Done / Back saves it, Cancel discards it. Preview mode lets you try the layout with nothing sent to the PC.
 */
@Composable
fun LayoutEditorScreen(vm: AppViewModel) {
    val book by vm.profiles.collectAsState()
    val stored = book.profiles.firstOrNull { it.id == vm.editingId }
    if (stored == null) {
        Page("Layout editor", onBack = { vm.back() }) { Text("This profile no longer exists.", color = Palette.TextDim) }
        return
    }
    LandscapeImmersiveEffect()
    // Working copy: initialised once per opened profile so storage updates cannot reset it mid-edit.
    var work by remember(stored.id) { mutableStateOf(stored) }
    var selected by remember(stored.id) { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    var skinPicker by remember { mutableStateOf(false) }
    val library by vm.skins.collectAsState()
    val skin = library.resolve(work.skinId)
    var options by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var redraw by remember { mutableIntStateOf(0) }
    val pad = remember { PadState() }
    val measurer = rememberTextMeasurer()

    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val all = remember(work, size) { if (size == IntSize.Zero) emptyList() else GamepadLayout.resolveAll(work, w, h) }
    val visibleOnly = remember(all) { all.filter { it.visible } }
    val allState = rememberUpdatedState(all)
    val workState = rememberUpdatedState(work)
    val sizeState = rememberUpdatedState(size)
    val selectedState = rememberUpdatedState(selected)

    fun save() = vm.saveProfile(work)
    BackHandler {
        save()
        vm.back()
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            kotlinx.coroutines.delay(2500)
            hint = null
        }
    }

    // Stored coordinates are un-mirrored; the screen shows them mirrored when left-handed.
    fun storedFx(px: Float, wd: Float, lh: Boolean) = (if (lh) wd - px else px) / wd

    /** Tries to move control [id] to a new centre; slides along one axis if the diagonal is blocked by a neighbour. */
    fun tryMove(id: String, nx: Float, ny: Float) {
        val ctl = allState.value.firstOrNull { it.id == id } ?: return
        val cur = workState.value
        val sz = sizeState.value
        val wd = sz.width.toFloat()
        val ht = sz.height.toFloat()
        val b = ctl.box()
        val (cx, cy) = LayoutMath.clampCentre(nx, ny, (b[2] - b[0]) / 2f, (b[3] - b[1]) / 2f, wd, ht)
        val options = listOf(cx to cy, cx to ctl.cy, ctl.cx to cy)
        val target = options.firstOrNull { (px, py) ->
            cur.allowOverlap || LayoutMath.placementAllowed(ctl, ctl.withCentre(px, py), allState.value)
        }
        if (target == null) {
            hint = "Blocked by a neighbouring control (turn on \"Allow overlap\" to place it there)"
            return
        }
        work = cur.withOverride(id, cur.override(id).copy(fx = storedFx(target.first, wd, cur.leftHanded), fy = target.second / ht))
    }

    fun tryScale(id: String, newScale: Float) {
        val ctl = allState.value.firstOrNull { it.id == id } ?: return
        val cur = workState.value
        val o = cur.override(id)
        val target = newScale.coerceIn(Profile.MIN_SCALE, Profile.MAX_SCALE)
        if (o.scale <= 0f || target == o.scale) return
        val cand = ctl.withSizeFactor(target / o.scale)
        if (!cur.allowOverlap && !LayoutMath.placementAllowed(ctl, cand, allState.value)) {
            hint = "Blocked by a neighbouring control (turn on \"Allow overlap\" to make it bigger)"
            return
        }
        work = cur.withOverride(id, o.copy(scale = target))
    }

    Box(Modifier.fillMaxSize().background(skin.col(ColorSlot.Background))) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .alpha(work.opacity)
                .then(
                    if (preview) {
                        Modifier.padTouch(visibleOnly, true, 0, pad, { work.tuning() }, {}, { redraw++ })
                    } else {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                val pointers = LinkedHashMap<PointerId, Offset>()
                                var dragging: String? = null
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    for (ch in ev.changes) {
                                        if (ch.pressed && !ch.previousPressed) {
                                            pointers[ch.id] = ch.position
                                            if (pointers.size == 1) {
                                                // Prefer visible controls over hidden "ghosts" at the same spot.
                                                val hit = allState.value.minByOrNull { it.hitDistance(ch.position) + if (it.visible) 0f else 0.5f }
                                                dragging = if (hit != null && hit.hitDistance(ch.position) != Float.MAX_VALUE) hit.id else null
                                                selected = dragging
                                            }
                                        } else if (!ch.pressed && ch.previousPressed) {
                                            pointers.remove(ch.id)
                                            if (pointers.isEmpty()) dragging = null
                                        } else if (ch.pressed) {
                                            pointers[ch.id] = ch.position
                                        }
                                    }
                                    val id = selectedState.value
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (id != null && pressed.size >= 2) {
                                        val a = pressed[0]
                                        val b = pressed[1]
                                        val now = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                                        val before = hypot(a.previousPosition.x - b.previousPosition.x, a.previousPosition.y - b.previousPosition.y)
                                        if (before > 20f && now > 20f) {
                                            val o = workState.value.override(id)
                                            tryScale(id, o.scale * (now / before))
                                        }
                                    } else if (dragging != null && pressed.size == 1) {
                                        val c = pressed[0]
                                        val ctl = allState.value.firstOrNull { it.id == dragging }
                                        if (ctl != null) {
                                            val d = c.position - c.previousPosition
                                            if (d != Offset.Zero) tryMove(ctl.id, ctl.cx + d.x, ctl.cy + d.y)
                                        }
                                    }
                                    ev.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                )
        ) {
            redraw // subscribe to preview redraws
            if (preview) {
                for (c in visibleOnly) drawControlAlpha(c, pad, measurer, skin)
            } else {
                val idle = PadState()
                for (c in all) {
                    drawControlAlpha(c, idle, measurer, skin, if (c.visible) 1f else 0.28f)
                    val bx = c.box()
                    if (!c.visible) {
                        drawRoundRect(
                            Palette.Outline, Offset(bx[0], bx[1]), Size(bx[2] - bx[0], bx[3] - bx[1]),
                            androidx.compose.ui.geometry.CornerRadius(12f),
                            style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))),
                        )
                    }
                    if (c.id == selected) {
                        drawRoundRect(
                            Palette.Accent, Offset(bx[0] - 6, bx[1] - 6), Size(bx[2] - bx[0] + 12, bx[3] - bx[1] + 12),
                            androidx.compose.ui.geometry.CornerRadius(14f),
                            style = Stroke(5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
                        )
                    }
                }
            }
        }

        // Top bar
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth(0.66f).padding(top = 4.dp)
                .background(Palette.Surface.copy(alpha = 0.9f), RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = { save(); vm.back() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Done", fontSize = 13.sp) }
            Text(
                (if (preview) "Preview · " else "Editing · ") + stored.name,
                Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, color = Palette.TextDim,
            )
            TextButton(onClick = { preview = !preview; pad.reset(); redraw++ }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (preview) "Edit" else "Preview", fontSize = 13.sp)
            }
            TextButton(onClick = { skinPicker = !skinPicker }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (skinPicker) "Hide skins" else "Skin", fontSize = 13.sp)
            }
            if (!preview) TextButton(onClick = { options = !options }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (options) "Hide options" else "Options", fontSize = 13.sp)
            }
            TextButton(onClick = { vm.back() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Cancel", fontSize = 13.sp, color = Palette.Danger) }
        }

        val helper = when {
            preview -> "Preview: touch the controls to try them. Nothing is sent to the PC."
            selected == null -> "Drag a control to move it. Select it, then pinch with two fingers or use Options to resize."
            else -> null
        }
        if (skinPicker) {
            // Live preview: picking a skin redraws the pad behind this strip immediately; it is saved with Done.
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp, start = 12.dp, end = 12.dp).fillMaxWidth()
                    .background(Palette.Surface.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                    .horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (s in library.all) SkinChip(s, selected = s.id == skin.id) { work = work.copy(skinId = s.id) }
            }
        }
        if (helper != null && !options && !skinPicker) {
            Text(
                helper, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    .background(Palette.Surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                fontSize = 12.sp, color = Palette.TextDim,
            )
        }
        hint?.let {
            Text(
                it, Modifier.align(Alignment.Center).background(Palette.Surface.copy(alpha = 0.95f), RoundedCornerShape(8.dp)).padding(12.dp),
                fontSize = 13.sp, color = Palette.Warn,
            )
        }

        if (options && !preview && !skinPicker) {
            val sel = all.firstOrNull { it.id == selected }
            // Show the panel on the half of the screen away from the selected control.
            val atTop = sel != null && sel.cy > h / 2f
            // Compact rows: the default 48 dp touch-target minimum would make the panel cover too much of the pad.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Row(
                    Modifier.align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .padding(top = if (atTop) 44.dp else 0.dp, bottom = 6.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth().heightIn(max = 150.dp)
                        .background(Palette.Surface.copy(alpha = 0.95f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val id = selected
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (id == null) {
                            Text("Select a control", fontSize = 13.sp)
                            Text("Tap a control on the pad to change its size, opacity or visibility.", fontSize = 12.sp, color = Palette.TextDim)
                        } else {
                            val o = work.override(id)
                            Text("Control: $id", fontSize = 13.sp)
                            LabeledSlider("Size", o.scale, Profile.MIN_SCALE..Profile.MAX_SCALE, "%.2fx".format(o.scale)) { tryScale(id, it) }
                            LabeledSlider("Opacity", o.opacity, Profile.MIN_OPACITY..1f, "${(o.opacity * 100).toInt()}%") {
                                work = work.withOverride(id, o.copy(opacity = it))
                            }
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (id != null) {
                            val o = work.override(id)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Visible", Modifier.weight(1f), fontSize = 13.sp)
                                Switch(o.visible, { work = work.withOverride(id, o.copy(visible = it)) })
                            }
                            OutlinedButton(onClick = { work = work.withOverride(id, ControlOverride()) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                                Text("Reset this control", fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(onClick = { confirmRestore = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                            Text("Restore default layout", fontSize = 12.sp)
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Whole layout", fontSize = 13.sp)
                        LabeledSlider("Size", work.globalScale, Profile.MIN_GLOBAL_SCALE..Profile.MAX_GLOBAL_SCALE, "%.2fx".format(work.globalScale)) {
                            work = work.copy(globalScale = it)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Left-handed (mirror)", Modifier.weight(1f), fontSize = 13.sp)
                            Switch(work.leftHanded, { work = work.copy(leftHanded = it) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Allow overlap", Modifier.weight(1f), fontSize = 13.sp)
                            Switch(work.allowOverlap, { work = work.copy(allowOverlap = it) })
                        }
                    }
                }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore default layout?") },
            text = { Text("All positions, sizes, opacity and visibility return to the standard layout. Stick tuning is kept.") },
            confirmButton = { TextButton(onClick = { work = work.resetLayout(); selected = null; confirmRestore = false }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 8.dp), fontSize = 13.sp)
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, Modifier.weight(1f).height(32.dp), valueRange = range)
        Text(valueText, fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(start = 8.dp))
    }
}
