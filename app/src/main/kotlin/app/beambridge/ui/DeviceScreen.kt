package app.beambridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel
import app.beambridge.core.data.AcRemote
import app.beambridge.core.data.AcState
import app.beambridge.core.data.DeviceType
import app.beambridge.core.data.RemoteLayout
import app.beambridge.core.data.SavedDevice

private sealed interface Sheet {
    data class Key(val name: String, val inGrid: Boolean) : Sheet
    data class Move(val name: String) : Sheet
    data class Edit(val name: String?) : Sheet
    data object Hidden : Sheet
    data object Rename : Sheet
    data object Delete : Sheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: MainViewModel, device: SavedDevice) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val layout = remember(device) { vm.layout(device) }

    val press: (String) -> Unit = { n -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.press(device.id, n) }
    val long: (String, Boolean) -> Unit = { n, grid -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); sheet = Sheet.Key(n, grid) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = vm::home) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { sheet = Sheet.Edit(null) }) { Icon(Icons.Rounded.Add, "Add button") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Find a code (TV finder)") }, leadingIcon = { Icon(Icons.Rounded.TravelExplore, null) }, onClick = { menu = false; vm.openFinder(device.id) })
                            if (device.hidden.isNotEmpty()) DropdownMenuItem(text = { Text("Hidden keys (${device.hidden.size})") }, leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) }, onClick = { menu = false; sheet = Sheet.Hidden })
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; sheet = Sheet.Rename })
                            DropdownMenuItem(text = { Text(if (device.archived) "Restore" else "Archive") }, leadingIcon = { Icon(Icons.Rounded.Archive, null) }, onClick = {
                                menu = false; vm.setArchived(device.id, !device.archived); if (!device.archived) vm.home()
                            })
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; sheet = Sheet.Delete })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (device.type == DeviceType.AC) AcPanel(vm, device)
            Remote(layout, press, long)
            if (layout.extras.isNotEmpty()) ExtrasGrid(layout.extras, press, long)
            if (device.buttons.isEmpty()) {
                Text("No keys yet. Tap + to add one with an IR code, or use the TV finder.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (val s = sheet) {
        null -> {}
        is Sheet.Key -> KeySheet(vm, device, s, onDismiss = { sheet = null }, onMove = { sheet = Sheet.Move(s.name) }, onEdit = { sheet = Sheet.Edit(s.name) })
        is Sheet.Move -> MoveDialog(layout.extras, s.name, onDismiss = { sheet = null }) { cell -> vm.moveButton(device.id, s.name, cell); sheet = null }
        is Sheet.Edit -> ButtonEditorSheet(vm, device, s.name?.let { device.button(it) }) { sheet = null }
        Sheet.Hidden -> HiddenSheet(vm, device) { sheet = null }
        Sheet.Rename -> RenameDialog(device.name, onDismiss = { sheet = null }) { vm.rename(device.id, it); sheet = null }
        Sheet.Delete -> ConfirmDelete(device.name, onDismiss = { sheet = null }) { sheet = null; vm.delete(device.id) }
    }
}

// ---- remote body --------------------------------------------------------------------------------------

@Composable
private fun Remote(l: RemoteLayout, press: (String) -> Unit, long: (String, Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Row 1: mute ……… power
    if (l.mute != null || l.power != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Slot(l.mute) { RemoteKey(it, { press(it) }, { long(it, false) }) }
        Slot(l.power) { RemoteKey(it, { press(it) }, { long(it, false) }, container = cs.errorContainer, content = cs.onErrorContainer) }
    }
    if (l.hasDpad) Dpad(l, press, long)
    // Home ……… back
    if (l.home != null || l.back != null) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Slot(l.back) { RemoteKey(it, { press(it) }, { long(it, false) }) }
        Slot(l.home) { RemoteKey(it, { press(it) }, { long(it, false) }) }
    }
    if (l.volDown != null || l.volUp != null) Pill(l.volDown, l.volUp, "Volume", press, long)
    if (l.chDown != null || l.chUp != null) Pill(l.chDown, l.chUp, "Channel", press, long)
}

@Composable
private fun Slot(name: String?, size: Dp = 64.dp, content: @Composable (String) -> Unit) {
    if (name != null) content(name) else Spacer(Modifier.size(size))
}

/** Big circular D-pad: arrows on a tonal ring, OK in the middle. */
@Composable
private fun Dpad(l: RemoteLayout, press: (String) -> Unit, long: (String, Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val transparent = androidx.compose.ui.graphics.Color.Transparent
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val d = minOf(maxWidth, 320.dp)
        val key = d / 3.4f
        Box(Modifier.size(d).clip(CircleShape).background(cs.surfaceContainerHigh)) {
            @Composable fun arrow(name: String?, align: Alignment) = name?.let {
                RemoteKey(it, { press(it) }, { long(it, false) }, Modifier.align(align).padding(6.dp), size = key, container = transparent, content = cs.onSurface)
            }
            arrow(l.up, Alignment.TopCenter); arrow(l.down, Alignment.BottomCenter)
            arrow(l.left, Alignment.CenterStart); arrow(l.right, Alignment.CenterEnd)
            l.ok?.let {
                RemoteKey(it, { press(it) }, { long(it, false) }, Modifier.align(Alignment.Center), size = d * 0.36f, container = cs.primary, content = cs.onPrimary, icon = null)
            }
        }
    }
}

