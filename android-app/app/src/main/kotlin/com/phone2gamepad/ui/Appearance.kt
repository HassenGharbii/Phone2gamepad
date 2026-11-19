package com.phone2gamepad.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phone2gamepad.AppViewModel
import com.phone2gamepad.data.Background
import com.phone2gamepad.data.BackgroundKind
import com.phone2gamepad.data.BuiltinBackgrounds
import com.phone2gamepad.data.ControllerType
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.Skin

/** The picture for [bg] (null for none/built-in/missing). Decoded off the main thread and cached while the reference is unchanged. */
@Composable
fun rememberBackgroundImage(vm: AppViewModel, bg: Background): ImageBitmap? {
    val ref = bg.imageRef
    val img by produceState<ImageBitmap?>(null, ref) { value = if (ref == null) null else vm.loadBackground(ref)?.asImageBitmap() }
    return img
}

/**
 * "Controller & background" dialog: pick the controller type (with preview thumbnails) and what is printed on its shell
 * (built-in art or a picture from the phone), with darkness and crop controls. Changes go out through [onChange] as a new
 * [profile]; the caller decides how they are stored.
 */
@Composable
fun AppearanceDialog(vm: AppViewModel, profile: Profile, skin: Skin, onChange: (Profile) -> Unit, onClose: () -> Unit) {
    val latest = rememberUpdatedState(profile)
    val image = rememberBackgroundImage(vm, profile.background)
    var error by remember { mutableStateOf<String?>(null) }
    val bg = profile.background

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackground(uri) { ref, err ->
            if (ref != null) {
                error = null
                val cur = latest.value
                onChange(cur.copy(background = Background(BackgroundKind.Image, ref, cur.background.scrim)))
            } else {
                error = err
            }
        }
    }

    fun setBg(b: Background) = onChange(profile.copy(background = b.sanitized()))

    val landscape = isLandscapePhone()
    val preview: @Composable (Modifier) -> Unit = { m ->
        ControllerPreview(profile.controllerType, skin, bg, image, m.aspectRatio(2.2f).clip(RoundedCornerShape(12.dp)))
    }
    val options: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            SectionHeader("Controller type")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (t in ControllerType.entries) TypeCard(t, skin, bg, image, selected = t == profile.controllerType) {
                    if (t != profile.controllerType) {
                        vm.connection.input.releaseAll()
                        onChange(profile.copy(controllerType = t))
                    }
                }
            }
            Text(profile.controllerType.description, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            SectionHeader("Printed on the controller")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FilterChip(selected = bg.kind == BackgroundKind.None, onClick = { setBg(Background.None) }, label = { Text("None") })
                for (id in BuiltinBackgrounds.IDS) {
                    FilterChip(
                        selected = bg.kind == BackgroundKind.Builtin && bg.ref == id,
                        onClick = { setBg(Background(BackgroundKind.Builtin, id, bg.scrim)) },
                        label = { Text(BuiltinBackgrounds.label(id)) },
                    )
                }
                if (bg.kind == BackgroundKind.Image) FilterChip(selected = true, onClick = {}, label = { Text("My picture") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SecondaryButton("Choose picture", { picker.launch(arrayOf("image/*")) })
                if (bg.kind == BackgroundKind.Image) SecondaryButton("Remove", { setBg(Background.None) })
            }
            error?.let { Notice(it, Tone.Error) }
            Text(
                "Your picture stays on this phone. It is never sent to the PC or included when you export a profile or skin.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
            )
            if (bg.kind != BackgroundKind.None) {
                SliderRow("Darkness", bg.scrim, 0f..Background.MAX_SCRIM, { "${(it * 100).toInt()}%" }) { setBg(bg.copy(scrim = it)) }
            }
            if (bg.kind == BackgroundKind.Image) {
                SliderRow("Zoom", bg.zoom, 1f..Background.MAX_ZOOM, { "%.2fx".format(it) }) { setBg(bg.copy(zoom = it)) }
                SliderRow("Left / right", bg.offsetX, -1f..1f, { "%+.2f".format(it) }) { setBg(bg.copy(offsetX = it)) }
                SliderRow("Up / down", bg.offsetY, -1f..1f, { "%+.2f".format(it) }) { setBg(bg.copy(offsetY = it)) }
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(if (landscape) 0.96f else 0.94f).fillMaxHeight(if (landscape) 0.94f else 0.9f),
            shape = MaterialTheme.shapes.large, color = Palette.Surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.xs, top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text("Controller type & art", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextAction("Done", onClose)
                }
                if (landscape) {
                    Row(Modifier.weight(1f).padding(horizontal = Space.l, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        Column(Modifier.weight(0.9f)) { preview(Modifier.fillMaxWidth()) }
                        Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState())) { options() }
                    }
                } else {
                    preview(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.l, vertical = Space.s)) { options() }
                }
            }
        }
    }
}

@Composable
private fun TypeCard(type: ControllerType, skin: Skin, bg: Background, image: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(132.dp).clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ControllerPreview(type, skin, bg, image, Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(8.dp)))
        Text(type.label, style = MaterialTheme.typography.labelLarge, color = if (selected) Palette.Accent else Palette.Text, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
    }
}

@Composable
private fun MiniSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp), fontSize = 12.sp)
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, Modifier.weight(1f).height(28.dp), valueRange = range)
        Text(valueText, fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(start = 8.dp).width(46.dp))
    }
}
