package com.phone2gamepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phone2gamepad.AppViewModel
import com.phone2gamepad.BuildConfig
import com.phone2gamepad.Screen
import com.phone2gamepad.net.ConnState
import com.phone2gamepad.net.DiscoveredPc
import com.phone2gamepad.net.PcTarget
import com.phone2gamepad.protocol.Wire
import kotlinx.coroutines.delay

/** "Step n of 3" caption shown on the onboarding screens until the first PC is set up. */
@Composable
private fun StepCaption(vm: AppViewModel, step: Int) {
    if (!vm.hasPc) Text("Step $step of 3", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
}

@Composable
private fun Hero(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Box(Modifier.size(64.dp).background(Palette.AccentContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = Palette.Accent)
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim)
    }
}

@Composable
private fun Requirement(n: Int, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).border(1.dp, Palette.Outline, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        }
    }
}

// ---------------------------------------------------------------- onboarding step 1

@Composable
fun WelcomeScreen(vm: AppViewModel) {
    Page(
        "", onBack = if (vm.hasPc) ({ vm.back() }) else null,
        bottomBar = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                PrimaryButton("Find my PC", { vm.navigate(Screen.Discover) }, Modifier.fillMaxWidth(), icon = P2GIcons.Wifi)
                TextAction("Enter the PC's address instead", { vm.navigate(Screen.Manual) }, Modifier.fillMaxWidth())
            }
        },
    ) {
        StepCaption(vm, 1)
        Hero(P2GIcons.Gamepad, "Your phone is the controller", "Play PC games with a touch controller that Windows sees as an Xbox pad.")
        Spacer(Modifier.height(Space.s))
        Requirement(1, "Start the PC app", "Open Phone2Gamepad on your Windows PC and click Start server.")
        Requirement(2, "Same Wi-Fi", "Connect this phone to the same network as the PC.")
        Requirement(3, "Pair once", "Enter the 6-digit PIN the PC shows. Next time it connects by itself.")
    }
}

// ---------------------------------------------------------------- onboarding step 2

@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val pcs by vm.discovered.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.scanStartedAt) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val showHelp = DiscoveryHelp.showHelp(now - vm.scanStartedAt, pcs.size)

    Page(
        "Choose your PC", onBack = { vm.back() },
        actions = { IconButton(onClick = { vm.startScan() }) { Icon(P2GIcons.Refresh, contentDescription = "Search again") } },
        bottomBar = { SecondaryButton("Enter IP address manually", { vm.navigate(Screen.Manual) }, Modifier.fillMaxWidth()) },
    ) {
        StepCaption(vm, 2)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(
                if (pcs.isEmpty()) "Looking for PCs on this Wi-Fi…" else "Tap your PC to connect.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim,
            )
            if (vm.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
        }
        if (pcs.isNotEmpty()) {
            RowGroup {
                pcs.forEachIndexed { i, pc ->
                    if (i > 0) HorizontalDivider(color = Palette.OutlineSoft)
                    PcRow(pc, isLast = pc.host == vm.lastPc?.host) { vm.connect(PcTarget(pc.name, pc.host, pc.tcpPort)) }
                }
            }
        }
        if (showHelp) {
            Notice(
                title = "No PC found yet",
                text = "• Is the Phone2Gamepad app running on the PC with the server started?\n" +
                    "• Are both on the same Wi-Fi? Guest networks and \"client isolation\" block discovery.\n" +
                    "• Did Windows ask about the firewall? Allow Phone2Gamepad on private networks.\n" +
                    "• Still nothing: enter the PC's IP address (shown in the PC app).",
                tone = Tone.Warn,
            )
        }
    }
}

@Composable
private fun PcRow(pc: DiscoveredPc, isLast: Boolean, onClick: () -> Unit) {
    ListRow(
        title = pc.name,
        subtitle = if (isLast) "Last used · ${pc.host}" else pc.host,
        icon = P2GIcons.Computer,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pc.pairingOpen) StatusChip("Pairing open", Tone.Ok)
                Icon(P2GIcons.ChevronRight, null, tint = Palette.TextDim)
            }
        },
    )
}

