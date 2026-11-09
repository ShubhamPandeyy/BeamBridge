package app.beambridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.service.BridgeRuntime
import app.beambridge.ui.BeamBridgeTheme
import app.beambridge.ui.DeviceScreen
import app.beambridge.ui.HubScreen
import app.beambridge.ui.SettingsSheet
import app.beambridge.ui.WizardScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BeamBridgeTheme { App(vm) } }
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val route by vm.route.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    val wizard by vm.wizard.collectAsStateWithLifecycle()
    val server by BridgeRuntime.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var settingsOpen by remember { mutableStateOf(false) }

    // Notification permission (Android 13+) so the foreground service notification is visible.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (vm.settings.autoStart.value && !BridgeRuntime.status.value.running) vm.setServerRunning(true)
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    BackHandler(enabled = route != Route.Hub) { vm.back() }

    AnimatedContent(targetState = route, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route") { r ->
        when (r) {
            Route.Hub -> HubScreen(vm, devices, server, snackbar, onSettings = { settingsOpen = true })
            is Route.Device -> {
                val device = devices.firstOrNull { it.id == r.id }
                if (device != null) DeviceScreen(vm, device, snackbar) else LaunchedEffect(Unit) { vm.home() }
            }
            Route.Wizard -> WizardScreen(vm, wizard, datasets, snackbar)
        }
    }

    if (settingsOpen) SettingsSheet(vm, server.running) { settingsOpen = false }
}
