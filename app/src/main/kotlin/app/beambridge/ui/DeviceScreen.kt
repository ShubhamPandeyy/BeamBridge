package app.beambridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.MainViewModel
import app.beambridge.SendFeedback
import app.beambridge.core.data.DeviceType
import app.beambridge.core.data.RemoteGrid
import app.beambridge.core.data.RemoteLayout
import app.beambridge.core.data.SavedDevice
import app.beambridge.core.data.Tile
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface Sheet {
    data class Key(val name: String) : Sheet
    data class Edit(val name: String?) : Sheet
    data object Hidden : Sheet
    data object AddKeys : Sheet
    data object Rename : Sheet
    data object Delete : Sheet
    data object GridSize : Sheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: MainViewModel, device: SavedDevice) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val layout = remember(device) { vm.layout(device) }
    val grid = remember(device) { vm.grid(device) }
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val prefs by vm.settings.collectAsStateWithLifecycle()

    val press: (String) -> Unit = { n -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.press(device.id, n) }
    val long: (String) -> Unit = { n -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); sheet = Sheet.Key(n) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (editing) "Edit layout" else device.name, maxLines = 1)
                        SendStatus(feedback)
                    }
                },
                navigationIcon = {
                    if (editing) IconButton(onClick = { editing = false }) { Icon(Icons.Rounded.Close, "Stop editing") }
                    else IconButton(onClick = vm::home) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    if (editing) {
                        TextButton(onClick = { sheet = Sheet.GridSize }) { Text("${grid.cols}×${grid.rows}") }
                        TextButton(onClick = { vm.resetGrid(device.id) }) { Text("Reset") }
                        Button(onClick = { editing = false }, Modifier.padding(end = 8.dp)) { Text("Done") }
                        return@TopAppBar
                    }
                    IconButton(onClick = { sheet = Sheet.Edit(null) }) { Icon(Icons.Rounded.Add, "Add button") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit layout") }, leadingIcon = { Icon(Icons.Rounded.Dashboard, null) }, onClick = { menu = false; editing = true })
                            if (layout.available.isNotEmpty()) DropdownMenuItem(text = { Text("Add keys to screen (${layout.available.size})") }, leadingIcon = { Icon(Icons.Rounded.AddCircleOutline, null) }, onClick = { menu = false; sheet = Sheet.AddKeys })
                            if (layout.hasDpad) DropdownMenuItem(
                                text = { Text("Swipe D-pad") }, leadingIcon = { Icon(Icons.Rounded.Swipe, null) },
                                trailingIcon = { Switch(device.swipeDpad, null) },
                                onClick = { vm.setSwipe(device.id, !device.swipeDpad) },
                            )
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
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!prefs.tipLongPress && !editing) TipCard { vm.updatePrefs { it.copy(tipLongPress = true) } }
            if (editing) Text(
                "Drag a key to move it. Drag the corner handle to resize. Keys can't overlap.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device.type == DeviceType.AC && !editing) AcPanel(vm, device)
            GridRemote(
                grid, layout, device.swipeDpad, editing, feedback,
                press = press, long = long,
                onEditStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); editing = true },
                onMove = { t, c, r, w, h -> vm.moveTile(device.id, t.id, c, r, w, h) },
            )
            if (device.buttons.isEmpty() && device.type != DeviceType.AC) {
                Text("No keys yet. Tap + to add one with an IR code, or use the TV finder.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    when (val s = sheet) {
        null -> {}
        is Sheet.Key -> KeySheet(vm, device, s.name, onDismiss = { sheet = null }, onLayout = { sheet = null; editing = true }, onEdit = { sheet = Sheet.Edit(s.name) })
        is Sheet.Edit -> ButtonEditorSheet(vm, device, s.name?.let { device.button(it) }) { sheet = null }
        Sheet.Hidden -> HiddenSheet(vm, device) { sheet = null }
        Sheet.AddKeys -> AddKeysSheet(vm, device, layout) { sheet = null }
        Sheet.Rename -> RenameDialog(device.name, onDismiss = { sheet = null }) { vm.rename(device.id, it); sheet = null }
        Sheet.Delete -> ConfirmDelete(device.name, onDismiss = { sheet = null }) { sheet = null; vm.delete(device.id) }
        Sheet.GridSize -> GridSizeDialog(grid.cols, device.gridRows, onDismiss = { sheet = null }) { c, r -> vm.setGridSize(device.id, c, r); sheet = null }
    }
}

