package app.beambridge

import android.app.Application
import app.beambridge.core.data.Datasets
import app.beambridge.core.data.DeviceStore
import app.beambridge.core.data.JsonFileDeviceStore
import app.beambridge.core.server.BridgeController
import app.beambridge.core.server.Endpoints
import app.beambridge.data.AppSettings
import app.beambridge.data.StbRepository
import app.beambridge.service.AndroidIrEmitter
import app.beambridge.service.BridgeRuntime
import app.beambridge.service.NetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Hand-wired dependencies shared by the UI and the foreground service. */
class Graph(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(app)
    val store: DeviceStore = JsonFileDeviceStore(File(app.filesDir, "devices.json"))
    val datasets = MutableStateFlow(Datasets.EMPTY)
    val emitter = AndroidIrEmitter(app)
    val stb = StbRepository(app)

    /** Base URL shown for "Copy URL Endpoint": the live server address, or the phone's IP + configured port. */
    val endpoints = Endpoints {
        BridgeRuntime.status.value.url ?: NetUtil.localIpv4()?.let { "http://$it:${settings.port.value}" }
    }
    val controller = BridgeController(store, emitter) { datasets.value }

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
