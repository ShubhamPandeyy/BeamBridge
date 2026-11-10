package app.beambridge.core

import app.beambridge.core.data.*
import app.beambridge.core.server.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test

/** Manual preview of the web UI with the real datasets: `gradle :core:test -Pdemo` serves http://127.0.0.1:8099 for 10 minutes. */
class DemoServer {
    @Test fun serve() {
        if (System.getenv("BEAMBRIDGE_DEMO") == null) return
        val dir = File("../app/src/main/assets")
        val ds = Datasets.load { File(dir, it).takeIf(File::exists)?.inputStream() }
        val store = InMemoryDeviceStore()
        val controller = BridgeController(store, FakeEmitter()) { ds }
        val pairing = PairingService({ ds }, controller)
        val tcl = pairing.candidates(DeviceType.TV, 22).last()
        pairing.create(DeviceType.TV, tcl, "Living room TV", "TCL")
        val lgAc = ds.ac.manufacturers.first { it.name.startsWith("LG") }
        pairing.create(DeviceType.AC, pairing.candidates(DeviceType.AC, lgAc.id).first(), "Bedroom AC", "LG")
        val bridge = Bridge(controller, pairing, Finder(controller, { ds }, CoroutineScope(Dispatchers.Default)),
            InMemorySettingsStore(), Endpoints { "http://192.168.1.50:8080" })
        BridgeHttpServer("127.0.0.1", 8099, bridge).startServer()
        Thread.sleep(600_000)
    }
}
