package app.beambridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.MainViewModel
import app.beambridge.core.data.AuthMode

/** Settings pages: the root list, and one sub-page per section. A full page (not a sheet) so it never wobbles. */
enum class SettingsPage(val title: String) { Root("Settings"), Appearance("Look & feel"), Remote("Remote"), Iphone("iPhone remote"), Server("Web server"), About("About") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, page: SettingsPage) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(page.title, style = if (scroll.state.collapsedFraction < 0.5f) BeamType.hero else BeamType.screenTitle) },
                navigationIcon = { FilledTonalIconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 8.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (page) {
                SettingsPage.Root -> SettingsRoot(vm)
                SettingsPage.Appearance -> AppearancePage(vm)
                SettingsPage.Remote -> RemotePage(vm)
                SettingsPage.Iphone -> IosRemoteSettings(vm)
                SettingsPage.Server -> ServerPage(vm)
                SettingsPage.About -> AboutPage(vm)
            }
        }
    }
}

@Composable
private fun SettingsRoot(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ios by vm.iosState.collectAsStateWithLifecycle()
    MenuGroup("App") {
        MenuRow(Icons.Rounded.Palette, "Look & feel", "Colours, dark mode, vibration (${BeamHaptics.LABELS[s.haptics] ?: s.haptics})", chevron = true) { vm.openSettings(SettingsPage.Appearance) }
        MenuRow(Icons.Rounded.SettingsRemote, "Remote", "Volume keys, lock screen, notifications", chevron = true) { vm.openSettings(SettingsPage.Remote) }
    }
    MenuGroup("Connections") {
        MenuRow(Icons.Rounded.PhoneIphone, "iPhone remote", if (ios.running) "On · ${ios.pairedPhones} paired" else "Off · PIN, paired iPhones, devices", chevron = true) { vm.openSettings(SettingsPage.Iphone) }
        MenuRow(Icons.Rounded.Dns, "Web server", "Port ${s.port} · ${if (s.authMode == AuthMode.PASSWORD) "password" else "open"}", chevron = true) { vm.openSettings(SettingsPage.Server) }
    }
    MenuGroup("BeamBridge") {
        CoffeeRow { vm.buyMeCoffee() }
        MenuRow(Icons.Rounded.Info, "About", "Version, licence, source code", chevron = true) { vm.openSettings(SettingsPage.About) }
    }
}

