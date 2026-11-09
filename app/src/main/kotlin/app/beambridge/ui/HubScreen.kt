package app.beambridge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel
import app.beambridge.core.data.SavedDevice
import app.beambridge.service.ServerStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(vm: MainViewModel, devices: List<SavedDevice>, server: ServerStatus, onSettings: () -> Unit) {
    var menuFor by remember { mutableStateOf<SavedDevice?>(null) }
    var confirmDelete by remember { mutableStateOf<SavedDevice?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val active = devices.filterNot { it.archived }
    val archived = devices.filter { it.archived }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScrollFix(scroll),
        topBar = {
            LargeTopAppBar(
                title = { Text("BeamBridge") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = vm::startWizard, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add device") })
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ServerCard(vm, server) }
            if (active.isEmpty()) item { EmptyState() }
            items(active, key = { it.id }) { d -> DeviceCard(vm, d, onLong = { menuFor = d }, modifier = Modifier.animateItem()) }
            if (archived.isNotEmpty()) {
                item(key = "archived-header") {
                    TextButton(onClick = { showArchived = !showArchived }, modifier = Modifier.animateItem()) {
                        Icon(if (showArchived) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Archived (${archived.size})")
                    }
                }
                if (showArchived) items(archived, key = { it.id }) { d ->
                    DeviceCard(vm, d, onLong = { menuFor = d }, dimmed = true, modifier = Modifier.animateItem())
                }
            }
        }
    }

    menuFor?.let { d ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Text(d.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SheetAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open") { menuFor = null; vm.open(d.id) }
            if (d.archived) SheetAction(Icons.Rounded.Unarchive, "Restore from archive") { vm.setArchived(d.id, false); menuFor = null }
            else SheetAction(Icons.Rounded.Archive, "Archive") { vm.setArchived(d.id, true); menuFor = null; vm.toast("${d.name} archived") }
            SheetAction(Icons.Rounded.DeleteOutline, "Delete", danger = true) { confirmDelete = d; menuFor = null }
            Spacer(Modifier.height(24.dp))
        }
    }
    confirmDelete?.let { d -> ConfirmDelete(d.name, onDismiss = { confirmDelete = null }) { vm.delete(d.id); confirmDelete = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.nestedScrollFix(b: TopAppBarScrollBehavior) = this.nestedScroll(b.nestedScrollConnection)

@Composable
fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickableRow(onClick),
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

@Composable
fun ConfirmDelete(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteOutline, null) },
        title = { Text("Delete $name?") },
        text = { Text("Its buttons and endpoint URLs stop working. Archive instead if you may want it back.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(Icons.Rounded.SettingsRemote, size = 72.dp)
        Text("No devices yet", style = MaterialTheme.typography.titleMedium)
        Text("Tap Add device to pair a TV, AC or set-top box.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                FlowRowCompat {
                    StatusPill(if (server.running) "Online" else "Stopped", good = server.running)
                    if (!vm.emitterAvailable) StatusPill("No IR emitter", good = false)
                    server.error?.let { StatusPill(it, good = false) }
                }
            }
            Switch(checked = server.running, onCheckedChange = vm::setServerRunning)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceCard(vm: MainViewModel, device: SavedDevice, onLong: () -> Unit, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val haptics = LocalHapticFeedback.current
    val power = vm.buttonNames(device).firstOrNull { it.uppercase() in setOf("POWER", "POWER_TOGGLE", "POWER_ON") }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (dimmed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).combinedClickable(
            onClick = { vm.open(device.id) },
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLong() },
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(deviceIcon(device.type))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(device.brand, device.type.label, "${device.buttons.size} keys").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (power != null && !dimmed) FilledTonalIconButton(onClick = { vm.press(device.id, power) }) { Icon(Icons.Rounded.PowerSettingsNew, "Power") }
        }
    }
}