/** "Sending… / Sent ✓ / Failed" under the title, so every press gets visible confirmation. */
@Composable
private fun SendStatus(f: SendFeedback?) {
    AnimatedVisibility(f != null, enter = fadeIn(), exit = fadeOut()) {
        val cs = MaterialTheme.colorScheme
        val (text, color) = when (f?.state) {
            SendFeedback.State.SENDING -> "Sending ${prettyName(f.key)}…" to cs.onSurfaceVariant
            SendFeedback.State.SENT -> "✓ ${prettyName(f.key)} sent" to cs.primary
            SendFeedback.State.FAILED -> "✕ ${f.message ?: "Failed"}" to cs.error
            null -> "" to cs.onSurface
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
private fun TipCard(onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.TouchApp, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                "Long-press any key for its URL, favourites and editing. Long-press empty space to rearrange and resize keys.",
                Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss tip", tint = MaterialTheme.colorScheme.onTertiaryContainer) }
        }
    }
}

// ---- free-form grid ------------------------------------------------------------------------------------

/**
 * Remote laid out on a cols×rows grid of square cells. In edit mode tiles wiggle; drag one to move it, drag its
 * corner handle to resize. Positions snap to cells and the core rejects overlaps (the tile springs back).
 */
@Composable
private fun GridRemote(
    grid: RemoteGrid, l: RemoteLayout, swipe: Boolean, editing: Boolean, feedback: SendFeedback?,
    press: (String) -> Unit, long: (String) -> Unit, onEditStart: () -> Unit,
    onMove: (Tile, Int, Int, Int, Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = maxWidth / grid.cols
        val density = LocalDensity.current
        val cellPx = with(density) { cell.toPx() }
        Box(
            Modifier.fillMaxWidth().height(cell * grid.rows)
                .pointerInput(editing) { if (!editing) detectTapGestures(onLongPress = { onEditStart() }) },
        ) {
            if (editing) for (r in 0 until grid.rows) for (c in 0 until grid.cols) Box(
                Modifier.offset(cell * c, cell * r).size(cell).padding(6.dp).clip(RoundedCornerShape(16.dp))
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)),
            )
            grid.tiles.forEach { t ->
                key(t) {
                    var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    var grow by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    val wiggle = if (editing) rememberInfiniteTransition(label = "wiggle").animateFloat(-1.2f, 1.2f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "r").value else 0f
                    val w = (t.w + (grow.x / cellPx).roundToInt()).coerceAtLeast(1)
                    val h = (t.h + (grow.y / cellPx).roundToInt()).coerceAtLeast(1)
                    Box(
                        Modifier
                            .offset { IntOffset((t.col * cellPx + drag.x).roundToInt(), (t.row * cellPx + drag.y).roundToInt()) }
                            .size(cell * (if (grow == androidx.compose.ui.geometry.Offset.Zero) t.w else w), cell * (if (grow == androidx.compose.ui.geometry.Offset.Zero) t.h else h))
                            .graphicsLayer { rotationZ = if (drag == androidx.compose.ui.geometry.Offset.Zero) wiggle else 0f; if (drag != androidx.compose.ui.geometry.Offset.Zero) { scaleX = 1.05f; scaleY = 1.05f } }
                            .then(if (editing) Modifier.pointerInput(t) {
                                detectDragGestures(
                                    onDragEnd = {
                                        val c = ((t.col * cellPx + drag.x) / cellPx).roundToInt()
                                        val r = ((t.row * cellPx + drag.y) / cellPx).roundToInt()
                                        drag = androidx.compose.ui.geometry.Offset.Zero
                                        if (c != t.col || r != t.row) onMove(t, c, r, t.w, t.h)
                                    },
                                    onDragCancel = { drag = androidx.compose.ui.geometry.Offset.Zero },
                                ) { ch, d -> ch.consume(); drag += d }
                            } else Modifier)
                            .padding(6.dp),
                    ) {
                        TileContent(t, l, swipe, editing, feedback, press, long)
                        if (editing) Box(
                            Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape).background(cs.primary)
                                .pointerInput(t) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            val nw = (t.w + (grow.x / cellPx).roundToInt()); val nh = (t.h + (grow.y / cellPx).roundToInt())
                                            grow = androidx.compose.ui.geometry.Offset.Zero
                                            if (nw != t.w || nh != t.h) onMove(t, t.col, t.row, nw, nh)
                                        },
                                        onDragCancel = { grow = androidx.compose.ui.geometry.Offset.Zero },
                                    ) { ch, d -> ch.consume(); grow += d }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.OpenInFull, "Resize", Modifier.size(16.dp).rotate(90f), tint = cs.onPrimary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileContent(t: Tile, l: RemoteLayout, swipe: Boolean, editing: Boolean, f: SendFeedback?, press: (String) -> Unit, long: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    // In edit mode keys don't fire; the grid handles drags.
    val p: (String) -> Unit = if (editing) { _ -> } else press
    val lp: (String) -> Unit = if (editing) { _ -> } else long
    when (t.id) {
        "@DPAD" -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val d = minOf(maxWidth, maxHeight)
            if (swipe) SwipePad(l, d, p) else Dpad(l, d, f, p, lp)
        }
        "@VOL" -> Pill(l.volDown, l.volUp, "Volume", f, p, lp)
        "@CH" -> Pill(l.chDown, l.chUp, "Channel", f, p, lp)
        else -> {
            val power = t.id.uppercase().startsWith("POWER")
            TileKey(
                t.id, f, p, lp, round = t.w == t.h,
                container = if (power) cs.errorContainer else cs.secondaryContainer,
                content = if (power) cs.onErrorContainer else cs.onSecondaryContainer,
                icon = if (t.id.uppercase() in setOf("SETTINGS", "MENU", "OPTIONS", "TOOLS", "TCL_SETTINGS")) Icons.Rounded.Settings else buttonIcon(t.id),
                showLabel = t.w > 1,
            )
        }
    }
}

/** Fills its tile. Flashes primary when its command was sent, error when it failed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileKey(
    name: String, f: SendFeedback?, press: (String) -> Unit, long: (String) -> Unit, round: Boolean,
    container: Color, content: Color, icon: androidx.compose.ui.graphics.vector.ImageVector?, showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 14.dp else if (round) 100.dp else 24.dp, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "corner")
    val mine = f?.key.equals(name, true)
    val ring by animateColorAsState(
        when { !mine -> Color.Transparent; f?.state == SendFeedback.State.FAILED -> cs.error; f?.state == SendFeedback.State.SENT -> cs.primary; else -> cs.outline },
        label = "ring",
    )
    Box(
        modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(container)
            .border(3.dp, ring, RoundedCornerShape(corner))
            .combinedClickable(interactionSource = source, indication = ripple(), onClick = { press(name) }, onLongClick = { long(name) }, onClickLabel = prettyName(name)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, prettyName(name), Modifier.size(26.dp), tint = content)
            if (icon == null || showLabel) Text(if (showLabel) prettyName(name) else shortLabel(name), color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Big circular D-pad: arrows on a tonal ring, OK in the middle. */
@Composable
private fun Dpad(l: RemoteLayout, d: Dp, f: SendFeedback?, press: (String) -> Unit, long: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(d).clip(CircleShape).background(cs.primaryContainer.copy(alpha = 0.55f)).border(2.dp, cs.primaryContainer, CircleShape)) {
        val key = d / 3.2f
        @Composable fun arrow(name: String?, align: Alignment) = name?.let {
            Box(Modifier.align(align).padding(4.dp).size(key)) {
                TileKey(it, f, press, long, round = true, container = Color.Transparent, content = cs.onPrimaryContainer, icon = buttonIcon(it), showLabel = false)
            }
        }
        arrow(l.up, Alignment.TopCenter); arrow(l.down, Alignment.BottomCenter)
        arrow(l.left, Alignment.CenterStart); arrow(l.right, Alignment.CenterEnd)
        l.ok?.let {
            Box(Modifier.align(Alignment.Center).size(d * 0.38f)) {
                TileKey(it, f, press, long, round = true, container = cs.primary, content = cs.onPrimary, icon = null, showLabel = false)
            }
        }
    }
}

/** Touchpad D-pad: swipe for arrows (each 48 dp of travel = one step), tap for OK. */
@Composable
private fun SwipePad(l: RemoteLayout, d: Dp, press: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val step = with(LocalDensity.current) { 48.dp.toPx() }
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flash) { if (flash != null) { kotlinx.coroutines.delay(180); flash = null } }
    fun send(name: String?) { if (name != null) { flash = name; press(name) } }
    Box(
        Modifier.size(d).clip(RoundedCornerShape(40.dp)).background(cs.primaryContainer.copy(alpha = 0.55f))
            .pointerInput(l) { detectTapGestures(onTap = { send(l.ok) }) }
            .pointerInput(l) {
                var dx = 0f; var dy = 0f
                detectDragGestures(onDragStart = { dx = 0f; dy = 0f }) { change, amount ->
                    change.consume()
                    dx += amount.x; dy += amount.y
                    if (abs(dx) >= step || abs(dy) >= step) {
                        if (abs(dx) > abs(dy)) send(if (dx > 0) l.right else l.left) else send(if (dy > 0) l.down else l.up)
                        dx = 0f; dy = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val hint = cs.onPrimaryContainer.copy(alpha = 0.35f)
        Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.align(Alignment.TopCenter).padding(12.dp), tint = if (flash == l.up) cs.primary else hint)
        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.align(Alignment.BottomCenter).padding(12.dp), tint = if (flash == l.down) cs.primary else hint)
        Icon(Icons.Rounded.KeyboardArrowLeft, null, Modifier.align(Alignment.CenterStart).padding(12.dp), tint = if (flash == l.left) cs.primary else hint)
        Icon(Icons.Rounded.KeyboardArrowRight, null, Modifier.align(Alignment.CenterEnd).padding(12.dp), tint = if (flash == l.right) cs.primary else hint)
        val okFlash = flash != null && flash == l.ok
        Text(if (okFlash) "OK" else "Swipe · tap for OK", style = MaterialTheme.typography.labelLarge, color = if (okFlash) cs.primary else cs.onPrimaryContainer)
    }
}

/** Horizontal pill: minus on the left, plus on the right. */
@Composable
private fun Pill(minus: String?, plus: String?, label: String, f: SendFeedback?, press: (String) -> Unit, long: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxSize().clip(CircleShape).background(cs.secondaryContainer), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.fillMaxHeight().aspectRatio(1.2f)) { minus?.let { TileKey(it, f, press, long, true, Color.Transparent, cs.onSecondaryContainer, Icons.Rounded.Remove, false) } }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer, textAlign = TextAlign.Center, maxLines = 1)
        Box(Modifier.fillMaxHeight().aspectRatio(1.2f)) { plus?.let { TileKey(it, f, press, long, true, Color.Transparent, cs.onSecondaryContainer, Icons.Rounded.Add, false) } }
    }
}

