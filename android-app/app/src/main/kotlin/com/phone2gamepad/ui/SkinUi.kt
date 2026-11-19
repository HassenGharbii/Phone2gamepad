package com.phone2gamepad.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.sp
import com.phone2gamepad.AppViewModel
import com.phone2gamepad.data.ColorSlot
import com.phone2gamepad.data.LabelStyle
import com.phone2gamepad.data.Skin
import com.phone2gamepad.data.SkinCodec
import com.phone2gamepad.data.SkinShape
import com.phone2gamepad.data.Skins
import com.phone2gamepad.protocol.Buttons
import com.phone2gamepad.protocol.Dpad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mini gamepad drawn with the real renderer, used for gallery cards, chips and the creator preview. */
@Composable
fun SkinPreview(skin: Skin, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    // A few controls shown pressed so the pressed colours are visible too.
    val pad = remember { PadState().apply { buttons = Buttons.A or Buttons.RB; dpad = Dpad.UP; lt = 150 } }
    Canvas(modifier.aspectRatio(2f).clip(RoundedCornerShape(10.dp)).background(skin.col(ColorSlot.Background))) {
        for (c in GamepadLayout.standard(size.width, size.height)) drawControl(c, pad, measurer, skin)
    }
}

/** Small selectable skin tile (used by the layout editor's live skin picker). */
@Composable
fun SkinChip(skin: Skin, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(132.dp).clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SkinPreview(skin, Modifier.fillMaxWidth())
        Text(skin.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) Palette.Accent else Palette.Text)
    }
}

private fun safeFileName(name: String) = name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "skin" }

