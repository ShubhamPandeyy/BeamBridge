package app.beambridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.MainViewModel
import app.beambridge.core.atv.IosGesture
import app.beambridge.core.data.SavedDevice

/**
 * "Set up iPhone remote": while open, gestures on this device's Apple TV (the iPhone's Control Center remote) are
 * caught here instead of sent. The device's real remote is shown below; do a gesture, then tap the key it should
 * press (or make a new custom IR key for it). Keys show a small count of the gestures bound to them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun IosRemoteScreen(vm: MainViewModel, device: SavedDevice) {
    val state by vm.iosState.collectAsStateWithLifecycle()
    val learn by vm.iosLearn.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    val haptics = LocalBeamHaptics.current
    var editing by remember { mutableStateOf<String?>(null) }   // saved gesture whose key is being changed from the list
    var customFor by remember { mutableStateOf<String?>(null) } // gesture waiting for a new custom IR key
    var namesBefore by remember { mutableStateOf<Set<String>>(emptySet()) }
    var customSheet by remember { mutableStateOf(false) }

    DisposableEffect(device.id) {
        vm.iosRemote.startLearning(device.id)
        onDispose { vm.iosRemote.stopLearning() }
    }

    val entry = state.entries.firstOrNull { it.deviceId == device.id }
    val caught = learn?.takeIf { it.deviceId == device.id }?.gesture
    val listening = state.running && entry != null
    val buttons = remember(device, datasets) { vm.buttonNames(device) }
    val layout = remember(device, datasets) { vm.layout(device) }
    val grid = remember(device, datasets) { vm.grid(device) }
    LaunchedEffect(caught) { if (caught != null) haptics.long() }

    // Keys reachable on the grid; everything else the device knows goes in "More keys" chips.
    val onGrid = remember(grid, layout) {
        grid.tiles.flatMap { t ->
            when (t.id) {
                "@DPAD" -> listOfNotNull(layout.up, layout.down, layout.left, layout.right, layout.ok)
                "@VOL" -> listOfNotNull(layout.volDown, layout.volUp)
                "@CH" -> listOfNotNull(layout.chDown, layout.chUp)
                else -> listOf(t.id)
            }
        }.map { it.uppercase() }.toSet()
    }
    val more = buttons.filter { it.uppercase() !in onGrid }
    val badges = remember(device.iosGestures, buttons, onGrid) {
        (buttons + grid.tiles.map { it.id } + listOfNotNull(layout.up, layout.down, layout.left, layout.right, layout.ok, layout.volDown, layout.volUp, layout.chDown, layout.chUp))
            .distinct()
            .associateWith { b -> device.iosGestures.values.count { it.equals(b, true) } }
            .filterValues { it > 0 }
    }

    fun bind(key: String) {
        val g = caught
        if (g == null) { vm.toast(if (listening) "Do a gesture on your iPhone first" else "Turn on the iPhone remote first"); return }
        haptics.key()
        customFor = null
        vm.setIosMapping(device.id, g, key)
        vm.iosRemote.clearLearned()
    }

    // A custom key was just made for a waiting gesture: bind it.
    LaunchedEffect(device.buttons) {
        val g = customFor ?: return@LaunchedEffect
        val added = device.buttons.map { it.name }.firstOrNull { it !in namesBefore } ?: return@LaunchedEffect
        vm.setIosMapping(device.id, g, added)
        vm.iosRemote.clearLearned()
        customFor = null
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("iPhone remote"); Text(device.name, style = MaterialTheme.typography.bodyMedium) } },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
    }) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!listening || entry?.connected != true) item {
                ConnectCard(vm, device, state.running, entry != null, entry?.connected == true, settings.iosPin, device.id in settings.iosHidden)
            }
            stickyHeader {
                ListenBanner(
                    listening = listening, connected = entry?.connected == true, caught = caught,
                    current = caught?.let { device.iosGestures[it] },
                    onCustom = { g -> namesBefore = device.buttons.map { it.name }.toSet(); customFor = g; customSheet = true },
                    onUnbind = { g -> customFor = null; vm.setIosMapping(device.id, g, null); vm.iosRemote.clearLearned() },
                    onSkip = { customFor = null; vm.iosRemote.clearLearned() },
                )
            }
            item {
                GridRemote(
                    grid, layout, swipe = false, editing = false, feedback = null,
                    press = ::bind, long = ::bind, onEditStart = {}, onMove = { _, _, _, _, _ -> }, badges = badges,
                )
            }
            if (more.isNotEmpty()) item {
                MenuGroup(if (grid.tiles.isEmpty()) "Keys" else "More keys") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp)) {
                        FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            more.forEach { b ->
                                val n = badges[b] ?: 0
                                AssistChip(
                                    onClick = { bind(b) },
                                    label = { Text(if (n > 0) "${prettyName(b)} · $n" else prettyName(b)) },
                                    leadingIcon = { Icon(buttonIcon(b) ?: Icons.Rounded.RadioButtonChecked, null, Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                }
            }
            item { Text("Saved gestures (${device.iosGestures.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
            if (device.iosGestures.isEmpty()) item {
                Text("None yet. Gestures you don't set up do nothing.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            } else item {
                val order = IosGesture.LABELS.keys.toList()
                MenuGroup(null) {
                    device.iosGestures.entries.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) 999 else i } }.forEach { (g, b) ->
                        val missing = buttons.none { it.equals(b, true) }
                        MenuRow(
                            gestureIcon(g), IosGesture.label(g),
                            if (missing) "$b · missing, pick another key" else "Presses ${prettyName(b)}",
                            danger = missing,
                            trailing = {
                                Row {
                                    IconButton(onClick = { editing = g }) { Icon(Icons.Rounded.Edit, "Change key") }
                                    IconButton(onClick = { vm.setIosMapping(device.id, g, null) }) { Icon(Icons.Rounded.DeleteOutline, "Remove") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    editing?.let { g ->
        ButtonPickerDialog(IosGesture.label(g), buttons, device.iosGestures[g], onDismiss = { editing = null }) { b -> vm.setIosMapping(device.id, g, b); editing = null }
    }
    // Closing without saving leaves the gesture caught, so the user can tap a key instead.
    if (customSheet) ButtonEditorSheet(vm, device, null) { customSheet = false }
}

/** Pinned under the top bar: "Listening…", or the caught gesture with what to do next. */
@Composable
private fun ListenBanner(
    listening: Boolean, connected: Boolean, caught: String?, current: String?,
    onCustom: (String) -> Unit, onUnbind: (String) -> Unit, onSkip: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (caught != null) cs.primaryContainer else cs.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp), shadowElevation = if (caught != null) 6.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        AnimatedContent(caught, label = "listen") { g ->
            if (g == null) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
                Icon(Icons.Rounded.TouchApp, null, Modifier.size(32.dp).alpha(if (listening) pulse else 0.35f))
                Column {
                    Text(if (listening) "Listening…" else "Not listening", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !listening -> "Turn on the iPhone remote and show this device first."
                            !connected -> "Open the remote on your iPhone and pick this device."
                            else -> "Swipe, tap or press a button on your iPhone. Nothing is sent while this screen is open."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                        Icon(gestureIcon(g), null, tint = cs.onPrimary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(IosGesture.label(g), style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                        Text(
                            (current?.let { "Now presses ${prettyName(it)}. " } ?: "") + "Tap a key below to bind it.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { onCustom(g) }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Custom IR key") }
                    if (current != null) TextButton(onClick = { onUnbind(g) }) { Text("Unbind") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSkip) { Text("Skip") }
                }
            }
        }
    }
}

