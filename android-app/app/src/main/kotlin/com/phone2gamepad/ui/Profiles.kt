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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextOverflow
import com.phone2gamepad.AppViewModel
import com.phone2gamepad.data.ControllerType
import com.phone2gamepad.data.HandbrakeButton
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
    val n = p.controls.size
    val custom = if (n == 0) "standard layout" else "custom layout"
    val hand = if (p.leftHanded) " · left-handed" else ""
    return "${p.controllerType.label} · $skinName skin · $custom$hand"
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

    Page(
        "Profiles", onBack = { vm.back() },
        actions = {
            TextAction("Import", { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
        },
        bottomBar = { PrimaryButton("New profile", { creating = true }, Modifier.fillMaxWidth(), icon = P2GIcons.Add) },
    ) {
        if (backup) {
            Notice(
                "The profile file was damaged (or written by a newer version), so a fresh Standard profile is being used. The unreadable data was kept aside.",
                Tone.Warn, title = "Saved profiles could not be read", actionLabel = "Dismiss", onAction = { vm.dismissProfileBackupNotice() },
            )
        }
        Text("Each profile has its own controller type, skin, layout and tuning. The selected one is used when you play.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
        for (p in book.profiles) {
            val active = p.id == book.activeId
            var menu by remember(p.id) { mutableStateOf(false) }
            val skin = library.resolve(p.skinId)
            AppCard(selected = active, padding = Space.m, onClick = if (active) null else ({ vm.selectProfile(p.id) })) {
                ProfilePreview(p, skin, rememberBackgroundImage(vm, p.background), Modifier.fillMaxWidth())
                Row(Modifier.padding(top = Space.s, start = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (active) { Spacer(Modifier.width(Space.s)); StatusChip("In use", Tone.Ok) }
                        }
                        Text(summary(p, skin.name), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(P2GIcons.MoreVert, contentDescription = "More for ${p.name}") }
                        DropdownMenu(menu, { menu = false }) {
                            if (!active) DropdownMenuItem(text = { Text("Use this profile") }, onClick = { menu = false; vm.selectProfile(p.id) })
                            DropdownMenuItem(text = { Text("Edit layout") }, onClick = { menu = false; vm.openLayoutEditor(p.id) })
                            DropdownMenuItem(text = { Text("Controller type & art") }, onClick = { menu = false; appearanceFor = p.id })
                            DropdownMenuItem(text = { Text("Skin") }, onClick = { menu = false; vm.openSkins(p.id) })
                            DropdownMenuItem(text = { Text("Tuning") }, onClick = { menu = false; vm.openTuning(p.id) })
                            HorizontalDivider(color = Palette.OutlineSoft)
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = p })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicateProfile(p.id) })
                            DropdownMenuItem(text = { Text("Export to file") }, onClick = {
                                menu = false
                                exporting = p
                                exportLauncher.launch(p.name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".p2g.json")
                            })
                            DropdownMenuItem(text = { Text("Reset to default") }, onClick = { menu = false; resetting = p })
                            DropdownMenuItem(
                                text = { Text("Delete", color = if (book.profiles.size > 1) Palette.Danger else Palette.TextFaint) }, enabled = book.profiles.size > 1,
                                onClick = { menu = false; deleting = p },
                            )
                        }
                    }
                }
            }
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

    Page("Tuning", onBack = { vm.back() }) {
        Text("Profile \"${stored.name}\" · ${stored.controllerType.label}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        SectionTitle("Sticks")
        SliderRow("Stick dead zone", p.deadZone, 0f..Profile.MAX_DEADZONE, { "${(it * 100).toInt()}%" }, { val v = p.deadZone; vm.editProfile(stored.id) { it.copy(deadZone = v) } }) {
            p = p.copy(deadZone = it)
        }
        SliderRow("Stick sensitivity", p.sensitivity, Profile.MIN_SENS..Profile.MAX_SENS, { "%.2fx".format(it) }, { val v = p.sensitivity; vm.editProfile(stored.id) { it.copy(sensitivity = v) } }) {
            p = p.copy(sensitivity = it)
        }
        Text("Response curve", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in ResponseCurve.entries) FilterChip(selected = p.curve == c, onClick = { apply { it.copy(curve = c) } }, label = { Text(c.name) })
        }
        Text(
            "Linear: output follows the stick. Quadratic / Cubic: finer control near the centre, full output only at the edge.",
            color = Palette.TextDim, style = MaterialTheme.typography.bodySmall,
        )
        SectionTitle("Triggers")
        SwitchRow("Digital triggers", "LT/RT send a full press on touch instead of analog position", p.triggerMode == TriggerMode.Digital) { on ->
            apply { it.copy(triggerMode = if (on) TriggerMode.Digital else TriggerMode.Analog) }
        }
        if (stored.controllerType == ControllerType.Racing) {
            SectionTitle("Steering wheel")
            Text("Wheel rotation (lock to lock)", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (v in Profile.WHEEL_LOCK_CHOICES) FilterChip(
                    selected = p.wheelLockDeg == v, onClick = { apply { it.copy(wheelLockDeg = v) } }, label = { Text("${v.toInt()}°") },
                )
            }
            Text("A wheel turned half of this range each way gives full steering.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
            SliderRow("Wheel sensitivity", p.wheelSensitivity, Profile.MIN_SENS..Profile.MAX_SENS, { "%.2fx".format(it) }, { val v = p.wheelSensitivity; vm.editProfile(stored.id) { it.copy(wheelSensitivity = v) } }) {
                p = p.copy(wheelSensitivity = it)
            }
            SliderRow("Wheel centre dead zone", p.wheelDeadZone, 0f..Profile.WHEEL_MAX_DEADZONE, { "${(it * 100).toInt()}%" }, { val v = p.wheelDeadZone; vm.editProfile(stored.id) { it.copy(wheelDeadZone = v) } }) {
                p = p.copy(wheelDeadZone = it)
            }
            Text("Wheel response", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in ResponseCurve.entries) FilterChip(selected = p.wheelCurve == c, onClick = { apply { it.copy(wheelCurve = c) } }, label = { Text(c.name) })
            }
            SwitchRow("Wheel returns to centre", "Off: the wheel stays turned where you let go", p.wheelSelfCenter) { on ->
                apply { it.copy(wheelSelfCenter = on) }
            }
            SwitchRow("Show steering angle", "Small read-out of the wheel angle in degrees", p.wheelShowAngle) { on -> apply { it.copy(wheelShowAngle = on) } }

            SectionTitle("Pedals")
            SwitchRow("Lower = more pressed", "On: slide down the pedal to press it (like the triggers). Off: slide up", p.pedalPressDown) { on ->
                apply { it.copy(pedalPressDown = on) }
            }
            SwitchRow("Full press on touch", "Gas and brake go to 100% as soon as you touch them", p.pedalFullPress) { on -> apply { it.copy(pedalFullPress = on) } }
            Text("Brake response", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in ResponseCurve.entries) FilterChip(selected = p.brakeCurve == c, onClick = { apply { it.copy(brakeCurve = c) } }, label = { Text(c.name) })
            }
            SliderRow("Brake sensitivity", p.brakeSensitivity, Profile.MIN_SENS..Profile.MAX_SENS, { "%.2fx".format(it) }, { val v = p.brakeSensitivity; vm.editProfile(stored.id) { it.copy(brakeSensitivity = v) } }) {
                p = p.copy(brakeSensitivity = it)
            }
            Text("Handbrake sends", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (h in HandbrakeButton.entries) FilterChip(selected = p.handbrake == h, onClick = { apply { it.copy(handbrake = h) } }, label = { Text(h.label) })
            }

            SectionTitle("Tilt steering")
            SwitchRow("Steer by tilting the phone", "Off by default. Hold the phone like a wheel; the pose when you start is straight ahead. Use Recenter on the pad any time", p.tiltEnabled) { on ->
                apply { it.copy(tiltEnabled = on) }
            }
            SliderRow("Tilt sensitivity", p.tiltSensitivity, Profile.TILT_MIN_SENS..Profile.TILT_MAX_SENS, { "%.2fx".format(it) }, { val v = p.tiltSensitivity; vm.editProfile(stored.id) { it.copy(tiltSensitivity = v) } }) {
                p = p.copy(tiltSensitivity = it)
            }
            SliderRow("Tilt smoothing", p.tiltSmoothing, 0f..Profile.TILT_MAX_SMOOTHING, { "${(it * 100).toInt()}%" }, { val v = p.tiltSmoothing; vm.editProfile(stored.id) { it.copy(tiltSmoothing = v) } }) {
                p = p.copy(tiltSmoothing = it)
            }
            SwitchRow("Invert tilt direction", "Swap left and right", p.tiltInvert) { on -> apply { it.copy(tiltInvert = on) } }
            Text("45° of tilt gives full steering at 1.00x.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        SectionTitle("Layout")
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
        SecondaryButton("Move and resize controls", { vm.openLayoutEditor(stored.id) }, Modifier.fillMaxWidth(), icon = P2GIcons.Layout)
    }
}

@Composable
private fun SectionTitle(text: String) = SectionHeader(text)