/** "Buy me a coffee": the one coloured row in Settings, with a cookie-shaped badge. */
@Composable
private fun CoffeeRow(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer, shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(Icons.Rounded.Coffee, size = 44.dp, shape = BeamShapes.coffee, container = cs.tertiary, content = cs.onTertiary)
            Column {
                Text("Buy me a coffee", style = BeamType.cardTitle)
                Text("Say hi to the developer", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AppearancePage(vm: MainViewModel) {
    val current by vm.settings.collectAsStateWithLifecycle()
    val haptics = LocalBeamHaptics.current
    MenuGroup("Colours") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ThemePicker(current.theme) { t -> vm.updatePrefs { it.copy(theme = t) } }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("auto" to "System", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (mode, label) ->
                        SegmentedButton(selected = current.darkMode == mode, onClick = { vm.updatePrefs { it.copy(darkMode = mode) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                    }
                }
            }
        }
    }
    MenuGroup("Vibration") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Buzz when you press a key", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BeamHaptics.LEVELS.forEachIndexed { i, level ->
                        SegmentedButton(
                            selected = current.haptics == level,
                            onClick = { vm.updatePrefs { it.copy(haptics = level) }; haptics.withLevel(level).key() },
                            shape = SegmentedButtonDefaults.itemShape(i, BeamHaptics.LEVELS.size),
                        ) { Text(BeamHaptics.LABELS[level] ?: level, maxLines = 1) }
                    }
                }
                Text("Some phones ignore the strength and always buzz the same.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RemotePage(vm: MainViewModel) {
    val current by vm.settings.collectAsStateWithLifecycle()
    MenuGroup("Controls") {
        MenuSwitchRow(Icons.AutoMirrored.Rounded.VolumeUp, "Phone volume keys control the remote", "Press both together to toggle power", current.volumeKeys) { on -> vm.updatePrefs { it.copy(volumeKeys = on) } }
        MenuSwitchRow(Icons.Rounded.Lock, "Show remote on lock screen", "While a remote is open, it stays usable without unlocking", current.lockScreen) { on -> vm.updatePrefs { it.copy(lockScreen = on) } }
    }
    MenuGroup("Notifications") {
        MenuSwitchRow(Icons.Rounded.Notifications, "Notify when a command is sent", "Silent confirmation for sends from the web, API or scenes", current.notifyOnSend) { on -> vm.updatePrefs { it.copy(notifyOnSend = on) } }
        NotificationPermissionRow()
    }
}

/** Web access (guest / password), bind address with availability check, port, wake lock, auto start. Saved with one button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPage(vm: MainViewModel) {
    val current by vm.settings.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(current) }
    var portText by remember { mutableStateOf(current.port.toString()) }
    var showPw by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var addrMenu by remember { mutableStateOf(false) }
    val options = remember { vm.bindOptions() }
    val port = portText.toIntOrNull() ?: 0

    MenuGroup("Who can use the web UI") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(AuthMode.GUEST to "Anyone (open)", AuthMode.PASSWORD to "Password").forEachIndexed { i, (mode, label) ->
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
            }
        }
        MenuSwitchRow(Icons.Rounded.Link, "Allow actions from simple GET links", "For automations that can only open a URL. Other websites can't use them either way.", draft.allowGetActions) { draft = draft.copy(allowGetActions = it) }
    }

    MenuGroup("Network") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        }
    }

    MenuGroup("Background") {
        MenuSwitchRow(Icons.Rounded.BatteryChargingFull, "Keep CPU and Wi-Fi awake", "Reliable responses while the screen is off", draft.wakeLock) { draft = draft.copy(wakeLock = it) }
        MenuSwitchRow(Icons.Rounded.PlayCircleOutline, "Start server when the app opens", null, draft.autoStart) { draft = draft.copy(autoStart = it) }
    }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = {
            // Only the web/server fields come from the draft; app preferences keep their live values.
            error = vm.saveSettings(current.copy(
                port = port, bindAddress = draft.bindAddress, authMode = draft.authMode, username = draft.username,
                password = draft.password, wakeLock = draft.wakeLock, autoStart = draft.autoStart, allowGetActions = draft.allowGetActions,
            ))
            if (error == null) { vm.toast("Saved"); vm.back() }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text("Save") }
}

@Composable
private fun AboutPage(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    fun open(url: String) = runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    MenuGroup(null) {
        MenuRow(Icons.Rounded.Info, "BeamBridge", "Version $version")
        MenuRow(Icons.Rounded.Code, "Source code", "github.com/ShubhamPandeyy/BeamBridge", chevron = true) { open(MainViewModel.GITHUB_URL) }
        MenuRow(Icons.Rounded.Gavel, "Licence", "MIT. Third-party notices are in the repository.", chevron = true) { open(MainViewModel.GITHUB_URL + "/blob/main/LICENSE") }
        CoffeeRow { vm.buyMeCoffee() }
    }
}

/** iPhone remote: PIN, who may connect, auto start, and which devices appear as Apple TVs. Changes apply at once. */
@Composable
private fun IosRemoteSettings(vm: MainViewModel) {
    val current by vm.settings.collectAsStateWithLifecycle()
    val state by vm.iosState.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    var pin by remember(current.iosPin) { mutableStateOf(current.iosPin) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }

    Text(
        "Each device appears as an Apple TV in the iPhone's Control Center remote. Set up gestures from a device's ⋮ menu → Set up iPhone remote.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
    )
    MenuGroup("Service") {
        MenuSwitchRow(Icons.Rounded.PhoneIphone, "iPhone remote", if (state.running) "On" else "Off", state.running) { vm.setIosRemoteRunning(it) }
        MenuSwitchRow(Icons.Rounded.PlayCircleOutline, "Start when the app opens", null, current.iosAutoStart) { on -> vm.updatePrefs { it.copy(iosAutoStart = on) } }
    }
    MenuGroup("Pairing") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(4); pinError = null }, label = { Text("Pairing PIN") }, singleLine = true,
                    isError = pinError != null, supportingText = { Text(pinError ?: "4 digits. Asked once per iPhone.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { pinError = vm.setIosPin(pin) }, enabled = pin != current.iosPin) { Text("Save") }
            }
        }
        MenuSwitchRow(Icons.Rounded.Shield, "Only paired iPhones", "Off lets any iPhone on your Wi-Fi control your devices", current.iosRequirePairing) { on -> vm.updatePrefs { it.copy(iosRequirePairing = on) } }
        MenuRow(
            Icons.Rounded.PhonelinkErase, "Paired iPhones: ${state.pairedPhones}", "Forgetting makes every iPhone enter the PIN again",
            trailing = { TextButton(onClick = { confirmForget = true }, enabled = state.pairedPhones > 0) { Text("Forget all") } },
        )
    }
    val active = devices.filterNot { it.archived }
    if (active.isNotEmpty()) MenuGroup("Show to the iPhone") {
        active.forEach { d ->
            MenuSwitchRow(deviceIcon(d.type), d.name, if (d.iosGestures.isEmpty()) "No gestures set up" else "${d.iosGestures.size} gesture${if (d.iosGestures.size == 1) "" else "s"}", d.id !in current.iosHidden) { on -> vm.setIosShown(d.id, on) }
        }
    }
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Forget all paired iPhones?") },
        text = { Text("They'll need the PIN again to connect.") },
        confirmButton = { TextButton(onClick = { vm.forgetIphones(); confirmForget = false }) { Text("Forget") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
    )
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
    MenuRow(
        Icons.Rounded.NotificationsOff, "Notifications are blocked", "Status and send confirmations can't be shown",
        trailing = {
            FilledTonalButton(onClick = {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Allow") }
        },
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
