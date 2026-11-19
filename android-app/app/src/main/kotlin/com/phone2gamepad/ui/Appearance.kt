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

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f), shape = RoundedCornerShape(16.dp), color = Palette.Surface,
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Preview", fontSize = 13.sp, color = Palette.TextDim)
                        ControllerPreview(profile.controllerType, skin, bg, image, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)))
                        Text(profile.controllerType.description, fontSize = 12.sp, color = Palette.TextDim)
                        error?.let { Text(it, fontSize = 12.sp, color = Palette.Danger) }
                        Row(Modifier.weight(1f)) {}
                        TextButton(onClick = onClose, Modifier.align(Alignment.End)) { Text("Done") }
                    }
                    Column(
                        Modifier.weight(1.3f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Controller", fontSize = 14.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (t in ControllerType.entries) TypeCard(t, skin, bg, image, selected = t == profile.controllerType) {
                                if (t != profile.controllerType) {
                                    vm.connection.input.releaseAll()
                                    onChange(profile.copy(controllerType = t))
                                }
                            }
                        }
                        Text("Printed on the controller", fontSize = 14.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = bg.kind == BackgroundKind.None, onClick = { setBg(Background.None) }, label = { Text("None", fontSize = 12.sp) })
                            for (id in BuiltinBackgrounds.IDS) {
                                FilterChip(
                                    selected = bg.kind == BackgroundKind.Builtin && bg.ref == id,
                                    onClick = { setBg(Background(BackgroundKind.Builtin, id, bg.scrim)) },
                                    label = { Text(BuiltinBackgrounds.label(id), fontSize = 12.sp) },
                                )
                            }
                            if (bg.kind == BackgroundKind.Image) FilterChip(selected = true, onClick = {}, label = { Text("My picture", fontSize = 12.sp) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                                Text("Choose picture…", fontSize = 12.sp)
                            }
                            if (bg.kind == BackgroundKind.Image) OutlinedButton(onClick = { setBg(Background.None) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                                Text("Remove picture", fontSize = 12.sp)
                            }
                        }
                        Text(
                            "The picture stays on this phone only; it is never sent to the PC or included when you export a profile or skin.",
                            fontSize = 11.sp, color = Palette.TextDim,
                        )
                        if (bg.kind != BackgroundKind.None) {
                            MiniSlider("Darkness", bg.scrim, 0f..Background.MAX_SCRIM, "${(bg.scrim * 100).toInt()}%") { setBg(bg.copy(scrim = it)) }
                        }
                        if (bg.kind == BackgroundKind.Image) {
                            MiniSlider("Zoom", bg.zoom, 1f..Background.MAX_ZOOM, "%.2fx".format(bg.zoom)) { setBg(bg.copy(zoom = it)) }
                            MiniSlider("Left / right", bg.offsetX, -1f..1f, "%+.2f".format(bg.offsetX)) { setBg(bg.copy(offsetX = it)) }
                            MiniSlider("Up / down", bg.offsetY, -1f..1f, "%+.2f".format(bg.offsetY)) { setBg(bg.copy(offsetY = it)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeCard(type: ControllerType, skin: Skin, bg: Background, image: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(124.dp).clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ControllerPreview(type, skin, bg, image, Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(8.dp)))
        Text(type.label, fontSize = 13.sp, color = if (selected) Palette.Accent else Palette.Text)
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
