package app.beambridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: MainViewModel, serverRunning: Boolean, onDismiss: () -> Unit) {
    val port by vm.settings.port.collectAsState()
    val wake by vm.settings.wakeLock.collectAsState()
    val auto by vm.settings.autoStart.collectAsState()
    var portText by remember { mutableStateOf(port.toString()) }
    var wakeLock by remember { mutableStateOf(wake) }
    var autoStart by remember { mutableStateOf(auto) }
    val portValue = portText.toIntOrNull()?.takeIf { it in 1024..65535 }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                portText, { portText = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                isError = portValue == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow("Keep CPU awake", wakeLock) { wakeLock = it }
            ToggleRow("Start on launch", autoStart) { autoStart = it }
            Button(
                enabled = portValue != null,
                onClick = { vm.applySettings(portValue!!, wakeLock, autoStart, serverRunning); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) { Icon(Icons.Rounded.Check, null); Text("Save", Modifier.padding(start = 8.dp)) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked, onChange)
    }
}
