package app.beambridge

import android.app.Application
import app.beambridge.core.data.CommandHistory
import app.beambridge.core.data.Datasets
import app.beambridge.core.data.JsonFileSceneStore
import app.beambridge.service.SendNotifier
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
import app.beambridge.core.atv.IosRemoteHub
import app.beambridge.service.AndroidIrEmitter
import app.beambridge.service.AndroidMdnsPublisher
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
    /** True once [datasets] holds the real catalogs. */
    val ready = MutableStateFlow(false)
    val emitter = AndroidIrEmitter(app)

    /** Base URL for "Copy URL Endpoint": the live server address, or where it would listen with current settings. */
    val endpoints = Endpoints {
        BridgeRuntime.status.value.url ?: settings.settings.value.let { s ->
            if (s.useHostName) "http://${s.hostName}.local:${s.port}"
            else Net.publicHost(Net.resolve(s.bindAddress)?.takeIf { it.isNotEmpty() })?.let { "http://$it:${s.port}" }
        }
    }
    val history = CommandHistory(File(app.filesDir, "history.json"))
    val scenes = JsonFileSceneStore(File(app.filesDir, "scenes.json"))
    val controller = BridgeController(store, emitter, history) { datasets.value }
    val pairing = PairingService({ datasets.value }, controller)
    val finder = Finder(controller, { datasets.value }, scope)

    /** One fake Apple TV per device for the iPhone's Control Center remote; runs while [IosRemoteService] is on. */
    val iosRemote = IosRemoteHub(store, settings, controller, File(app.filesDir, "ios_remote.json"), AndroidMdnsPublisher(app), scope)

    val bridge = Bridge(controller, pairing, finder, settings, endpoints, object : BridgeHooks {
        override fun restartServer() { if (BridgeRuntime.status.value.running) BridgeService.start(app) }
        override fun boundTo(): Pair<String?, Int>? = BridgeRuntime.bound
        override fun deviceDeleted(id: String) = iosRemote.forgetDevice(id)
    }, scenes, isReady = { ready.value })

    private val notifier by lazy { SendNotifier(app) }

    init {
        scope.launch(Dispatchers.IO) {
            datasets.value = Datasets.load { runCatching { app.assets.open(it) }.getOrNull() }
            ready.value = true
        }
        // Silent "Power on beamed to …" confirmation for sends you can't see (API, web, scenes, or the app in the
        // background). While the app is in front the on-screen feedback already confirms it.
        history.listener = { e -> if (settings.settings.value.notifyOnSend && !AppVisibility.foreground) notifier.show(e) }
    }
}

/** Whether an activity of ours is on screen. */
object AppVisibility { @Volatile var foreground = false }

class BeamBridgeApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}
