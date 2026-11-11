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
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    val cs = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.nestedScrollFix(scroll),
        topBar = {
            LargeTopAppBar(
                title = { Text("BeamBridge", style = if (scroll.state.collapsedFraction < 0.5f) BeamType.hero else BeamType.screenTitle) },
                actions = {
                    IconButton(onClick = { vm.route.value = app.beambridge.Route.History }) { Icon(Icons.Rounded.History, "Command history") }
                    FilledTonalIconButton(onClick = onSettings, modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.Rounded.Settings, "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::startWizard, icon = { Icon(Icons.Rounded.Add, null, Modifier.size(28.dp)) },
                text = { Text("Add", style = MaterialTheme.typography.titleMedium) },
                shape = RoundedCornerShape(28.dp), modifier = Modifier.height(80.dp),
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "connections", span = { GridItemSpan(maxLineSpan) }) { ConnectionsCard(vm, server, devices) }
            item(key = "scenes", span = { GridItemSpan(maxLineSpan) }) {
                SceneRow(scenes.map { it.name }, runningScene, onRun = { n -> scenes.firstOrNull { it.name == n }?.let(vm::runScene) }) {
                    vm.route.value = app.beambridge.Route.Scenes
                }
            }
            item(key = "devices-header", span = { GridItemSpan(maxLineSpan) }) {
                Text("Devices", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            }
            if (active.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
            items(active, key = { it.id }) { d -> DeviceTile(vm, d, onLong = { menuFor = d }, modifier = Modifier.animateItem()) }
            if (archived.isNotEmpty()) {
                item(key = "archived-header", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { showArchived = !showArchived }, modifier = Modifier.animateItem()) {
                        Icon(if (showArchived) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Archived (${archived.size})")
                    }
                }
                if (showArchived) items(archived, key = { it.id }) { d ->
                    DeviceTile(vm, d, onLong = { menuFor = d }, dimmed = true, modifier = Modifier.animateItem())
                }
            }
        }
    }

    menuFor?.let { d ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DeviceBadge(d.type)
                    Text(d.name, style = BeamType.screenTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MenuGroup(null) {
                    MenuRow(Icons.Rounded.OpenInNew, "Open", chevron = true) { menuFor = null; vm.open(d.id) }
                    if (d.archived) MenuRow(Icons.Rounded.Unarchive, "Restore from archive") { vm.setArchived(d.id, false); menuFor = null }
                    else MenuRow(Icons.Rounded.Archive, "Archive", "Hide from home, keep its keys") { vm.setArchived(d.id, true); menuFor = null; vm.toast("${d.name} archived") }
                    MenuRow(Icons.Rounded.DeleteOutline, "Delete", danger = true) { confirmDelete = d; menuFor = null }
                }
            }
        }
    }
    confirmDelete?.let { d -> ConfirmDelete(d.name, onDismiss = { confirmDelete = null }) { vm.delete(d.id); confirmDelete = null } }
}

/** Scenes as one connected button row (outer corners round, inner corners tight), plus a + to manage them. */
@Composable
private fun SceneRow(names: List<String>, running: String?, onRun: (String) -> Unit, onManage: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val all = names.size + 1
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        names.forEachIndexed { i, n ->
            FilledTonalButton(
                onClick = { onRun(n) }, enabled = running == null || running == n,
                shape = connectedShape(i, all), contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(48.dp),
            ) {
                if (running == n) LoadingDot() else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(n, maxLines = 1)
            }
        }
        FilledTonalButton(
            onClick = onManage, shape = connectedShape(all - 1, all), contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(48.dp),
        ) {
            Icon(if (names.isEmpty()) Icons.Rounded.AutoAwesome else Icons.Rounded.Add, null, Modifier.size(20.dp))
            if (names.isEmpty()) { Spacer(Modifier.width(6.dp)); Text("Scenes") }
        }
    }
}

