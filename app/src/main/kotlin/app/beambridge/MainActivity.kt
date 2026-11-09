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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beambridge.service.BridgeRuntime
import app.beambridge.ui.*

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
    val wizard by vm.wizard.collectAsStateWithLifecycle()
    val server by BridgeRuntime.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var settingsOpen by remember { mutableStateOf(false) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (vm.settings.value.autoStart && !BridgeRuntime.status.value.running) vm.setServerRunning(true)
    }
    // One host for the whole app; a new message replaces the current one instead of queueing behind it.
    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) }
        }
    }

    BackHandler(enabled = route != Route.Hub) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(targetState = route, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route") { r ->
            when (r) {
                Route.Hub -> HubScreen(vm, devices, server, onSettings = { settingsOpen = true })
                is Route.Device -> devices.firstOrNull { it.id == r.id }?.let { DeviceScreen(vm, it) } ?: LaunchedEffect(Unit) { vm.home() }
                is Route.Finder -> devices.firstOrNull { it.id == r.deviceId }?.let { FinderScreen(vm, it) } ?: LaunchedEffect(Unit) { vm.home() }
                Route.Wizard -> WizardScreen(vm, wizard)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }

    if (settingsOpen) SettingsSheet(vm) { settingsOpen = false }
}
