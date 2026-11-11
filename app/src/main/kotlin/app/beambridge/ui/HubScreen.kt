package app.beambridge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val scenes by vm.scenes.collectAsStateWithLifecycle()
    val runningScene by vm.runningScene.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.nestedScrollFix(scroll),
        topBar = {
            LargeTopAppBar(
                title = { Text("BeamBridge") },
                actions = {
                    IconButton(onClick = { vm.route.value = app.beambridge.Route.Scenes }) { Icon(Icons.Rounded.AutoAwesome, "Scenes") }
                    IconButton(onClick = { vm.route.value = app.beambridge.Route.History }) { Icon(Icons.Rounded.History, "Command history") }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
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
            item { ConnectionsCard(vm, server, devices) }
            if (scenes.isNotEmpty()) item(key = "scenes") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    scenes.forEach { sc ->
                        AssistChip(
                            onClick = { vm.runScene(sc) }, enabled = runningScene == null,
                            label = { Text(sc.name) },
                            leadingIcon = { Icon(if (runningScene == sc.name) Icons.Rounded.HourglassTop else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
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
            SheetAction(Icons.Rounded.OpenInNew, "Open") { menuFor = null; vm.open(d.id) }
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
private fun ConnectionsCard(vm: MainViewModel, server: ServerStatus, devices: List<SavedDevice>) {
    val cs = MaterialTheme.colorScheme
    var showError by rememberSaveable { mutableStateOf(false) }
    var locationGranted by remember { mutableStateOf(vm.hasLocationPermission()) }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { locationGranted = it }
    // Follows network changes live (switching Wi-Fi doesn't always restart the server).
    var networkTick by remember { mutableIntStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { networkTick++ }
            override fun onLost(network: android.net.Network) { networkTick++ }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { networkTick++ }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    val network = remember(server, locationGranted, networkTick) { vm.networkName() }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val active = devices.count { !it.archived }
    val container by androidx.compose.animation.animateColorAsState(if (server.running && server.error == null) cs.primaryContainer else if (server.error != null) cs.errorContainer else cs.surfaceContainerHighest, label = "card")
    val content = if (server.running && server.error == null) cs.onPrimaryContainer else if (server.error != null) cs.onErrorContainer else cs.onSurface
    ElevatedCard(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.elevatedCardColors(containerColor = container, contentColor = content)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(if (server.running) Icons.Rounded.Sensors else Icons.Rounded.SensorsOff, size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        when { server.error != null -> "Needs attention"; server.running -> "Bridge online"; else -> "Bridge off" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        listOfNotNull(network ?: if (server.running) "No Wi-Fi" else null, if (settings.authMode.name == "PASSWORD") "Password on" else "Guest access").joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = server.running, onCheckedChange = vm::setServerRunning)
            }
            server.url?.let { url ->
                Surface(shape = CircleShape, color = cs.surface.copy(alpha = 0.6f)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(url, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                        IconButton(onClick = { vm.copyText("Bridge URL", url) }) { Icon(Icons.Rounded.ContentCopy, "Copy address", tint = cs.onSurface) }
                    }
                }
            }
            if (!locationGranted && server.running) TextButton(onClick = { askLocation.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Rounded.Wifi, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Show my Wi-Fi name (needs location permission)")
            }
            FlowRowCompat {
                StatusPill("$active device${if (active == 1) "" else "s"}", good = true)
                StatusPill(if (vm.emitterAvailable) "IR emitter ready" else "No IR emitter", good = vm.emitterAvailable)
            }
            server.error?.let { err ->
                Surface(onClick = { showError = !showError }, shape = MaterialTheme.shapes.medium, color = cs.surface.copy(alpha = 0.5f)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = cs.error)
                            Text(err.lineSequence().first(), Modifier.weight(1f).padding(horizontal = 8.dp), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(if (showError) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (showError) "Hide details" else "Show details", tint = cs.onSurface)
                        }
                        androidx.compose.animation.AnimatedVisibility(showError) {
                            Text(
                                "$err\n\nCheck Settings → Web server: pick another port, or Automatic address and make sure Wi-Fi is on.",
                                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurface,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = content.copy(alpha = 0.15f))
            IosRemoteRow(vm, content)
        }
    }
}

/** The iPhone remote's on/off inside the connections card (every device shows up as an Apple TV in Control Center). */
@Composable
private fun IosRemoteRow(vm: MainViewModel, content: androidx.compose.ui.graphics.Color) {
    val cs = MaterialTheme.colorScheme
    val state by vm.iosState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val connected = state.entries.count { it.connected }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(Icons.Rounded.PhoneIphone, size = 56.dp)
            Column(Modifier.weight(1f)) {
                Text(if (state.running) "iPhone remote on" else "iPhone remote off", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (!state.running) "Use the Apple TV Remote in Control Center"
                    else listOfNotNull(
                        "${state.entries.size} shown",
                        if (connected > 0) "$connected connected" else null,
                        "PIN ${settings.iosPin}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(checked = state.running, onCheckedChange = vm::setIosRemoteRunning)
        }
        if (state.running) (state.error ?: state.lastEvent)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.error != null) cs.error else content.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    val haptics = LocalBeamHaptics.current
    val power = vm.buttonNames(device).firstOrNull { it.uppercase() in setOf("POWER", "POWER_TOGGLE", "POWER_ON") }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (dimmed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).combinedClickable(
            onClick = { vm.open(device.id) },
            onLongClick = { haptics.long(); onLong() },
        ),
    ) { Column {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(deviceIcon(device.type))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(device.brand, device.type.label, "${device.buttons.size} keys").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (power != null && !dimmed) FilledTonalIconButton(onClick = { haptics.key(); vm.press(device.id, power) }) { Icon(Icons.Rounded.PowerSettingsNew, "Power") }
        }
        val favs = device.favorites.filter { f -> vm.buttonNames(device).any { it.equals(f, true) } }
        if (favs.isNotEmpty() && !dimmed) Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            favs.forEach { f ->
                FilledTonalButton(onClick = { haptics.key(); vm.press(device.id, f) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    buttonIcon(f)?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                    Text(prettyName(f), maxLines = 1)
                }
            }
        }
    }
}
}
