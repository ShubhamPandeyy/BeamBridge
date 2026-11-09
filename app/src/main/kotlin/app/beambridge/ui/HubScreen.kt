package app.beambridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel
import app.beambridge.core.data.DeviceType
import app.beambridge.core.data.SavedDevice
import app.beambridge.service.ServerStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    vm: MainViewModel,
    devices: List<SavedDevice>,
    server: ServerStatus,
    snackbar: SnackbarHostState,
    onSettings: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("BeamBridge") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::startWizard,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add device") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { ServerCard(vm, server) }
            if (devices.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconBadge(Icons.Rounded.SettingsRemote, size = 96.dp)
                    }
                }
            }
            items(devices, key = { it.id }) { device -> DeviceCard(vm, device) }
        }
    }
}

@Composable
private fun ServerCard(vm: MainViewModel, server: ServerStatus) {
    ElevatedCard(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(if (server.running) Icons.Rounded.Sensors else Icons.Rounded.SensorsOff, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    server.url ?: if (server.running) "No Wi-Fi" else "Server off",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(if (server.running) "Online" else "Stopped", good = server.running)
                    if (!vm.emitterAvailable) StatusPill("No IR emitter", good = false)
                    server.error?.let { StatusPill(it, good = false) }
                }
            }
            Switch(checked = server.running, onCheckedChange = vm::setServerRunning)
        }
    }
}

@Composable
private fun DeviceCard(vm: MainViewModel, device: SavedDevice) {
    ElevatedCard(onClick = { vm.open(device.id) }, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(deviceIcon(device.type), size = 52.dp)
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleLarge)
                    Text(device.brand ?: device.type.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val ac = device.acState
                StatusPill(
                    if (device.type == DeviceType.AC) (if (ac != null && ac.power) "${ac.temperature}°" else "Off") else "Ready",
                    good = device.type != DeviceType.AC || (ac != null && ac.power),
                )
            }
            QuickButtons(vm, device)
        }
    }
}

private val QUICK = listOf("POWER", "VOL_UP", "VOL_DOWN", "VOL_MUTE", "CH_UP", "CH_DOWN")
private val QUICK_AC = listOf("POWER_ON", "POWER_OFF", "TEMP_DOWN", "TEMP_UP")

@Composable
private fun QuickButtons(vm: MainViewModel, device: SavedDevice) {
    val names = vm.buttonNames(device)
    val wanted = (if (device.type == DeviceType.AC) QUICK_AC else QUICK).filter { w -> names.any { it.equals(w, true) } }.ifEmpty { names.take(4) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        wanted.take(5).forEach { name ->
            FilledTonalIconButton(onClick = { vm.press(device.id, name) }) {
                val icon = buttonIcon(name)
                if (icon != null) Icon(icon, name) else Text(name.take(3), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
