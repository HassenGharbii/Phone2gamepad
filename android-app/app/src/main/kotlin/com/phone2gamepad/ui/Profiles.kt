package com.phone2gamepad.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phone2gamepad.AppViewModel
import com.phone2gamepad.data.ControllerType
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.ProfileCodec
import com.phone2gamepad.data.TriggerMode
import com.phone2gamepad.protocol.ResponseCurve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

private fun summary(p: Profile, skinName: String): String {
    val trig = if (p.triggerMode == TriggerMode.Digital) "digital triggers" else "analog triggers"
    val hand = if (p.leftHanded) " · left-handed" else ""
    val n = p.controls.size
    val custom = if (n == 0) "default layout" else "$n customised control${if (n == 1) "" else "s"}"
    return "${p.controllerType.label} controller · $custom · $skinName skin · dead zone ${(p.deadZone * 100).toInt()}% · sensitivity ${"%.2f".format(p.sensitivity)}x · " +
        "${p.curve.name.lowercase()} curve · $trig$hand"
}

/** Reads at most [max] bytes; null if the stream is longer (so a huge file cannot exhaust memory). */
internal fun readLimited(input: InputStream, max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it.take(Profile.MAX_NAME) }, singleLine = true, label = { Text("Profile name") })
        },
        confirmButton = { TextButton(onClick = { onOk(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProfilesScreen(vm: AppViewModel) {
    val book by vm.profiles.collectAsState()
    val library by vm.skins.collectAsState()
    val backup by vm.profileBackupPresent.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var resetting by remember { mutableStateOf<Profile?>(null) }
    var exporting by remember { mutableStateOf<Profile?>(null) }
    var appearanceFor by remember { mutableStateOf<String?>(null) }

    // Storage Access Framework: no storage permission needed, the user picks the file.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = exporting
        exporting = null
        if (uri != null && p != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ProfileCodec.encodeExport(p).toByteArray(Charsets.UTF_8)) } != null
                } catch (e: Exception) {
                    false
                }
            }
            vm.notice = if (ok) "Exported \"${p.name}\"." else "Could not write the file."
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { readLimited(it, ProfileCodec.MAX_IMPORT_BYTES) }?.toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    null
                }
            }
            val p = text?.let { ProfileCodec.decodeExport(it) }
            if (p == null) {
                vm.notice = "That file is not a valid Phone2Gamepad profile."
            } else {
                vm.importProfile(p)
                vm.notice = "Imported \"${p.name}\"."
            }
        }
    }

    Page("Layouts & profiles", onBack = { vm.back() }) {
        vm.notice?.let { msg ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
                Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), color = Palette.Accent)
                    TextButton(onClick = { vm.notice = null }) { Text("OK") }
                }
            }
        }
        if (backup) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Saved profiles could not be read", color = Palette.Warn, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The profile file was damaged (or written by a newer version), so a fresh Standard profile is being used. " +
                            "The unreadable data was kept aside.",
                        color = Palette.TextDim,
                    )
                    TextButton(onClick = { vm.dismissProfileBackupNotice() }) { Text("Dismiss") }
                }
            }
        }
        Text("The active profile is used on the gamepad. Editing a layout is optional; the Standard layout works as it is.", color = Palette.TextDim)
        for (p in book.profiles) {
            val active = p.id == book.activeId
            var menu by remember(p.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (active) Text("Active", color = Palette.Accent, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(summary(p, library.resolve(p.skinId).name), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!active) Button(onClick = { vm.selectProfile(p.id) }) { Text("Use") }
                        OutlinedButton(onClick = { vm.openLayoutEditor(p.id) }) { Text("Edit layout") }
                        Box {
                            OutlinedButton(onClick = { menu = true }) { Text("More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Controller & background…") }, onClick = { menu = false; appearanceFor = p.id })
                                DropdownMenuItem(text = { Text("Skin…") }, onClick = { menu = false; vm.openSkins(p.id) })
                                DropdownMenuItem(text = { Text("Tuning…") }, onClick = { menu = false; vm.openTuning(p.id) })
                                DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; renaming = p })
                                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicateProfile(p.id) })
                                DropdownMenuItem(text = { Text("Export…") }, onClick = {
                                    menu = false
                                    exporting = p
                                    exportLauncher.launch(p.name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".p2g.json")
                                })
                                DropdownMenuItem(text = { Text("Reset to default") }, onClick = { menu = false; resetting = p })
                                DropdownMenuItem(
                                    text = { Text("Delete") }, enabled = book.profiles.size > 1,
                                    onClick = { menu = false; deleting = p },
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }, Modifier.weight(1f)) { Text("New profile") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                Modifier.weight(1f),
            ) { Text("Import…") }
        }
    }

    appearanceFor?.let { id ->
        val p = book.profiles.firstOrNull { it.id == id }
        if (p == null) appearanceFor = null else AppearanceDialog(
            vm, p, library.resolve(p.skinId),
            onChange = { np -> vm.editProfile(id) { it.copy(controllerType = np.controllerType, background = np.background) } },
            onClose = { appearanceFor = null },
        )
    }

    if (creating) NameDialog("New profile", "My layout", "Create", { creating = false }) { vm.createProfile(it); creating = false }
    renaming?.let { p -> NameDialog("Rename profile", p.name, "Rename", { renaming = null }) { vm.renameProfile(p.id, it); renaming = null } }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Delete \"${p.name}\"?") },
            text = { Text("This layout and its tuning will be removed.") },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(p.id); deleting = null }) { Text("Delete", color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    resetting?.let { p ->
        AlertDialog(
            onDismissRequest = { resetting = null }, title = { Text("Reset \"${p.name}\"?") },
            text = { Text("Layout and tuning go back to the standard defaults. The name is kept.") },
            confirmButton = { TextButton(onClick = { vm.resetProfile(p.id); resetting = null }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun TuningScreen(vm: AppViewModel) {
    val book by vm.profiles.collectAsState()
    val id = vm.editingId
    val stored = book.profiles.firstOrNull { it.id == id }
    if (stored == null) {
        Page("Tuning", onBack = { vm.back() }) { Text("This profile no longer exists.", color = Palette.TextDim) }
        return
    }
    // Local copy so sliders are smooth. Only the changed field is written back (to the stored profile), so layout edits made
    // in the editor are never overwritten by this screen.
    var p by remember(stored) { mutableStateOf(stored) }
    fun apply(f: (Profile) -> Profile) {
        p = f(p)
        vm.editProfile(stored.id, f)
    }

    Page("Tuning · ${stored.name}", onBack = { vm.back() }) {
        SliderRow("Stick dead zone", p.deadZone, 0f..Profile.MAX_DEADZONE, { "${(it * 100).toInt()}%" }, { val v = p.deadZone; vm.editProfile(stored.id) { it.copy(deadZone = v) } }) {
            p = p.copy(deadZone = it)
        }
        SliderRow("Stick sensitivity", p.sensitivity, Profile.MIN_SENS..Profile.MAX_SENS, { "%.2fx".format(it) }, { val v = p.sensitivity; vm.editProfile(stored.id) { it.copy(sensitivity = v) } }) {
            p = p.copy(sensitivity = it)
        }
        Text("Response curve")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in ResponseCurve.entries) FilterChip(selected = p.curve == c, onClick = { apply { it.copy(curve = c) } }, label = { Text(c.name) })
        }
        Text(
            "Linear: output follows the stick. Quadratic / Cubic: finer control near the centre, full output only at the edge.",
            color = Palette.TextDim, style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow("Digital triggers", "LT/RT send a full press on touch instead of analog position", p.triggerMode == TriggerMode.Digital) { on ->
            apply { it.copy(triggerMode = if (on) TriggerMode.Digital else TriggerMode.Analog) }
        }
        if (stored.controllerType == ControllerType.Racing) {
            SliderRow(
                "Wheel rotation (lock to lock)", p.wheelLockDeg, Profile.WHEEL_MIN_LOCK..Profile.WHEEL_MAX_LOCK, { "${it.toInt()}°" },
                { val v = p.wheelLockDeg; vm.editProfile(stored.id) { it.copy(wheelLockDeg = v) } },
            ) { p = p.copy(wheelLockDeg = it) }
            SwitchRow("Wheel returns to centre", "Off: the wheel stays turned where you let go", p.wheelSelfCenter) { on ->
                apply { it.copy(wheelSelfCenter = on) }
            }
        }
        SwitchRow("Sticks return to centre", "Off: a stick stays where you released it until you touch it again", p.stickReturnToCenter) { on ->
            apply { it.copy(stickReturnToCenter = on) }
        }
        SliderRow("Control opacity", p.opacity, Profile.MIN_OPACITY..1f, { "${(it * 100).toInt()}%" }, { val v = p.opacity; vm.editProfile(stored.id) { it.copy(opacity = v) } }) {
            p = p.copy(opacity = it)
        }
        SwitchRow("Left-handed layout", "Mirrors the whole layout left to right", p.leftHanded) { on -> apply { it.copy(leftHanded = on) } }
        SliderRow(
            "Overall control size", p.globalScale, Profile.MIN_GLOBAL_SCALE..Profile.MAX_GLOBAL_SCALE, { "%.2fx".format(it) },
            { val v = p.globalScale; vm.editProfile(stored.id) { it.copy(globalScale = v) } },
        ) { p = p.copy(globalScale = it) }
        Button(onClick = { vm.openLayoutEditor(stored.id) }, Modifier.fillMaxWidth()) { Text("Edit layout") }
    }
}
