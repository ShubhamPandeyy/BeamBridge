package app.beambridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            val prefs by vm.settings.collectAsStateWithLifecycle()
            val route by vm.route.collectAsStateWithLifecycle()
            // Over the lock screen only while a remote is open and the user turned it on.
            LaunchedEffect(prefs.lockScreenMigrated) {
                if (!prefs.lockScreenMigrated) vm.updatePrefs { it.copy(lockScreen = true, lockScreenMigrated = true) }
            }
            LaunchedEffect(prefs.themeMigrated19) {
                if (!prefs.themeMigrated19) vm.updatePrefs { it.copy(theme = if (it.theme == "system") "beam" else it.theme, themeMigrated19 = true) }
            }
            val showOverLock = prefs.lockScreen && route is Route.Device
            LaunchedEffect(showOverLock) { setOverLockScreen(showOverLock) }
            BeamBridgeTheme(prefs.theme, prefs.darkMode) { ProvideBeamHaptics(prefs.haptics) { App(vm) } }
        }
    }

    @Suppress("DEPRECATION")
    private fun setOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            // Wake the screen too if a remote is on top when the phone is picked up.
            setTurnScreenOn(on)
        } else if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    override fun onStart() { super.onStart(); AppVisibility.foreground = true }

    override fun onStop() {
        AppVisibility.foreground = false
        (application as BeamBridgeApp).graph.history.flush()
        super.onStop()
    }

    /** Phone volume keys drive the open remote (see [MainViewModel.onVolumeKey]). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return super.dispatchKeyEvent(event)
        }
        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> vm.onVolumeKey(up, pressed = true, repeat = event.repeatCount)
            KeyEvent.ACTION_UP -> vm.onVolumeKey(up, pressed = false, repeat = 0)
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val route by vm.route.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val wizard by vm.wizard.collectAsStateWithLifecycle()
    val server by BridgeRuntime.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val prefs by vm.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        // First run asks inside onboarding instead.
        if (vm.settings.value.autoStart && !BridgeRuntime.status.value.running) vm.setServerRunning(true)
        if (vm.settings.value.iosAutoStart && !vm.iosState.value.running) vm.setIosRemoteRunning(true)
    }
    // One host for the whole app; a new message replaces the current one instead of queueing behind it.
    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) }
        }
    }

    BackHandler(enabled = route != Route.Hub) { vm.back() }

    if (!prefs.onboarded) { OnboardingScreen(vm); return }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(targetState = route, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route") { r ->
            when (r) {
                Route.Hub -> HubScreen(vm, devices, server, onSettings = { vm.openSettings() })
                is Route.Device -> devices.firstOrNull { it.id == r.id }?.let { DeviceScreen(vm, it) } ?: LaunchedEffect(Unit) { vm.home() }
                is Route.Finder -> devices.firstOrNull { it.id == r.deviceId }?.let { FinderScreen(vm, it) } ?: LaunchedEffect(Unit) { vm.home() }
                Route.Wizard -> WizardScreen(vm, wizard)
                Route.History -> HistoryScreen(vm)
                Route.Scenes -> ScenesScreen(vm)
                is Route.IosSetup -> devices.firstOrNull { it.id == r.deviceId }?.let { IosRemoteScreen(vm, it) } ?: LaunchedEffect(Unit) { vm.home() }
                is Route.Settings -> SettingsScreen(vm, r.page)
                is Route.SceneEdit -> SceneEditorScreen(vm, r.id?.let { id -> vm.scenes.value.firstOrNull { it.id == id } }, devices)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }

}