// ---- sheets & dialogs ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeySheet(vm: MainViewModel, device: SavedDevice, name: String, onDismiss: () -> Unit, onLayout: () -> Unit, onEdit: () -> Unit) {
    val btn = device.button(name)
    val fav = device.favorites.any { it.equals(name, true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(prettyName(name), style = MaterialTheme.typography.titleLarge)
            Text(vm.urlFor(device.id, name), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        SheetAction(Icons.Rounded.Link, "Copy endpoint URL") { vm.copyUrl(device.id, name); onDismiss() }
        SheetAction(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (fav) "Remove from favourites" else "Add to favourites (home screen)") { vm.setFavorite(device.id, name, !fav); onDismiss() }
        SheetAction(Icons.Rounded.Dashboard, "Move or resize (edit layout)", onClick = onLayout)
        if (device.onScreen.any { it.equals(name, true) }) SheetAction(Icons.Rounded.RemoveCircleOutline, "Remove from screen") { vm.setOnScreen(device.id, name, false); onDismiss() }
        else SheetAction(Icons.Rounded.VisibilityOff, "Hide from remote") { vm.setHidden(device.id, name, true); onDismiss(); vm.toast("Hidden · restore from ⋮ menu") }
        if (btn != null) SheetAction(Icons.Rounded.Edit, "Edit IR code", onClick = onEdit)
        if (btn?.custom == true) SheetAction(Icons.Rounded.DeleteOutline, "Remove key", danger = true) { vm.removeButton(device.id, name); onDismiss() }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GridSizeDialog(cols: Int, rows: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var c by remember { mutableIntStateOf(cols) }
    var r by remember { mutableIntStateOf(rows) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grid size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Columns: $c"); Slider(c.toFloat(), { c = it.roundToInt() }, valueRange = RemoteGrid.COLS.first.toFloat()..RemoteGrid.COLS.last.toFloat(), steps = RemoteGrid.COLS.last - RemoteGrid.COLS.first - 1)
                Text("Rows: $r"); Slider(r.toFloat(), { r = it.roundToInt() }, valueRange = RemoteGrid.ROWS.first.toFloat()..RemoteGrid.ROWS.last.toFloat(), steps = RemoteGrid.ROWS.last - RemoteGrid.ROWS.first - 1)
                if (c != cols) Text("Changing columns resets the arrangement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(c, r) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

/** Keys the device supports that aren't on the main screen yet. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddKeysSheet(vm: MainViewModel, device: SavedDevice, layout: RemoteLayout, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add keys to screen", style = MaterialTheme.typography.titleLarge)
            Text("Tap a key to add it. Long-press it on the remote to remove it again.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                layout.available.forEach { n ->
                    FilterChip(
                        selected = false, onClick = { vm.setOnScreen(device.id, n, true) },
                        label = { Text(prettyName(n)) },
                        leadingIcon = { Icon(buttonIcon(n) ?: Icons.Rounded.Add, null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (layout.available.isEmpty()) Text("All keys are on screen.")
        }
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

