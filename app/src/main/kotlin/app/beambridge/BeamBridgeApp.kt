package app.beambridge

import android.app.Application
import app.beambridge.core.data.Datasets
import app.beambridge.core.data.DeviceStore
import app.beambridge.core.data.Finder
import app.beambridge.core.data.JsonFileDeviceStore
import app.beambridge.core.data.JsonFileSettingsStore
import app.beambridge.core.data.PairingService
import app.beambridge.core.server.Bridge
import app.beambridge.core.server.BridgeController
import app.beambridge.core.server.BridgeHooks
import app.beambridge.core.server.Endpoints
import app.beambridge.core.server.Net
import app.beambridge.data.StbRepository
import app.beambridge.service.AndroidIrEmitter
import app.beambridge.service.BridgeRuntime
import app.beambridge.service.BridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Hand-wired dependencies shared by the UI, the foreground service and the HTTP API. */
class Graph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = JsonFileSettingsStore(File(app.filesDir, "settings.json"))
    val store: DeviceStore = JsonFileDeviceStore(File(app.filesDir, "devices.json"))
    val datasets = MutableStateFlow(Datasets.EMPTY)
    val emitter = AndroidIrEmitter(app)
    val stb = StbRepository(app)

    /** Base URL for "Copy URL Endpoint": the live server address, or where it would listen with current settings. */
    val endpoints = Endpoints {
        BridgeRuntime.status.value.url ?: settings.settings.value.let { s ->
            Net.publicHost(Net.resolve(s.bindAddress)?.takeIf { it.isNotEmpty() })?.let { "http://$it:${s.port}" }
        }
    }
    val controller = BridgeController(store, emitter) { datasets.value }
    val pairing = PairingService({ datasets.value }, { stb }, controller)
    val finder = Finder(controller, { datasets.value }, { stb }, scope)

    val bridge = Bridge(controller, pairing, finder, settings, endpoints, object : BridgeHooks {
        override fun restartServer() { if (BridgeRuntime.status.value.running) BridgeService.start(app) }
        override fun boundTo(): Pair<String?, Int>? = BridgeRuntime.bound
    })

    init {
        scope.launch(Dispatchers.IO) { datasets.value = Datasets.load { app.assets.open(it) } }
    }
}

class BeamBridgeApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}
