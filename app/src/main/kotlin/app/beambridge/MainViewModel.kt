package app.beambridge

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.beambridge.core.data.*
import app.beambridge.core.ir.IrCode
import app.beambridge.core.server.BindOption
import app.beambridge.core.server.Net
import app.beambridge.core.server.SendResult
import app.beambridge.service.BridgeRuntime
import app.beambridge.service.BridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Route {
    data object Hub : Route
    data class Device(val id: String) : Route
    data object Wizard : Route
    data class Finder(val deviceId: String) : Route
    data object History : Route
    data object Scenes : Route
    data class SceneEdit(val id: String?) : Route
}

enum class WizardStep { Type, Custom, Brand, Power, Test, Name }

data class WizardState(
    val step: WizardStep = WizardStep.Type,
    val type: DeviceType? = null,
    val query: String = "",
    val brands: List<Brand> = emptyList(),
    val brand: Brand? = null,
    val deviceOn: Boolean = false,
    val candidates: List<Candidate> = emptyList(),
    val index: Int = 0,
    val name: String = "",
    val loading: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as BeamBridgeApp).graph
    private val bridge = graph.bridge
    private val controller = graph.controller
    private val pairing = graph.pairing
    val finder = graph.finder

    val devices: StateFlow<List<SavedDevice>> = graph.store.devices
    val datasets: StateFlow<Datasets> = graph.datasets
    val settings: StateFlow<BridgeSettings> = graph.settings.settings
    val emitterAvailable: Boolean get() = controller.emitterAvailable

    val route = MutableStateFlow<Route>(Route.Hub)
    val wizard = MutableStateFlow(WizardState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    fun toast(text: String) { _messages.tryEmit(text) }

    // ---- navigation ---------------------------------------------------------------------------------

    fun open(id: String) { route.value = Route.Device(id) }
    fun home() { route.value = Route.Hub }

    /** @return true if the back press was consumed. */
    fun back(): Boolean {
        when (val r = route.value) {
            Route.Hub -> return false
            is Route.Device -> home()
            is Route.Finder -> {
                finder.reset()
                // "I don't know the brand" made a placeholder device; drop it if nothing was saved to it.
                if (r.deviceId == placeholderDevice && graph.store.get(r.deviceId)?.buttons.isNullOrEmpty()) {
                    graph.store.remove(r.deviceId); placeholderDevice = null; home()
                } else route.value = Route.Device(r.deviceId)
            }
            Route.History, Route.Scenes -> home()
            is Route.SceneEdit -> route.value = Route.Scenes
            Route.Wizard -> wizard.update {
                when (it.step) {
                    WizardStep.Type -> { home(); it }
                    WizardStep.Custom, WizardStep.Brand -> it.copy(step = WizardStep.Type, query = "")
                    WizardStep.Power -> it.copy(step = WizardStep.Brand)
                    WizardStep.Test -> it.copy(step = WizardStep.Power)
                    WizardStep.Name -> it.copy(step = WizardStep.Test)
                }
            }
        }
        return true
    }

    // ---- server & settings --------------------------------------------------------------------------

    fun setServerRunning(on: Boolean) {
        val ctx = getApplication<Application>()
        if (on) BridgeService.start(ctx) else BridgeService.stop(ctx)
    }

    fun bindOptions(): List<BindOption> = Net.bindOptions()

    /** True once the user granted location, which Android requires before it reveals the Wi-Fi name. */
    fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Network label for the dashboard: "Wi-Fi: HomeNet" with permission, "Wi-Fi" without, "Ethernet", or null when offline. */
    @Suppress("DEPRECATION")
    fun networkName(): String? {
        val ctx = getApplication<Application>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = if (hasLocationPermission())
                    (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.ssid
                else null
                ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }?.let { "Wi-Fi: $it" } ?: "Wi-Fi"
            }
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> null
        }
    }

    /** Async availability check for the settings sheet. */
    fun checkAddress(address: String, port: Int, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val host = Net.resolve(address)
            val text = when {
                host == "" -> "Not on this phone right now"
                bridge.isFree(host, port) -> "Free · http://${Net.publicHost(host) ?: "<phone-ip>"}:$port"
                else -> "Port $port is in use"
            }
            withContext(Dispatchers.Main) { onResult(text) }
        }
    }

    /** @return error text, or null when saved. */
    fun saveSettings(next: BridgeSettings): String? {
        val err = bridge.applySettings(next)
        if (err == null) toast("Saved")
        return err
    }

    // ---- pressing buttons ---------------------------------------------------------------------------

    private fun report(r: SendResult) {
        when (r) {
            is SendResult.Failed -> toast(r.message)
            is SendResult.NotFound -> toast(r.message)
            is SendResult.Ok -> {}
        }
    }

    /** Live "sending / sent / failed" state of the most recent key press, for on-screen confirmation. */
    val feedback = MutableStateFlow<SendFeedback?>(null)

    fun press(deviceId: String, button: String) {
        val id = System.nanoTime()
        feedback.value = SendFeedback(id, button, SendFeedback.State.SENDING)
        viewModelScope.launch(Dispatchers.Default) {
            val r = controller.blast(deviceId, button)
            feedback.value = SendFeedback(id, button, if (r is SendResult.Ok) SendFeedback.State.SENT else SendFeedback.State.FAILED, (r as? SendResult.Failed)?.message ?: (r as? SendResult.NotFound)?.message)
            report(r)
            delay(1200)
            if (feedback.value?.id == id) feedback.value = null
        }
    }

    fun sendCode(code: IrCode) {
        viewModelScope.launch(Dispatchers.Default) { report(controller.send(code, toggleKey = "preview", label = "Custom code ${CodeInput.format(code).take(32)}")) }
    }

    fun urlFor(deviceId: String, button: String) = graph.endpoints.blast(deviceId, button)

    fun copyUrl(deviceId: String, button: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BeamBridge endpoint", urlFor(deviceId, button)))
        toast("URL copied")
    }

    fun layout(device: SavedDevice): RemoteLayout = bridge.layout(device)
    fun acRemote(device: SavedDevice) = controller.acRemote(device)
    fun buttonNames(device: SavedDevice) = controller.visibleButtons(device)

    // ---- device editing -----------------------------------------------------------------------------

    fun rename(id: String, name: String) { graph.store.update(id) { it.copy(name = name.trim().ifEmpty { it.name }) } }
    fun delete(id: String) {
        val scenesChanged = bridge.deleteDevice(id)
        if (scenesChanged > 0) toast("Removed its steps from $scenesChanged scene${if (scenesChanged == 1) "" else "s"}")
        if (route.value != Route.Hub) home()
    }
    fun setArchived(id: String, archived: Boolean) { bridge.setArchived(id, archived) }
    fun setHidden(id: String, button: String, hidden: Boolean) { bridge.setHidden(id, button, hidden) }
    fun showAllHidden(id: String) { graph.store.update(id) { it.copy(hidden = emptyList()) } }
    fun setOnScreen(id: String, button: String, on: Boolean) { bridge.setOnScreen(id, button, on) }
    fun setSwipe(id: String, swipe: Boolean) { bridge.setSwipe(id, swipe) }
    fun grid(device: SavedDevice) = bridge.grid(device)

    /** One AC frame with the new temperature (the dial/slider), instead of N separate TEMP_UP presses. */
    fun setAcTemperature(id: String, t: Int) {
        feedback.value = SendFeedback(System.nanoTime(), "${t}°", SendFeedback.State.SENDING)
        viewModelScope.launch(Dispatchers.Default) {
            val (r, _) = controller.ac(id) { it.copy(power = true, temperature = t) }
            feedback.value = SendFeedback(System.nanoTime(), "${t}°", if (r is SendResult.Ok) SendFeedback.State.SENT else SendFeedback.State.FAILED, (r as? SendResult.Failed)?.message)
            report(r)
        }
    }
    fun moveTile(id: String, tile: String, col: Int, row: Int, w: Int, h: Int) { bridge.moveTile(id, tile, col, row, w, h)?.let(::toast) }
    fun setGridSize(id: String, cols: Int?, rows: Int?) { bridge.setGridSize(id, cols, rows)?.let(::toast) }
    fun resetGrid(id: String) { bridge.resetGrid(id) }
    fun setFavorite(id: String, button: String, on: Boolean) { bridge.setFavorite(id, button, on); toast(if (on) "Added to favourites" else "Removed from favourites") }
    /** Last command that actually went out for this device (IR is one-way: this is what was sent, not confirmed). */
    fun lastSent(deviceId: String) = graph.history.entries.value.firstOrNull { it.device == deviceId && it.ok }

    /** App-only preferences (theme, lock screen, volume keys): saved without restarting the server. */
    fun updatePrefs(transform: (BridgeSettings) -> BridgeSettings) { graph.settings.update(transform) }

    // ---- phone volume keys --------------------------------------------------------------------------

    private val held = mutableSetOf<Boolean>()      // true = up, false = down
    private var chord = false
    private var pendingVolume: Job? = null
    /** Last power state sent per device by the volume-key chord, so discrete ON/OFF codes alternate. */
    private val lastPowerOn = mutableMapOf<String, Boolean>()

    /** Called for every volume key event while the app is in front. @return true when consumed. */
    fun onVolumeKey(up: Boolean, pressed: Boolean, repeat: Int): Boolean {
        val id = (route.value as? Route.Device)?.id ?: return false
        if (!settings.value.volumeKeys) return false
        val device = devices.value.firstOrNull { it.id == id } ?: return false
        if (!pressed) {
            held.remove(up)
            if (held.isEmpty()) chord = false
            return true
        }
        if (repeat == 0) held.add(up)
        if (held.size == 2) {
            if (!chord) { chord = true; pendingVolume?.cancel(); togglePower(device) }
            return true
        }
        if (chord) return true
        val key = if (up) "VOL_UP" else "VOL_DOWN"
        val name = controller.buttonNames(device).firstOrNull { it.equals(key, true) }
            ?: if (device.type == DeviceType.AC) (if (up) "TEMP_UP" else "TEMP_DOWN") else return true
        pendingVolume?.cancel()
        // Short wait on the first press so pressing both keys sends power only, not a volume step first.
        pendingVolume = viewModelScope.launch { if (repeat == 0) delay(90); press(id, name) }
        return true
    }

    private fun togglePower(device: SavedDevice) {
        val names = controller.buttonNames(device).map { it.uppercase() }.toSet()
        val wasOn = when {
            device.type == DeviceType.AC -> device.acState?.power ?: false
            else -> lastPowerOn[device.id] ?: false
        }
        val key = when {
            device.type == DeviceType.AC -> if (wasOn) "POWER_OFF" else "POWER_ON"
            "POWER_ON" in names && "POWER_OFF" in names -> if (wasOn) "POWER_OFF" else "POWER_ON"
            "POWER" in names -> "POWER"
            "POWER_TOGGLE" in names -> "POWER_TOGGLE"
            else -> { toast("No power key"); return }
        }
        lastPowerOn[device.id] = !wasOn
        press(device.id, key)
        toast(if (key == "POWER_OFF") "Power off" else if (key == "POWER_ON") "Power on" else "Power")
    }

    fun saveButton(deviceId: String, name: String, code: IrCode, replacing: String?): Boolean {
        val clean = name.trim().uppercase().replace(Regex("\\s+"), "_")
        if (clean.isEmpty()) { toast("Name needed"); return false }
        graph.store.upsertButton(deviceId, SavedButton(clean, code, custom = true), replacing)
        return true
    }

    fun removeButton(deviceId: String, name: String) { graph.store.removeButton(deviceId, name) }

    /** Dataset buttons from the same brand that this device does not have yet. */
    fun candidateButtons(device: SavedDevice): List<Pair<String, IrCode>> {
        val ref = device.catalog ?: return emptyList()
        if (ref.source != "tv") return emptyList()
        val ds = datasets.value
        val brand = ds.tv.brand(ref.manufacturerId ?: return emptyList()) ?: return emptyList()
        val have = device.buttons.map { it.name.uppercase() }.toSet()
        val seen = linkedMapOf<String, IrCode>()
        for (r in brand.remotes) {
            if (!r.supported) continue
            for (name in r.buttons.keys) if (name.uppercase() !in have && name !in seen) seen[name] = r.irCode(name)!!
        }
        return seen.entries.sortedBy { StandardKeys.sortKey(it.key) }.map { it.key to it.value }
    }

    // ---- history -----------------------------------------------------------------------------------

    val history: StateFlow<List<HistoryEntry>> = graph.history.entries
    fun clearHistory() = graph.history.clear()

    // ---- scenes / macros ----------------------------------------------------------------------------

    val scenes: StateFlow<List<Scene>> = graph.scenes.scenes
    /** Name of the scene currently running, for a progress indicator. */
    val runningScene = MutableStateFlow<String?>(null)

    fun runScene(scene: Scene) {
        if (runningScene.value != null) { toast("A scene is already running"); return }
        runningScene.value = scene.name
        viewModelScope.launch(Dispatchers.IO) {
            val results = bridge.runScene(scene, source = "scene")
            val failed = results.count { it.second != null && it.second !is SendResult.Ok }
            runningScene.value = null
            toast(if (failed == 0) "${scene.name} done" else "${scene.name}: $failed step${if (failed == 1) "" else "s"} failed")
        }
    }

    /** @return error text, or null when saved. */
    fun saveScene(id: String?, name: String, steps: List<SceneStep>): String? =
        bridge.saveScene(id, name, steps).fold({ route.value = Route.Scenes; toast("Saved"); null }, { it.message })

    fun deleteScene(id: String) { graph.scenes.remove(id); route.value = Route.Scenes }
    fun sceneUrl(scene: Scene) = "${graph.endpoints.base()}/api/scenes/${java.net.URLEncoder.encode(scene.id, "UTF-8")}/run"
    fun copyText(label: String, text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text)); toast("Copied")
    }

    // ---- onboarding ---------------------------------------------------------------------------------

    fun finishOnboarding(addDevice: Boolean) {
        updatePrefs { it.copy(onboarded = true) }
        if (addDevice) startWizard()
    }

    // ---- pairing wizard -----------------------------------------------------------------------------

    fun startWizard() { wizard.value = WizardState(); route.value = Route.Wizard }

    fun pickType(type: DeviceType) {
        if (type == DeviceType.OTHER) { wizard.update { it.copy(type = type, step = WizardStep.Custom) }; return }
        wizard.update { it.copy(type = type, step = WizardStep.Brand, query = "", brands = emptyList()) }
        loadBrands("")
    }

    private var brandJob: Job? = null

    fun setQuery(q: String) { wizard.update { it.copy(query = q) }; loadBrands(q) }

    private fun loadBrands(q: String) {
        val type = wizard.value.type ?: return
        brandJob?.cancel()
        brandJob = viewModelScope.launch(Dispatchers.IO) {
            delay(150)
            val list = pairing.brands(type, q)
            wizard.update { it.copy(brands = list) }
        }
    }

    fun pickBrand(b: Brand) = wizard.update { it.copy(brand = b, step = WizardStep.Power) }

    fun setDeviceOn(on: Boolean) {
        val w = wizard.value
        val type = w.type ?: return
        val brand = w.brand ?: return
        wizard.update { it.copy(loading = true, deviceOn = on) }
        viewModelScope.launch(Dispatchers.IO) {
            val list = pairing.candidates(type, brand.id)
            withContext(Dispatchers.Main) {
                if (list.isEmpty()) {
                    toast("No usable codes for ${brand.name}")
                    wizard.update { it.copy(loading = false) }
                } else {
                    wizard.update { it.copy(loading = false, candidates = list, index = 0, step = WizardStep.Test) }
                }
            }
        }
    }

    fun testKey(): String? = wizard.value.let { w -> w.candidates.getOrNull(w.index)?.let { pairing.testKey(it, w.deviceOn) } }

    fun sendTest() {
        val w = wizard.value
        val c = w.candidates.getOrNull(w.index) ?: return
        viewModelScope.launch(Dispatchers.Default) { report(pairing.test(c, w.deviceOn)) }
    }

    fun nextCandidate() = wizard.update { it.copy(index = (it.index + 1) % it.candidates.size.coerceAtLeast(1)) }
    fun previousCandidate() = wizard.update { it.copy(index = (it.index - 1 + it.candidates.size) % it.candidates.size.coerceAtLeast(1)) }

    fun confirmWorked() = wizard.update {
        val suffix = when (it.type) { DeviceType.AC -> "AC"; DeviceType.SET_TOP_BOX -> "Box"; else -> "TV" }
        it.copy(step = WizardStep.Name, name = "${it.brand?.name.orEmpty()} $suffix".trim())
    }

    fun setName(name: String) = wizard.update { it.copy(name = name) }

    /** Unknown brand: make an empty device of this type and open the TV finder to discover its codes. */
    private var placeholderDevice: String? = null

    fun unknownBrand() {
        val type = wizard.value.type ?: DeviceType.TV
        val d = pairing.createCustom(if (type == DeviceType.SET_TOP_BOX) "My set-top box" else "My TV", type, null)
        placeholderDevice = d.id
        wizard.value = WizardState()
        openFinder(d.id)
        toast("Pick POWER, point at the device and tap STOP when it reacts")
    }

    fun saveWizardDevice() {
        val w = wizard.value
        val c = w.candidates.getOrNull(w.index) ?: return
        val d = pairing.create(w.type ?: DeviceType.TV, c, w.name, w.brand?.name)
        wizard.value = WizardState()
        route.value = Route.Device(d.id)
    }

    /** Custom device: name + optional first button. [codeText] uses the free-text formats in [CodeInput.EXAMPLES]. */
    fun createCustom(name: String, buttonName: String, codeText: String) {
        val first = if (buttonName.isBlank() || codeText.isBlank()) null else try {
            SavedButton(buttonName.trim().uppercase().replace(Regex("\\s+"), "_"), CodeInput.parseText(codeText), custom = true)
        } catch (e: Exception) {
            toast(e.message ?: "Bad IR code"); return
        }
        val d = pairing.createCustom(name, DeviceType.OTHER, first)
        wizard.value = WizardState()
        route.value = Route.Device(d.id)
    }

    fun parseCode(text: String): IrCode? = try { CodeInput.parseText(text) } catch (e: Exception) { toast(e.message ?: "Bad IR code"); null }

    // ---- finder -------------------------------------------------------------------------------------

    fun openFinder(deviceId: String) { finder.reset(); route.value = Route.Finder(deviceId) }
    fun startFinder(key: String) = finder.start(key)
    fun stopFinder() = finder.stop()
    fun resumeFinder() = finder.resume()
    fun finderPage(page: Int) = finder.setPage(page)
    fun finderWindow() = finder.window()
    fun testFinder(index: Int) { viewModelScope.launch(Dispatchers.Default) { report(finder.test(index)) } }
    fun acceptFinder(deviceId: String, name: String, index: Int) {
        finder.accept(deviceId, name, index)
        if (deviceId == placeholderDevice) placeholderDevice = null
        toast("${name.ifBlank { "Button" }} saved")
    }
}

data class SendFeedback(val id: Long, val key: String, val state: State, val message: String? = null) {
    enum class State { SENDING, SENT, FAILED }
}
