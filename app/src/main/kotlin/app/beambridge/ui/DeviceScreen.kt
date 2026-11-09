package app.beambridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.beambridge.MainViewModel
import app.beambridge.core.data.AcRemote
import app.beambridge.core.data.AcState
import app.beambridge.core.data.DeviceType
import app.beambridge.core.data.SavedButton
import app.beambridge.core.data.SavedDevice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceScreen(vm: MainViewModel, device: SavedDevice, snackbar: SnackbarHostState) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }

    val names = vm.buttonNames(device)
    val stored = device.buttons.associateBy { it.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name) },
                navigationIcon = { IconButton(onClick = vm::home) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; deleting = true })
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editor = EditorTarget(null) }) { Icon(Icons.Rounded.Add, "Add button") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (device.type == DeviceType.AC) {
                item { AcPanel(vm, device) }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.forEach { name ->
                        CommandTile(
                            name = name,
                            emphasized = name.startsWith("POWER", ignoreCase = true),
                            onPress = { vm.press(device.id, name) },
                            onLongPress = { sheetFor = name },
                            onCopy = { vm.copyUrl(device.id, name) },
                        )
                    }
                }
            }
        }
    }

    sheetFor?.let { name ->
        val custom = stored[name]
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(prettyName(name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(
                    vm.urlFor(device.id, name), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                ListItem(
                    headlineContent = { Text("Copy URL Endpoint") },
                    leadingContent = { Icon(Icons.Rounded.ContentCopy, null) },
                    modifier = Modifier.clickableItem { vm.copyUrl(device.id, name); sheetFor = null },
                )
                ListItem(
                    headlineContent = { Text("Send now") },
                    leadingContent = { Icon(Icons.Rounded.Send, null) },
                    modifier = Modifier.clickableItem { vm.press(device.id, name); sheetFor = null },
                )
                if (custom != null) {
                    ListItem(
                        headlineContent = { Text("Edit code") },
                        leadingContent = { Icon(Icons.Rounded.Edit, null) },
                        modifier = Modifier.clickableItem { editor = EditorTarget(custom); sheetFor = null },
                    )
                    ListItem(
                        headlineContent = { Text("Remove") },
                        leadingContent = { Icon(Icons.Rounded.Delete, null) },
                        modifier = Modifier.clickableItem { vm.removeButton(device.id, name); sheetFor = null },
                    )
                }
            }
        }
    }

    editor?.let { target ->
        ButtonEditorSheet(vm, device, target.button) { editor = null }
    }

    if (renaming) {
        var text by remember { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.rename(device.id, text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${device.name}?") },
            confirmButton = { TextButton(onClick = { deleting = false; vm.delete(device.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

private data class EditorTarget(val button: SavedButton?)

@Composable
private fun Modifier.clickableItem(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AcPanel(vm: MainViewModel, device: SavedDevice) {
    val remote: AcRemote? = vm.acRemote(device)
    val state = device.acState ?: AcState()
    ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                FilledIconButton(onClick = { vm.acAction(device.id, "TEMP_DOWN") }) { Icon(Icons.Rounded.Remove, "Cooler") }
                Text(if (state.power) "${state.temperature}°" else "Off", style = MaterialTheme.typography.displayMedium)
                FilledIconButton(onClick = { vm.acAction(device.id, "TEMP_UP") }) { Icon(Icons.Rounded.Add, "Warmer") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (remote?.modes.orEmpty()).forEach { mode ->
                    FilterChip(selected = state.power && state.mode == mode, onClick = { vm.acAction(device.id, "MODE_${mode.uppercase()}") }, label = { Text(prettyName(mode)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (remote?.fanSpeeds.orEmpty()).forEach { fan ->
                    FilterChip(selected = state.fan == fan, onClick = { vm.acAction(device.id, "FAN_${fan.uppercase()}") }, label = { Text("Fan ${fan}") })
                }
            }
        }
    }
}
