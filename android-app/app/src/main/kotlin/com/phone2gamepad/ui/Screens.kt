package com.phone2gamepad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phone2gamepad.AppViewModel
import com.phone2gamepad.BuildConfig
import com.phone2gamepad.Screen
import com.phone2gamepad.data.Settings
import com.phone2gamepad.net.ConnState
import com.phone2gamepad.net.DiscoveredPc
import com.phone2gamepad.net.PcTarget
import com.phone2gamepad.protocol.Wire

@Composable
internal fun InfoCard(title: String, body: String, accent: Boolean = false) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (accent) Palette.Accent else Palette.Text)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        }
    }
}

@Composable
fun WelcomeScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    Page("Phone2Gamepad", onBack = null) {
        Text(
            "Turn this phone into an Xbox-style controller for your Windows PC.",
            style = MaterialTheme.typography.titleMedium,
        )
        InfoCard(
            "Before you start",
            "1. Install and start the Phone2Gamepad companion on your Windows PC.\n" +
                "2. Put the phone and the PC on the same Wi-Fi network.\n" +
                "3. Find the PC below and enter the PIN it shows (first connection only).",
        )
        Button(onClick = { vm.navigate(Screen.Discover) }, Modifier.fillMaxWidth()) { Text("Find my PC") }
        OutlinedButton(onClick = { vm.navigate(Screen.Manual) }, Modifier.fillMaxWidth()) { Text("Enter IP address manually") }
        if (s.lastHost.isNotEmpty()) {
            OutlinedButton(
                onClick = { vm.connect(PcTarget(s.lastName.ifEmpty { s.lastHost }, s.lastHost, s.lastTcpPort)) },
                Modifier.fillMaxWidth(),
            ) { Text("Reconnect to ${s.lastName.ifEmpty { s.lastHost }}") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.navigate(Screen.Profiles) }, Modifier.weight(1f)) { Text("Layouts") }
            OutlinedButton(onClick = { vm.navigate(Screen.Settings) }, Modifier.weight(1f)) { Text("Settings") }
            OutlinedButton(onClick = { vm.navigate(Screen.About) }, Modifier.weight(1f)) { Text("About") }
        }
    }
}

@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val pcs by vm.discovered.collectAsState()
    Page("Find your PC", onBack = { vm.back() }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                if (pcs.isEmpty()) "Searching the local network…" else "${pcs.size} PC found",
                color = Palette.TextDim,
            )
        }
        for (pc in pcs) PcRow(pc) { vm.connect(PcTarget(pc.name, pc.host, pc.tcpPort)) }
        if (pcs.isEmpty()) {
            InfoCard(
                "Nothing found yet?",
                "Make sure the Windows companion is running, both devices are on the same Wi-Fi, and Windows Firewall allows " +
                    "Phone2Gamepad (UDP ${Wire.DISCOVERY_PORT}, TCP ${Wire.DEFAULT_TCP_PORT}, UDP ${Wire.DEFAULT_UDP_PORT}). " +
                    "Guest or \"client isolation\" Wi-Fi networks block discovery; use manual IP instead.",
            )
        }
        OutlinedButton(onClick = { vm.startScan() }, Modifier.fillMaxWidth()) { Text("Search again") }
        OutlinedButton(onClick = { vm.navigate(Screen.Manual) }, Modifier.fillMaxWidth()) { Text("Enter IP address manually") }
    }
}

