package app.beambridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.MainViewModel
import app.beambridge.core.data.AuthMode

/** Web access (guest / password), bind address with availability check, port, wake lock, auto start. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val current by vm.settings.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(current) }
    var portText by remember { mutableStateOf(current.port.toString()) }
    var showPw by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var addrMenu by remember { mutableStateOf(false) }
    val options = remember { vm.bindOptions() }
    val port = portText.toIntOrNull() ?: 0

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            Text("Appearance", style = MaterialTheme.typography.titleSmall)
            ThemePicker(current.theme) { t -> vm.updatePrefs { it.copy(theme = t) } }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("auto" to "System", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (mode, label) ->
                    SegmentedButton(selected = current.darkMode == mode, onClick = { vm.updatePrefs { it.copy(darkMode = mode) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }

            Text("Remote", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Phone volume keys control the remote", "Press both together to toggle power", current.volumeKeys) { on -> vm.updatePrefs { it.copy(volumeKeys = on) } }
            SwitchRow("Notify when a command is sent", "Silent \"Power on beamed to …\" confirmation for sends from the web, API or scenes", current.notifyOnSend) { on -> vm.updatePrefs { it.copy(notifyOnSend = on) } }
            NotificationPermissionRow()
            SwitchRow("Show remote on lock screen", "While a remote is open, it stays usable without unlocking", current.lockScreen) { on -> vm.updatePrefs { it.copy(lockScreen = on) } }
            HorizontalDivider()

            Text("Web UI access", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(AuthMode.GUEST to "Guest (open)", AuthMode.PASSWORD to "Username + password").forEachIndexed { i, (mode, label) ->
                    SegmentedButton(selected = draft.authMode == mode, onClick = { draft = draft.copy(authMode = mode) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label, maxLines = 1) }
                }
            }
            if (draft.authMode == AuthMode.PASSWORD) {
                OutlinedTextField(draft.username, { draft = draft.copy(username = it.trim()) }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    draft.password, { draft = draft.copy(password = it) },
                    label = { Text(if (current.hasPassword) "New password (blank keeps the current one)" else "Password") }, singleLine = true,
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showPw = !showPw }) { Icon(if (showPw) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show password") } },
                    supportingText = { Text("Applies to the web UI and every API call (HTTP Basic auth).") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Network", style = MaterialTheme.typography.titleSmall)
            ExposedDropdownMenuBox(expanded = addrMenu, onExpandedChange = { addrMenu = it }) {
                OutlinedTextField(
                    options.firstOrNull { it.address == draft.bindAddress }?.label ?: draft.bindAddress,
                    {}, readOnly = true, label = { Text("Listen on") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(addrMenu) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = addrMenu, onDismissRequest = { addrMenu = false }) {
                    options.forEach { o ->
                        DropdownMenuItem(text = { Text(o.label) }, onClick = { draft = draft.copy(bindAddress = o.address); addrMenu = false; check = null })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    portText, { portText = it.filter(Char::isDigit).take(5); check = null }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { check = "Checking…"; vm.checkAddress(draft.bindAddress, port) { check = it } }, enabled = port in 1024..65535) { Text("Check") }
            }
            check?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            SwitchRow(
                "Allow actions from simple GET links",
                "For automations that can only open a URL. Other websites can never use them either way.",
                draft.allowGetActions,
            ) { draft = draft.copy(allowGetActions = it) }

            Text("Background", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Keep CPU and Wi-Fi awake", "Reliable responses while the screen is off", draft.wakeLock) { draft = draft.copy(wakeLock = it) }
            SwitchRow("Start server when the app opens", null, draft.autoStart) { draft = draft.copy(autoStart = it) }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    // Only the web/server fields come from the draft; app preferences keep their live values.
                    error = vm.saveSettings(current.copy(
                        port = port, bindAddress = draft.bindAddress, authMode = draft.authMode, username = draft.username,
                        password = draft.password, wakeLock = draft.wakeLock, autoStart = draft.autoStart, allowGetActions = draft.allowGetActions,
                    ))
                    if (error == null) onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Save") }
        }
    }
}

/** Shows whether Android lets us post notifications, with a shortcut to fix it (also for people who skipped onboarding). */
@Composable
private fun NotificationPermissionRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        enabled = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    if (enabled) return
    ListItem(
        headlineContent = { Text("Notifications are blocked") },
        supportingContent = { Text("The server's status and send confirmations can't be shown") },
        trailingContent = {
            FilledTonalButton(onClick = {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Allow") }
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

/** Colour swatches: "System" (wallpaper colours) plus the M3 presets. */
@Composable
private fun ThemePicker(selected: String, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val options = listOf(Triple("system", if (dynamicColorAvailable) "System" else "Default", cs.primary)) +
            PRESETS.map { Triple(it.id, it.label, it.seed) }
        items(options.size) { i ->
            val (id, label, color) = options[i]
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    onClick = { onPick(id) }, shape = androidx.compose.foundation.shape.CircleShape, color = color,
                    border = if (selected == id) androidx.compose.foundation.BorderStroke(3.dp, cs.onSurface) else null,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (selected == id) Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = androidx.compose.ui.graphics.Color.White) }
                    else if (id == "system") Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Wallpaper, null, tint = androidx.compose.ui.graphics.Color.White) }
                }
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
