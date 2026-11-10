package app.beambridge.ui

import androidx.compose.animation.AnimatedContent
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
 * "Set up iOS remote": while open, gestures on this device's Apple TV (in the iPhone's Control Center remote) are
 * caught here instead of sent. Do a gesture, pick the button it should press, save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosRemoteScreen(vm: MainViewModel, device: SavedDevice) {
    val state by vm.iosState.collectAsStateWithLifecycle()
    val learn by vm.iosLearn.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }   // gesture whose button is being picked from the list

    DisposableEffect(device.id) {
        vm.iosRemote.startLearning(device.id)
        onDispose { vm.iosRemote.stopLearning() }
    }

    val entry = state.entries.firstOrNull { it.deviceId == device.id }
    val caught = learn?.takeIf { it.deviceId == device.id }?.gesture
    val buttons = remember(device) { vm.buttonNames(device) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("iOS remote"); Text(device.name, style = MaterialTheme.typography.bodyMedium) } },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
    }) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectCard(vm, device, state.running, entry != null, entry?.connected == true, settings.iosPin, device.id in settings.iosHidden) }
            item {
                ListenCard(
                    listening = state.running && entry != null, caught = caught, current = caught?.let { device.iosGestures[it] }, buttons = buttons,
                    onSave = { g, b -> vm.setIosMapping(device.id, g, b); vm.iosRemote.clearLearned() },
                    onDiscard = { vm.iosRemote.clearLearned() },
                )
            }
            item { Text("Saved gestures (${device.iosGestures.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (device.iosGestures.isEmpty()) item {
                Text("None yet. Gestures you don't set up do nothing.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val order = IosGesture.LABELS.keys.toList()
            items(device.iosGestures.entries.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) 999 else i } }.toList(), key = { it.key }) { (g, b) ->
                val missing = buttons.none { it.equals(b, true) }
                ListItem(
                    headlineContent = { Text(IosGesture.label(g)) },
                    supportingContent = { Text(if (missing) "$b · missing, pick another button" else "→ ${prettyName(b)}", color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingContent = { Icon(gestureIcon(g), null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = g }) { Icon(Icons.Rounded.Edit, "Change button") }
                            IconButton(onClick = { vm.setIosMapping(device.id, g, null) }) { Icon(Icons.Rounded.DeleteOutline, "Remove") }
                        }
                    },
                )
            }
        }
    }

    editing?.let { g ->
        ButtonPickerDialog(IosGesture.label(g), buttons, device.iosGestures[g], onDismiss = { editing = null }) { b -> vm.setIosMapping(device.id, g, b); editing = null }
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
private fun ListenCard(listening: Boolean, caught: String?, current: String?, buttons: List<String>, onSave: (String, String) -> Unit, onDiscard: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var pick by remember(caught) { mutableStateOf(current) }
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.elevatedCardColors(containerColor = if (caught != null) cs.primaryContainer else cs.surfaceContainerHigh)) {
        AnimatedContent(caught, label = "listen") { g ->
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (g == null) {
                    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
                    Icon(Icons.Rounded.TouchApp, null, Modifier.size(48.dp).alpha(if (listening) pulse else 0.35f))
                    Text(if (listening) "Listening…" else "Not listening", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (listening) "Swipe, tap or press a button on your iPhone's remote. Nothing is sent while this screen is open."
                        else "Turn on the iPhone remote and show this device first.",
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    )
                } else {
                    Icon(gestureIcon(g), null, Modifier.size(48.dp))
                    Text("Got: ${IosGesture.label(g)}", style = MaterialTheme.typography.titleLarge)
                    current?.let { Text("Currently presses ${prettyName(it)}", style = MaterialTheme.typography.bodySmall) }
                    Box {
                        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(pick?.let { "Press: ${prettyName(it)}" } ?: "Choose a button…"); Spacer(Modifier.weight(1f)); Icon(Icons.Rounded.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                            if (buttons.isEmpty()) DropdownMenuItem(text = { Text("This device has no buttons yet") }, onClick = { menu = false }, enabled = false)
                            buttons.forEach { b -> DropdownMenuItem(text = { Text(prettyName(b)) }, onClick = { pick = b; menu = false }) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onDiscard) { Text("Try another") }
                        Button(onClick = { pick?.let { onSave(g, it) } }, enabled = pick != null) { Text("Save") }
                    }
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