@Composable
fun ManualScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    var host by rememberSaveable { mutableStateOf(s.lastHost) }
    var port by rememberSaveable { mutableStateOf(s.lastTcpPort.toString()) }
    val portNum = port.toIntOrNull()
    val valid = host.isNotBlank() && portNum != null && portNum in 1..65535
    Page(
        "Enter PC address", onBack = { vm.back() },
        bottomBar = { PrimaryButton("Connect", { vm.connect(PcTarget(host, host, portNum!!)) }, Modifier.fillMaxWidth(), enabled = valid) },
    ) {
        Text("The PC app shows its address on the dashboard, for example 192.168.1.20.", color = Palette.TextDim)
        OutlinedTextField(
            host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
            supportingText = { Text("Usually ${Wire.DEFAULT_TCP_PORT}. Change it only if you changed it on the PC.") },
            isError = port.isNotEmpty() && (portNum == null || portNum !in 1..65535),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------- onboarding step 3: connect & pair

/** Six digit boxes over one invisible text field: auto-advance, backspace and paste come for free. */
@Composable
fun PinField(pin: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(enabled) { if (enabled) runCatching { focus.requestFocus() } }
    BasicTextField(
        value = pin,
        onValueChange = { onChange(PinInput.sanitize(it)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        cursorBrush = SolidColor(Palette.Accent),
        modifier = modifier.focusRequester(focus).semantics { contentDescription = "6-digit PIN, ${pin.length} of 6 entered" },
        decorationBox = { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.fillMaxWidth()) {
                for (i in 0 until PinInput.LENGTH) {
                    val ch = pin.getOrNull(i)
                    val active = enabled && i == pin.length
                    Box(
                        Modifier.weight(1f).heightIn(min = 56.dp)
                            .background(Palette.Surface, RoundedCornerShape(12.dp))
                            .border(if (active) 2.dp else 1.dp, if (active) Palette.Accent else Palette.Outline, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(ch?.toString() ?: "", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                    if (i == 2) Spacer(Modifier.width(Space.xs))
                }
            }
        },
    )
}

@Composable
fun ConnectScreen(vm: AppViewModel) {
    val conn by vm.connection.state.collectAsState()
    val name = vm.lastPc?.name ?: "your PC"
    var pin by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    // A fresh NeedsPairing (e.g. after a wrong PIN) re-enables the field and clears the digits.
    LaunchedEffect(conn) { if (conn is ConnState.NeedsPairing && submitted) { submitted = false; pin = "" } }

    Page(if (conn is ConnState.NeedsPairing || conn is ConnState.Pairing) "Pair with $name" else "Connecting", onBack = { vm.back() }) {
        StepCaption(vm, 3)
        when (val c = conn) {
            is ConnState.NeedsPairing -> {
                Text("Enter the 6-digit PIN shown in the Phone2Gamepad app on your PC.", style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim)
                if (!c.pairingOpen && c.error == null) {
                    Notice("On your PC, click Open pairing to show a PIN. It stays valid for 2 minutes.", Tone.Warn, title = "Pairing isn't open yet")
                }
                c.error?.let {
                    val p = PairErrors.describe(c.errorCode, it)
                    Notice(p.detail, Tone.Error, title = p.title)
                }
                PinField(pin, { v ->
                    pin = v
                    if (PinInput.isComplete(v) && !submitted) { submitted = true; vm.connection.submitPin(v) }
                }, enabled = !submitted, modifier = Modifier.fillMaxWidth().padding(top = Space.s))
                Text("You only need to do this once for this PC.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            is ConnState.Pairing -> WaitBlock(
                P2GIcons.Computer, "Approve on your PC",
                "Phone2Gamepad on $name is asking whether to allow this phone. Click Allow there to finish.",
            ) { SecondaryButton("Cancel", { vm.back() }) }
            is ConnState.Idle, is ConnState.Connecting -> WaitBlock(P2GIcons.Wifi, "Connecting to $name…", "This takes a second on a good Wi-Fi network.") {
                SecondaryButton("Cancel", { vm.back() })
            }
            is ConnState.Connected -> WaitBlock(P2GIcons.Check, "Connected", "Opening…") {}
            is ConnState.Reconnecting -> {
                if (submitted) {
                    WaitBlock(P2GIcons.Computer, "Checking PIN…", "") {}
                } else {
                    WaitBlock(P2GIcons.Warning, "Can't reach $name", c.reason) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            SecondaryButton("Choose another PC", { vm.changePc() })
                        }
                    }
                    Text("Retrying automatically (attempt ${c.attempt}).", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
            }
            is ConnState.Failed -> {
                Notice(c.message, Tone.Error, title = "Couldn't connect")
                PrimaryButton("Try again", { vm.connect(c.target) }, Modifier.fillMaxWidth())
                SecondaryButton("Choose another PC", { vm.changePc() }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun WaitBlock(icon: ImageVector, title: String, body: String, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(72.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh, strokeWidth = 3.dp)
            Icon(icon, null, Modifier.size(28.dp), tint = Palette.Text)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, textAlign = TextAlign.Center)
        actions()
    }
}

// ---------------------------------------------------------------- shared form rows (tuning / editors)

@Composable
internal fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String,
    onFinished: (() -> Unit)? = null, onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(fmt(value), style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
        }
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, onValueChangeFinished = onFinished, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
internal fun SwitchRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchListRow(label, sub, checked, horizontalPadding = 0.dp, onChange = onChange)
}

// ---------------------------------------------------------------- settings / about

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    Page("Settings", onBack = { vm.back() }) {
        SectionHeader("Connection")
        RowGroup {
            val pc = vm.lastPc
            ListRow(
                title = pc?.name ?: "No PC yet",
                subtitle = pc?.let { "${it.host}:${it.tcpPort}" } ?: "Find your PC to get started",
                icon = P2GIcons.Computer,
            )
            HorizontalDivider(color = Palette.OutlineSoft)
            ListRow("Change PC", "Pick another PC on this network or enter an address", P2GIcons.Wifi, onClick = { vm.changePc() })
        }
        SectionHeader("While playing")
        RowGroup {
            SwitchListRow("Vibrate on press", "A short tick when you press a button or a pedal step", s.haptics) { vm.updateSettings(s.copy(haptics = it)) }
            HorizontalDivider(color = Palette.OutlineSoft)
            SwitchListRow("Keep screen on", "The screen won't dim while the controller is open", s.keepScreenOn) { vm.updateSettings(s.copy(keepScreenOn = it)) }
            HorizontalDivider(color = Palette.OutlineSoft)
            SwitchListRow("Show connection status", "Small status pill at the top of the controller", s.showOverlay) { vm.updateSettings(s.copy(showOverlay = it)) }
        }
        SectionHeader("Controller")
        RowGroup {
            ListRow("Customize", "Layout, skins, controller type and tuning", P2GIcons.Tune, onClick = { vm.openCustomize() })
        }
        SectionHeader("About")
        RowGroup {
            ListRow("Help & about", "Version ${BuildConfig.VERSION_NAME} · how it works · troubleshooting", P2GIcons.Help, onClick = { vm.navigate(Screen.About) })
            if (BuildConfig.DEBUG) {
                HorizontalDivider(color = Palette.OutlineSoft)
                ListRow("Preview first-run screens", "Debug build only. Keeps your pairing.", P2GIcons.Info, onClick = { vm.previewOnboarding() })
            }
        }
    }
}

@Composable
fun AboutScreen(vm: AppViewModel) {
    Page("Help & about", onBack = { vm.back() }) {
        AppCard {
            Text("Phone2Gamepad", style = MaterialTheme.typography.titleMedium)
            Text("Version ${BuildConfig.VERSION_NAME} · protocol ${Wire.VERSION}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        SectionHeader("How it works")
        Text(
            "Your phone sends the controller state to the Phone2Gamepad app on your PC over your local Wi-Fi. " +
                "The PC turns it into a virtual Xbox controller that games see like a real one. " +
                "Pairing uses a one-time PIN; every session is authenticated. Nothing leaves your network and no account is needed.",
            color = Palette.TextDim,
        )
        SectionHeader("Troubleshooting")
        Notice("Check that both are on the same Wi-Fi and that Windows Firewall allows Phone2Gamepad on private networks (UDP ${Wire.DISCOVERY_PORT} and ${Wire.DEFAULT_UDP_PORT}, TCP ${Wire.DEFAULT_TCP_PORT}). Or enter the PC's address by hand.", title = "PC not found")
        Notice("PINs expire after 2 minutes. Click Open pairing on the PC for a new one.", title = "PIN not accepted")
        Notice("Open the PC app and check the driver status card; it tells you what to install.", title = "Game doesn't see the controller")
        Notice("This app only uses the network and vibration. It never contacts the internet.", title = "Permissions")
    }
}