@Composable
fun SkinGalleryScreen(vm: AppViewModel) {
    val library by vm.skins.collectAsState()
    val book by vm.profiles.collectAsState()
    val backup by vm.skinBackupPresent.collectAsState()
    val profile = book.profiles.firstOrNull { it.id == vm.editingId } ?: book.active
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Skin?>(null) }
    var exporting by remember { mutableStateOf<Skin?>(null) }

    // Storage Access Framework: the user picks the file, so no storage permission is needed.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val s = exporting
        exporting = null
        if (uri != null && s != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(SkinCodec.encodeExport(s).toByteArray(Charsets.UTF_8)) } != null
                } catch (e: Exception) {
                    false
                }
            }
            vm.notice = if (ok) "Exported \"${s.name}\"." else "Could not write the file."
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { readLimited(it, SkinCodec.MAX_IMPORT_BYTES) }
                    if (bytes == null) SkinCodec.ImportResult.Rejected("The file is unreadable or too large for a skin (max 16 KB).")
                    else SkinCodec.decodeImport(bytes.toString(Charsets.UTF_8))
                } catch (e: Exception) {
                    SkinCodec.ImportResult.Rejected("The file could not be read.")
                }
            }
            when (result) {
                is SkinCodec.ImportResult.Ok -> vm.importSkin(result.skin)
                is SkinCodec.ImportResult.Rejected -> vm.notice = "Skin not imported: ${result.reason}"
            }
        }
    }

    Page(
        "Skins", onBack = { vm.back() },
        actions = { TextAction("Import", { importLauncher.launch(arrayOf("*/*")) }) },
        bottomBar = { SecondaryButton("Create your own skin", { vm.duplicateSkin(Skins.CLASSIC_ID, open = true) }, Modifier.fillMaxWidth(), icon = P2GIcons.Add) },
    ) {
        if (backup) {
            Notice(
                "The custom skin file was damaged (or written by a newer version), so only the built-in skins are shown. The unreadable data was kept aside.",
                Tone.Warn, title = "Saved skins could not be read", actionLabel = "Dismiss", onAction = { vm.dismissSkinBackupNotice() },
            )
        }
        Text("Tap a skin to use it on \"${profile.name}\". Skins change colours and shapes only, never how controls behave.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
        for (pair in library.all.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (s in pair) {
                    SkinCard(
                        skin = s, applied = s.id == profile.skinId, bundled = library.isBundled(s.id), modifier = Modifier.weight(1f),
                        onUse = { vm.setProfileSkin(profile.id, s.id) },
                        onEdit = { vm.openSkinEditor(s.id) },
                        onDuplicate = { vm.duplicateSkin(s.id, open = false) },
                        onExport = { exporting = s; exportLauncher.launch(safeFileName(s.name) + SkinCodec.FILE_EXTENSION) },
                        onDelete = { deleting = s },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            "Share skins as .p2gskin files with Export and Import. There is no online catalog yet.",
            color = Palette.TextDim, style = MaterialTheme.typography.bodySmall,
        )
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Delete \"${s.name}\"?") },
            text = { Text("Profiles using this skin go back to Classic.") },
            confirmButton = { TextButton(onClick = { vm.deleteSkin(s.id); deleting = null }) { Text("Delete", color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SkinCard(
    skin: Skin, applied: Boolean, bundled: Boolean, modifier: Modifier,
    onUse: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember(skin.id) { mutableStateOf(false) }
    AppCard(modifier, onClick = if (applied) null else onUse, selected = applied, padding = Space.s) {
        SkinPreview(skin, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = Space.xs)) {
                Text(skin.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (applied) "In use" else if (bundled) "Built in" else (skin.author.ifEmpty { "Custom" }),
                    color = if (applied) Palette.Ok else Palette.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(P2GIcons.MoreVert, contentDescription = "More for ${skin.name}") }
                DropdownMenu(menu, { menu = false }) {
                    if (!applied) DropdownMenuItem(text = { Text("Use") }, onClick = { menu = false; onUse() })
                    if (!bundled) DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(if (bundled) "Customize a copy" else "Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Export to file") }, onClick = { menu = false; onExport() })
                    if (!bundled) DropdownMenuItem(text = { Text("Delete", color = Palette.Danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private val PALETTE = listOf(
    0xFF0E1013, 0xFF22262D, 0xFF59616E, 0xFFFFFFFF, 0xFFE5534B, 0xFFFF8A1F, 0xFFE6B450, 0xFF58C46A,
    0xFF2DD4BF, 0xFF4D8FE8, 0xFF8B5CF6, 0xFFFB7185,
).map { it.toInt() }

private fun shapeLabel(s: SkinShape) = when (s) {
    SkinShape.Circle -> "Circle"
    SkinShape.RoundedSquare -> "Rounded"
    SkinShape.Hexagon -> "Hexagon"
    SkinShape.Angular -> "Angular"
}

@Composable
private fun ShapeRow(title: String, value: SkinShape, onChange: (SkinShape) -> Unit) {
    Text(title)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in SkinShape.entries) FilterChip(selected = s == value, onClick = { onChange(s) }, label = { Text(shapeLabel(s)) })
    }
}

@Composable
private fun HsvSliders(argb: Int, onChange: (Int) -> Unit) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
    fun emit(h: Float, s: Float, v: Float) = onChange(Color.hsv(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)).toArgb())
    Column {
        Text("Hue", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Slider(hsv[0], { emit(it, hsv[1], hsv[2]) }, valueRange = 0f..360f)
        Text("Saturation", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Slider(hsv[1], { emit(hsv[0], it, hsv[2]) }, valueRange = 0f..1f)
        Text("Brightness", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Slider(hsv[2], { emit(hsv[0], hsv[1], it) }, valueRange = 0f..1f)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in PALETTE) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color(p)).border(1.dp, Palette.Outline, CircleShape).clickable { onChange(p) })
            }
        }
    }
}

@Composable
fun SkinEditorScreen(vm: AppViewModel) {
    val library by vm.skins.collectAsState()
    val stored = library.custom.firstOrNull { it.id == vm.editingSkinId }
    if (stored == null) {
        Page("Edit skin", onBack = { vm.back() }) { Text("This skin no longer exists.", color = Palette.TextDim) }
        return
    }
    // Working copy so sliders are smooth; written back on Done / system back.
    var work by remember(stored.id) { mutableStateOf(stored) }
    var open by remember { mutableStateOf<ColorSlot?>(null) }
    fun finish() { vm.saveSkin(work); vm.back() }
    BackHandler { finish() }

    Page("Edit skin", onBack = { finish() }) {
        SkinPreview(work, Modifier.fillMaxWidth())
        OutlinedTextField(
            work.name, { work = work.copy(name = it.take(Skin.MAX_NAME)) }, singleLine = true, label = { Text("Skin name") },
            modifier = Modifier.fillMaxWidth(),
        )
        ShapeRow("Face buttons (A B X Y)", work.faceShape) { work = work.copy(faceShape = it) }
        ShapeRow("Buttons, bumpers and triggers", work.buttonShape) { work = work.copy(buttonShape = it) }
        ShapeRow("Sticks and D-pad", work.padShape) { work = work.copy(padShape = it) }
        Text("Outline thickness: ${"%.1f".format(work.outlineWidth)}")
        Slider(work.outlineWidth, { work = work.copy(outlineWidth = it) }, valueRange = 0f..Skin.MAX_OUTLINE)
        Text("Label style")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in LabelStyle.entries) FilterChip(selected = work.labelStyle == l, onClick = { work = work.copy(labelStyle = l) }, label = { Text(l.name) })
        }
        Text("Colours", style = MaterialTheme.typography.titleSmall)
        for (slot in ColorSlot.entries) {
            val argb = work.color(slot)
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (open == slot) null else slot }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(argb)).border(1.dp, Palette.Outline, CircleShape))
                    Text(slot.label, Modifier.weight(1f))
                    Text(if (open == slot) "Close" else "Change", color = Palette.Accent, style = MaterialTheme.typography.labelLarge)
                }
                if (open == slot) HsvSliders(argb) { work = work.withColor(slot, it) }
            }
        }
        Button(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Done") }
        Spacer(Modifier.height(12.dp))
    }
}
