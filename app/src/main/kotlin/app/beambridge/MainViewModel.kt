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
            is Route.Finder -> { finder.reset(); route.value = Route.Device(r.deviceId) }
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

    fun press(deviceId: String, button: String) {
        viewModelScope.launch(Dispatchers.Default) { report(controller.blast(deviceId, button)) }
    }

    fun sendCode(code: IrCode) {
        viewModelScope.launch(Dispatchers.Default) { report(controller.send(code, toggleKey = "preview")) }
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
    fun delete(id: String) { graph.store.remove(id); if (route.value != Route.Hub) home() }
    fun setArchived(id: String, archived: Boolean) { bridge.setArchived(id, archived) }
    fun setHidden(id: String, button: String, hidden: Boolean) { bridge.setHidden(id, button, hidden) }
    fun showAllHidden(id: String) { graph.store.update(id) { it.copy(hidden = emptyList()) } }
    fun moveButton(id: String, button: String, cell: Int) { bridge.moveButton(id, button, cell) }

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
            if (!r.isSupported(ds.protocols)) continue
            for (name in r.buttons.keys) if (name.uppercase() !in have && name !in seen) seen[name] = r.irCode(name)!!
        }
        return seen.entries.sortedBy { StandardKeys.sortKey(it.key) }.map { it.key to it.value }
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
        toast("${name.ifBlank { "Button" }} saved")
    }
}
