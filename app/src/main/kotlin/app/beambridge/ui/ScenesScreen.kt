package app.beambridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.MainViewModel
import app.beambridge.Route
import app.beambridge.core.data.SavedDevice
import app.beambridge.core.data.Scene
import app.beambridge.core.data.SceneStep

/** List of scenes (macros): run, copy URL, edit, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenesScreen(vm: MainViewModel) {
    val scenes by vm.scenes.collectAsStateWithLifecycle()
    val running by vm.runningScene.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<Scene?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scenes") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.route.value = Route.SceneEdit(null) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New scene") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (running != null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (scenes.isEmpty()) item {
                Text(
                    "A scene sends several commands in one go, e.g. TV power → wait 3 s → HDMI 1 → AC on. Run it from here, the home screen, or its URL.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(scenes, key = { it.id }) { sc ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, onClick = { vm.route.value = Route.SceneEdit(sc.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(Icons.Rounded.AutoAwesome, size = 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(sc.name, style = MaterialTheme.typography.titleMedium)
                            Text("${sc.steps.size} steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.copyText("Scene URL", vm.sceneUrl(sc)) }) { Icon(Icons.Rounded.Link, "Copy URL") }
                        FilledIconButton(onClick = { vm.runScene(sc) }, enabled = running == null) { Icon(Icons.Rounded.PlayArrow, "Run") }
                    }
                }
            }
        }
    }
}

/** Editor: name + ordered steps. Each step is "press KEY on DEVICE" or "wait N ms". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneEditorScreen(vm: MainViewModel, scene: Scene?, devices: List<SavedDevice>) {
    var name by remember { mutableStateOf(scene?.name.orEmpty()) }
    val steps = remember { mutableStateListOf<SceneStep>().apply { addAll(scene?.steps.orEmpty()) } }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Int?>(null) }   // index being edited, -1 = new step
    var confirmDelete by remember { mutableStateOf(false) }
    val active = devices.filterNot { it.archived }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (scene == null) "New scene" else "Edit scene") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (scene != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete scene") }
                    TextButton(onClick = { error = vm.saveScene(scene?.id, name, steps.toList()) }) { Text("Save") }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(name, { name = it }, label = { Text("Scene name") }, placeholder = { Text("Movie night") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            itemsIndexed(steps) { i, st ->
                val d = active.firstOrNull { it.id.equals(st.device, true) }
                ListItem(
                    leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.titleMedium) },
                    headlineContent = {
                        Text(if (st.isWait) "Wait ${st.delayMs} ms" else "${prettyName(st.button)} on ${d?.name ?: st.device}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { if (i > 0) { steps.add(i - 1, steps.removeAt(i)) } }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                            IconButton(onClick = { if (i < steps.lastIndex) { steps.add(i + 1, steps.removeAt(i)) } }, enabled = i < steps.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                            IconButton(onClick = { steps.removeAt(i) }) { Icon(Icons.Rounded.Close, "Remove step") }
                        }
                    },
                    modifier = Modifier.clickableRow { picking = i },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { picking = -1 }, enabled = active.isNotEmpty()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Key press") }
                    OutlinedButton(onClick = { steps.add(SceneStep(delayMs = 1000)) }) { Icon(Icons.Rounded.Timer, null); Spacer(Modifier.width(6.dp)); Text("Wait") }
                }
            }
        }
    }

    picking?.let { idx ->
        val current = steps.getOrNull(idx)
        if (current?.isWait == true) WaitDialog(current.delayMs, onDismiss = { picking = null }) { steps[idx] = SceneStep(delayMs = it); picking = null }
        else StepPicker(vm, active, current, onDismiss = { picking = null }) { st -> if (idx < 0) steps.add(st) else steps[idx] = st; picking = null }
    }
    if (confirmDelete && scene != null) ConfirmDelete(scene.name, onDismiss = { confirmDelete = false }) { vm.deleteScene(scene.id) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StepPicker(vm: MainViewModel, devices: List<SavedDevice>, current: SceneStep?, onDismiss: () -> Unit, onPick: (SceneStep) -> Unit) {
    var device by remember { mutableStateOf(devices.firstOrNull { it.id.equals(current?.device, true) } ?: devices.firstOrNull()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Device", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { d -> FilterChip(selected = device?.id == d.id, onClick = { device = d }, label = { Text(d.name) }, leadingIcon = { Icon(deviceIcon(d.type), null, Modifier.size(18.dp)) }) }
            }
            device?.let { d ->
                Text("Key", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    Modifier.heightIn(max = 360.dp).verticalScrollCompat(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vm.buttonNames(d).forEach { b ->
                        val ic = buttonIcon(b)
                        if (ic != null) AssistChip(onClick = { onPick(SceneStep(d.id, b)) }, label = { Text(prettyName(b)) }, leadingIcon = { Icon(ic, null, Modifier.size(18.dp)) })
                        else AssistChip(onClick = { onPick(SceneStep(d.id, b)) }, label = { Text(prettyName(b)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitDialog(ms: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var text by remember { mutableStateOf(ms.toString()) }
    val v = text.toLongOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wait") },
        text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(5) }, label = { Text("Milliseconds") }, singleLine = true,
                isError = v == null || v !in 1..60_000, supportingText = { Text("1 to 60000 (TVs often need ~3000 after power on)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { v?.let(onSave) }, enabled = v != null && v in 1..60_000) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable private fun Modifier.verticalScrollCompat() = this.verticalScroll(rememberScrollState())
private fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)