/** Corners for item [i] of [n] in a connected button group. */
fun connectedShape(i: Int, n: Int): RoundedCornerShape = when {
    n == 1 -> RoundedCornerShape(50)
    i == 0 -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50, topEndPercent = 15, bottomEndPercent = 15)
    i == n - 1 -> RoundedCornerShape(topStartPercent = 15, bottomStartPercent = 15, topEndPercent = 50, bottomEndPercent = 50)
    else -> RoundedCornerShape(15)
}

@Composable
private fun LoadingDot() = ShapeSpinner(Modifier.size(18.dp), LocalContentColor.current)

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.nestedScrollFix(b: TopAppBarScrollBehavior) = this.nestedScroll(b.nestedScrollConnection)

/** One action row in a bottom sheet: icon in a tonal circle, rounded row, M3 Expressive style. */
@Composable
fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val color = if (danger) cs.error else cs.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (danger) cs.errorContainer else cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = if (danger) cs.error else cs.onSecondaryContainer)
            }
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).clickableRow(onClick),
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
        IconBadge(Icons.Rounded.SettingsRemote, size = 96.dp, shape = BeamShapes.scene, container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
        Text("No devices yet", style = BeamType.cardTitle)
        Text("Tap Add to pair a TV, AC or set-top box.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Card(shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(if (server.running) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, size = 52.dp, shape = BeamShapes.bridge, container = if (server.running) cs.primary else cs.surfaceContainerHighest, content = if (server.running) cs.onPrimary else cs.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(
                        when { server.error != null -> "Needs attention"; server.running -> "Bridge online"; else -> "Bridge off" },
                        style = BeamType.cardTitle,
                    )
                    Text(
                        listOfNotNull(network ?: if (server.running) "No Wi-Fi" else null, if (settings.authMode.name == "PASSWORD") "Password on" else "Guest access").joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = server.running, onCheckedChange = vm::setServerRunning)
            }
            server.url?.let { url ->
                Surface(shape = RoundedCornerShape(20.dp), color = cs.surface.copy(alpha = 0.6f)) {
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
            IconBadge(Icons.Rounded.PhoneIphone, size = 52.dp, shape = BeamShapes.iphone, container = if (state.running) cs.primary else cs.surfaceContainerHighest, content = if (state.running) cs.onPrimary else cs.onSurfaceVariant)
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DeviceTile(vm: MainViewModel, device: SavedDevice, onLong: () -> Unit, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val haptics = LocalBeamHaptics.current
    val cs = MaterialTheme.colorScheme
    val names = vm.buttonNames(device)
    val power = names.firstOrNull { it.uppercase() in setOf("POWER", "POWER_TOGGLE", "POWER_ON") }
    val favs = device.favorites.filter { f -> names.any { it.equals(f, true) } }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (dimmed) cs.surfaceContainerLow else cs.surfaceContainer,
        modifier = modifier.fillMaxWidth().heightIn(min = 168.dp).clip(RoundedCornerShape(28.dp)).combinedClickable(
            onClick = { vm.open(device.id) },
            onLongClick = { haptics.long(); onLong() },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                DeviceBadge(device.type)
                Spacer(Modifier.weight(1f))
                if (power != null && !dimmed) Box(
                    Modifier.size(44.dp).clip(BeamShapes.power).background(cs.tertiaryContainer)
                        .clickable(onClickLabel = "Power ${device.name}") { haptics.key(); vm.press(device.id, power) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PowerSettingsNew, "Power", Modifier.size(20.dp), tint = cs.onTertiaryContainer) }
            }
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name, style = BeamType.cardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(device.brand ?: device.type.label, "${device.buttons.size} keys").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (favs.isNotEmpty() && !dimmed) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                favs.take(4).forEach { f ->
                    Surface(
                        onClick = { haptics.key(); vm.press(device.id, f) }, shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerHighest,
                    ) {
                        Text(prettyName(f), Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}