@Composable
private fun PcRow(pc: DiscoveredPc, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pc.name, style = MaterialTheme.typography.titleMedium)
                Text("${pc.host}:${pc.tcpPort}", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (pc.pairingOpen) "Pairing open" else "Available",
                color = if (pc.pairingOpen) Palette.Accent else Palette.TextDim,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
fun ManualScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    var host by rememberSaveable { mutableStateOf(s.lastHost) }
    var port by rememberSaveable { mutableStateOf(s.lastTcpPort.toString()) }
    val portNum = port.toIntOrNull()
    val valid = host.isNotBlank() && portNum != null && portNum in 1..65535
    Page("Manual connection", onBack = { vm.back() }) {
        Text("Enter the PC's IP address as shown in the Windows companion.", color = Palette.TextDim)
        OutlinedTextField(
            host, { host = it.trim() }, label = { Text("PC IP address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Control port (TCP)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.connect(PcTarget(host, host, portNum!!)) },
            enabled = valid, modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }
    }
}

@Composable
fun ConnectScreen(vm: AppViewModel) {
    val conn by vm.connection.state.collectAsState()
    var pin by rememberSaveable { mutableStateOf("") }
    Page("Connect", onBack = { vm.back() }) {
        when (val c = conn) {
            is ConnState.Idle, is ConnState.Connecting -> Busy("Connecting…")
            is ConnState.NeedsPairing -> {
                Text("Pair with ${c.target.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Enter the 6-digit PIN shown in the Windows companion. You will then be asked to allow this phone on the PC. " +
                        "This only has to be done once.",
                    color = Palette.TextDim,
                )
                if (!c.pairingOpen) {
                    InfoCard("Pairing may be closed", "Open the pairing window in the Windows companion to get a PIN (it expires after 2 minutes).")
                }
                c.error?.let { Text(it, color = Palette.Danger) }
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.connection.submitPin(pin); pin = "" },
                    enabled = pin.length == 6, modifier = Modifier.fillMaxWidth(),
                ) { Text("Pair") }
            }
            is ConnState.Pairing -> Busy("Waiting for you to allow this phone on the PC…")
            is ConnState.Connected -> Busy("Connected. Opening gamepad…")
            is ConnState.Reconnecting -> {
                Busy("Can't reach ${c.target.name}. Retrying (attempt ${c.attempt})…")
                Text(c.reason, color = Palette.Warn)
            }
            is ConnState.Failed -> {
                Text("Connection failed", style = MaterialTheme.typography.titleMedium, color = Palette.Danger)
                Text(c.message, color = Palette.TextDim)
                Button(onClick = { vm.connect(c.target) }, Modifier.fillMaxWidth()) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

@Composable
internal fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String,
    onFinished: (() -> Unit)? = null, onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text(fmt(value), color = Palette.TextDim)
        }
        Slider(value, onChange, valueRange = range, onValueChangeFinished = onFinished)
    }
}

@Composable
internal fun SwitchRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    fun upd(n: Settings) = vm.updateSettings(n)
    Page("Settings", onBack = { vm.back() }) {
        SwitchRow("Haptic feedback", "Short vibration when a button is pressed", s.haptics) { upd(s.copy(haptics = it)) }
        SwitchRow("Keep screen on while playing", null, s.keepScreenOn) { upd(s.copy(keepScreenOn = it)) }
        SwitchRow("Show status overlay", "Connection info at the top of the gamepad", s.showOverlay) { upd(s.copy(showOverlay = it)) }
        InfoCard(
            "Stick and layout tuning",
            "Dead zone, sensitivity, response curve, trigger mode, opacity, left-handed layout and control positions are saved per profile.",
        )
        OutlinedButton(onClick = { vm.navigate(Screen.Profiles) }, Modifier.fillMaxWidth()) { Text("Layouts & profiles") }
        Spacer(Modifier.height(4.dp))
        InfoCard("Not available yet", "Vibration from the PC and gyro steering are not implemented.")
    }
}

@Composable
fun AboutScreen(vm: AppViewModel) {
    Page("About", onBack = { vm.back() }) {
        InfoCard("Phone2Gamepad", "App version ${BuildConfig.VERSION_NAME}\nProtocol version ${Wire.VERSION}")
        InfoCard(
            "How it works",
            "The phone sends controller state to the Windows companion over your local network (UDP). " +
                "Pairing and session setup use TCP and are authenticated with a PIN-derived key; input packets carry a per-session HMAC. " +
                "Nothing leaves your local network and no account is needed.",
        )
        InfoCard(
            "Troubleshooting",
            "• PC not listed: check Wi-Fi, firewall (UDP ${Wire.DISCOVERY_PORT}/${Wire.DEFAULT_UDP_PORT}, TCP ${Wire.DEFAULT_TCP_PORT}) and use manual IP.\n" +
                "• \"Wrong PIN\": the PIN expires after 2 minutes; request a new one on the PC.\n" +
                "• Controller not detected in Windows: check the driver status in the Windows companion.\n" +
                "• Permissions: this app only uses network access and vibration.",
        )
    }
}