/** Horizontal pill: minus on the left, plus on the right. */
@Composable
private fun Pill(minus: String?, plus: String?, label: String, press: (String) -> Unit, long: (String, Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val transparent = androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().height(72.dp).clip(CircleShape).background(cs.secondaryContainer).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slot(minus) { RemoteKey(it, { press(it) }, { long(it, false) }, container = transparent, icon = Icons.Rounded.Remove) }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Slot(plus) { RemoteKey(it, { press(it) }, { long(it, false) }, container = transparent, icon = Icons.Rounded.Add) }
    }
}

@Composable
private fun ExtrasGrid(cells: List<String>, press: (String) -> Unit, long: (String, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        cells.chunked(RemoteLayout.COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0 until RemoteLayout.COLUMNS) {
                    val n = row.getOrNull(i).orEmpty()
                    Box(Modifier.size(64.dp)) { if (n.isNotEmpty()) RemoteKey(n, { press(n) }, { long(n, true) }, round = false) }
                }
            }
        }
    }
}

// ---- sheets & dialogs ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeySheet(vm: MainViewModel, device: SavedDevice, s: Sheet.Key, onDismiss: () -> Unit, onMove: () -> Unit, onEdit: () -> Unit) {
    val btn = device.button(s.name)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(prettyName(s.name), style = MaterialTheme.typography.titleLarge)
            Text(vm.urlFor(device.id, s.name), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        SheetAction(Icons.Rounded.Link, "Copy endpoint URL") { vm.copyUrl(device.id, s.name); onDismiss() }
        SheetAction(Icons.Rounded.VisibilityOff, "Hide from remote") { vm.setHidden(device.id, s.name, true); onDismiss(); vm.toast("Hidden · restore from ⋮ menu") }
        if (s.inGrid) SheetAction(Icons.Rounded.OpenWith, "Move on grid", onClick = onMove)
        if (btn != null) SheetAction(Icons.Rounded.Edit, "Edit IR code", onClick = onEdit)
        if (btn?.custom == true) SheetAction(Icons.Rounded.DeleteOutline, "Remove key", danger = true) { vm.removeButton(device.id, s.name); onDismiss() }
        Spacer(Modifier.height(24.dp))
    }
}

/** Fixed 4-column grid: tap the cell the key should go to (swaps with what's there). */
@Composable
private fun MoveDialog(cells: List<String>, moving: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val total = ((cells.size / RemoteLayout.COLUMNS) + 1) * RemoteLayout.COLUMNS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${prettyName(moving)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                (0 until total).chunked(RemoteLayout.COLUMNS).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { i ->
                            val n = cells.getOrNull(i).orEmpty()
                            val me = n == moving
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                                    .background(if (me) cs.primary else if (n.isEmpty()) cs.surface else cs.secondaryContainer)
                                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
                                    .clickable(enabled = !me) { onPick(i) },
                                contentAlignment = Alignment.Center,
                            ) {
                                val icon = buttonIcon(n)
                                val tint = if (me) cs.onPrimary else cs.onSecondaryContainer
                                when {
                                    n.isEmpty() -> {}
                                    icon != null -> Icon(icon, n, tint = tint)
                                    else -> Text(shortLabel(n), color = tint, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenSheet(vm: MainViewModel, device: SavedDevice, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Hidden keys", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.showAllHidden(device.id); onDismiss() }) { Text("Show all") }
        }
        device.hidden.forEach { n ->
            SheetAction(buttonIcon(n) ?: Icons.Rounded.Visibility, prettyName(n)) { vm.setHidden(device.id, n, false) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AcPanel(vm: MainViewModel, device: SavedDevice) {
    val remote: AcRemote? = vm.acRemote(device)
    val state = device.acState ?: AcState()
    ElevatedCard(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                FilledIconButton(onClick = { vm.press(device.id, "TEMP_DOWN") }, Modifier.size(56.dp)) { Icon(Icons.Rounded.Remove, "Cooler") }
                Text(if (state.power) "${state.temperature}°" else "Off", style = MaterialTheme.typography.displayMedium)
                FilledIconButton(onClick = { vm.press(device.id, "TEMP_UP") }, Modifier.size(56.dp)) { Icon(Icons.Rounded.Add, "Warmer") }
            }
            FilledTonalButton(onClick = { vm.press(device.id, if (state.power) "POWER_OFF" else "POWER_ON") }, Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PowerSettingsNew, null); Spacer(Modifier.width(8.dp)); Text(if (state.power) "Turn off" else "Turn on")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                remote?.modes.orEmpty().forEach { mode ->
                    FilterChip(selected = state.power && state.mode == mode, onClick = { vm.press(device.id, "MODE_${mode.uppercase()}") }, label = { Text(prettyName(mode)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                remote?.fanSpeeds.orEmpty().forEach { fan ->
                    FilterChip(selected = state.fan == fan, onClick = { vm.press(device.id, "FAN_${fan.uppercase()}") }, label = { Text("Fan $fan") })
                }
            }
        }
    }
}