@Composable
private fun ConnectCard(vm: MainViewModel, device: SavedDevice, running: Boolean, shown: Boolean, connected: Boolean, pin: String, hidden: Boolean) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.PhoneIphone)
                Text(
                    when { !running -> "iPhone remote is off"; !shown -> "Not shown to the iPhone"; connected -> "iPhone connected"; else -> "Ready to pair" },
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                )
            }
            when {
                !running -> {
                    Text("Turn it on to make \"${device.name}\" appear as an Apple TV in your iPhone's Control Center remote.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { vm.setIosRemoteRunning(true) }) { Text("Turn on iPhone remote") }
                }
                device.archived -> Text("Archived devices aren't shown. Restore it first.", style = MaterialTheme.typography.bodyMedium)
                hidden -> {
                    Text("You hid this device from the iPhone in Settings.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { vm.setIosShown(device.id, true) }) { Text("Show it") }
                }
                !shown -> Text("Only the first 12 devices are shown. Hide some in Settings → iPhone remote.", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("On your iPhone (same Wi-Fi):", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "1. Open Control Center → Apple TV Remote\n2. Pick \"${device.name}\" at the top\n3. Enter the PIN below (first time only)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Surface(shape = MaterialTheme.shapes.medium, color = cs.secondaryContainer) {
                        Text("PIN  $pin", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                    }
                    Text("Don't see the remote? iPhone Settings → Control Center → add Apple TV Remote.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ButtonPickerDialog(title: String, buttons: List<String>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(buttons) { b ->
                    TextButton(onClick = { onPick(b) }, modifier = Modifier.fillMaxWidth()) {
                        Text(prettyName(b), Modifier.weight(1f))
                        if (b.equals(current, true)) Icon(Icons.Rounded.Check, "Current")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun gestureIcon(g: String) = when {
    g == IosGesture.SWIPE_UP || g == "ios_up" -> Icons.Rounded.KeyboardArrowUp
    g == IosGesture.SWIPE_DOWN || g == "ios_down" -> Icons.Rounded.KeyboardArrowDown
    g == IosGesture.SWIPE_LEFT || g == "ios_left" -> Icons.AutoMirrored.Rounded.KeyboardArrowLeft
    g == IosGesture.SWIPE_RIGHT || g == "ios_right" -> Icons.AutoMirrored.Rounded.KeyboardArrowRight
    g == IosGesture.TAP || g == "ios_select" -> Icons.Rounded.TouchApp
    g.contains("volume") -> Icons.AutoMirrored.Rounded.VolumeUp
    g == "ios_mute" -> Icons.AutoMirrored.Rounded.VolumeOff
    g == "ios_power" -> Icons.Rounded.PowerSettingsNew
    g == "ios_play_pause" -> Icons.Rounded.PlayArrow
    g == "ios_tv" -> Icons.Rounded.Tv
    g == "ios_back" -> Icons.AutoMirrored.Rounded.Undo
    else -> Icons.Rounded.RadioButtonChecked
}
