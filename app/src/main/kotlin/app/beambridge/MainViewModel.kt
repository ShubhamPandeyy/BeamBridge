package app.beambridge

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.beambridge.core.data.*
import app.beambridge.core.ir.IrCode
import app.beambridge.core.server.SendResult
import app.beambridge.data.StbProvider
import app.beambridge.service.BridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
}

enum class WizardStep { Type, Brand, Test, Name }

/** One remote profile to try during pairing. */
data class Candidate(val ref: CatalogRef, val tv: TvRemote? = null, val ac: AcRemote? = null)

data class WizardState(
    val step: WizardStep = WizardStep.Type,
    val type: DeviceType? = null,
    val query: String = "",
    val brandName: String = "",
    val candidates: List<Candidate> = emptyList(),
    val index: Int = 0,
    val name: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as BeamBridgeApp).graph
    private val controller = graph.controller

    val devices: StateFlow<List<SavedDevice>> = graph.store.devices
    val datasets: StateFlow<Datasets> = graph.datasets
    val settings = graph.settings
    val emitterAvailable: Boolean get() = controller.emitterAvailable

    val route = MutableStateFlow<Route>(Route.Hub)
    val wizard = MutableStateFlow(WizardState())
    val stbProviders = MutableStateFlow<List<StbProvider>>(emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun toast(text: String) { _messages.tryEmit(text) }

    // ---- navigation ---------------------------------------------------------------------------------

    fun open(id: String) { route.value = Route.Device(id) }
    fun home() { route.value = Route.Hub }

    /** @return true if the back press was consumed. */
    fun back(): Boolean {
        val w = wizard.value
        when (route.value) {
            Route.Hub -> return false
            is Route.Device -> home()
            Route.Wizard -> when (w.step) {
                WizardStep.Type -> home()
                WizardStep.Brand -> wizard.update { it.copy(step = WizardStep.Type, query = "") }
                WizardStep.Test -> wizard.update { it.copy(step = WizardStep.Brand) }
                WizardStep.Name -> wizard.update { it.copy(step = WizardStep.Test) }
            }
        }
        return true
    }

    // ---- server -------------------------------------------------------------------------------------

    fun setServerRunning(on: Boolean) {
        val ctx = getApplication<Application>()
        if (on) BridgeService.start(ctx) else BridgeService.stop(ctx)
    }

    fun applySettings(port: Int, wakeLock: Boolean, autoStart: Boolean, serverRunning: Boolean) {
        settings.setPort(port); settings.setWakeLock(wakeLock); settings.setAutoStart(autoStart)
        if (serverRunning) BridgeService.start(getApplication())
    }

    // ---- pressing buttons ---------------------------------------------------------------------------

    private fun report(r: SendResult) {
        if (r !is SendResult.Ok) toast(when (r) { is SendResult.Failed -> r.message; is SendResult.NotFound -> r.message; else -> "" })
    }

    fun press(deviceId: String, button: String) {
        viewModelScope.launch(Dispatchers.Default) { report(controller.blast(deviceId, button)) }
    }

    fun sendCode(code: IrCode) {
        viewModelScope.launch(Dispatchers.Default) { report(controller.send(code, toggleKey = "preview")) }
    }

    fun copyUrl(deviceId: String, button: String) {
        val url = graph.endpoints.blast(deviceId, button)
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BeamBridge endpoint", url))
        toast("URL copied")
    }

    fun buttonNames(device: SavedDevice) = controller.buttonNames(device)
    fun urlFor(deviceId: String, button: String) = graph.endpoints.blast(deviceId, button)
    fun acRemote(device: SavedDevice) = controller.acRemote(device)

    // ---- device editing -----------------------------------------------------------------------------

    fun rename(id: String, name: String) { graph.store.update(id) { it.copy(name = name.trim().ifEmpty { it.name }) } }

    fun delete(id: String) { graph.store.remove(id); home() }

    fun saveButton(deviceId: String, name: String, code: IrCode, replacing: String?) {
        val clean = name.trim().uppercase().replace(Regex("\\s+"), "_")
        if (clean.isEmpty()) { toast("Name needed"); return }
        graph.store.upsertButton(deviceId, SavedButton(clean, code, custom = true), replacing)
    }

    fun removeButton(deviceId: String, name: String) { graph.store.removeButton(deviceId, name) }

    /** Dataset buttons from the same brand that this device does not have yet. */
    fun candidateButtons(device: SavedDevice): List<Pair<String, IrCode>> {
        val ref = device.catalog ?: return emptyList()
        if (ref.source != "tv") return emptyList()
        val brand = datasets.value.tv.brand(ref.manufacturerId ?: return emptyList()) ?: return emptyList()
        val have = device.buttons.map { it.name.uppercase() }.toSet()
        val seen = linkedMapOf<String, IrCode>()
        for (r in brand.remotes) {
            if (!r.isSupported(datasets.value.protocols)) continue
            for (name in r.buttons.keys) if (name.uppercase() !in have && name !in seen) seen[name] = r.irCode(name)!!
        }
        return seen.entries.sortedBy { StandardKeys.sortKey(it.key) }.map { it.key to it.value }
    }

    fun acAction(deviceId: String, action: String) = press(deviceId, action)

    // ---- pairing wizard -----------------------------------------------------------------------------

    fun startWizard() { wizard.value = WizardState(); route.value = Route.Wizard }

    fun pickType(type: DeviceType) {
        wizard.update { it.copy(type = type, step = WizardStep.Brand, query = "") }
        if (type == DeviceType.SET_TOP_BOX) searchProviders("")
    }

    private var providerJob: Job? = null

    fun searchProviders(query: String) {
        wizard.update { it.copy(query = query) }
        providerJob?.cancel()
        providerJob = viewModelScope.launch(Dispatchers.IO) {
            stbProviders.value = graph.stb.providers(query)
        }
    }

    fun setQuery(q: String) {
        if (wizard.value.type == DeviceType.SET_TOP_BOX) searchProviders(q) else wizard.update { it.copy(query = q) }
    }

    fun pickTvBrand(id: Int, name: String) {
        val ds = datasets.value
        val remotes = ds.tv.brand(id)?.remotes.orEmpty().filter { it.isSupported(ds.protocols) }
        val distinct = remotes.distinctBy { testCode(it) }
        beginTest(name, distinct.map { Candidate(CatalogRef("tv", id, it.remoteId), tv = it) })
    }

    fun pickAcBrand(id: Int, name: String) {
        val ds = datasets.value
        val remotes = ds.ac.manufacturers.firstOrNull { it.id == id }?.remotes.orEmpty().filter { it.supported }
        val distinct = remotes.distinctBy { runCatching { AcCodec.hex(it, TEST_AC) }.getOrNull() ?: it.remoteId.toString() }
        beginTest(name, distinct.map { Candidate(CatalogRef("ac", id, it.remoteId), ac = it) })
    }

    fun pickProvider(p: StbProvider) {
        viewModelScope.launch {
            val ds = datasets.value
            val remotes = withContext(Dispatchers.IO) { graph.stb.remotes(p.id) }.filter { it.isSupported(ds.protocols) }
            val distinct = remotes.distinctBy { testCode(it) }
            beginTest(p.title, distinct.map { Candidate(CatalogRef("stb", p.id, it.remoteId), tv = it) })
        }
    }

    private fun beginTest(brand: String, candidates: List<Candidate>) {
        if (candidates.isEmpty()) { toast("No usable codes"); return }
        wizard.update { it.copy(step = WizardStep.Test, brandName = brand, candidates = candidates, index = 0) }
    }

    private fun testKey(r: TvRemote) = StandardKeys.TEST_KEYS.firstOrNull { it in r.buttons } ?: r.buttons.keys.first()
    private fun testCode(r: TvRemote) = r.buttons.getValue(testKey(r)) + "/" + r.protocolName + r.bitLength

    fun sendTest() {
        val c = wizard.value.candidates.getOrNull(wizard.value.index) ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val result = when {
                c.tv != null -> controller.send(c.tv.irCode(testKey(c.tv))!!, toggleKey = "wizard")
                c.ac != null -> controller.testAc(c.ac, TEST_AC)
                else -> return@launch
            }
            report(result)
        }
    }

    fun nextCandidate() = wizard.update { it.copy(index = (it.index + 1) % it.candidates.size.coerceAtLeast(1)) }

    fun confirmWorked() {
        wizard.update {
            val suffix = when (it.type) { DeviceType.AC -> "AC"; DeviceType.SET_TOP_BOX -> "Box"; else -> "TV" }
            it.copy(step = WizardStep.Name, name = "${it.brandName} $suffix")
        }
    }

    fun setName(name: String) = wizard.update { it.copy(name = name) }

    fun saveWizardDevice() {
        val w = wizard.value
        val c = w.candidates.getOrNull(w.index) ?: return
        val name = w.name.trim().ifEmpty { w.brandName }
        val id = graph.store.newId(name)
        val device = when {
            c.ac != null -> SavedDevice(
                id = id, name = name, type = DeviceType.AC, brand = w.brandName, catalog = c.ref,
                acState = AcState(power = false, mode = "cool", temperature = c.ac.defaultTemperature.coerceIn(c.ac.temperatures)),
            )
            else -> {
                val r = c.tv!!
                SavedDevice(
                    id = id, name = name, type = w.type ?: DeviceType.TV, brand = w.brandName, catalog = c.ref,
                    buttons = r.buttons.keys.sortedBy(StandardKeys::sortKey).map { SavedButton(it, r.irCode(it)!!) },
                )
            }
        }
        graph.store.add(device)
        wizard.value = WizardState()
        route.value = Route.Device(device.id)
    }

    companion object {
        private val TEST_AC = AcState(power = true, mode = "cool", temperature = 24, fan = "auto")
    }
}
