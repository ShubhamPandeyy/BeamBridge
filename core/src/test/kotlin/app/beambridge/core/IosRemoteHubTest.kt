package app.beambridge.core

import app.beambridge.core.atv.*
import app.beambridge.core.data.*
import app.beambridge.core.ir.IrCode
import app.beambridge.core.server.BridgeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.test.*

class IosRemoteHubTest {
    private val raw = IrCode.Raw(38000, listOf(9000, 4500, 560, 560))
    private val store = InMemoryDeviceStore(listOf(
        SavedDevice("tv", "TCL TV", DeviceType.TV, buttons = listOf(SavedButton("UP", raw, true), SavedButton("POWER", raw, true)), createdAt = 1),
        SavedDevice("ac", "Bedroom AC", DeviceType.OTHER, buttons = listOf(SavedButton("COOL", raw, true)), createdAt = 2),
        SavedDevice("old", "Old box", DeviceType.OTHER, archived = true, createdAt = 3),
    ))
    private val settings = InMemorySettingsStore()
    private val emitter = FakeEmitter()
    private val controller = BridgeController(store, emitter) { Datasets.EMPTY }
    private val published = java.util.concurrent.ConcurrentHashMap<String, List<MdnsService>>()
    private val publisher = object : MdnsPublisher {
        override fun publish(key: String, services: List<MdnsService>) { published[key] = services }
        override fun withdraw(key: String) { published.remove(key) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = Files.createTempFile("ios", ".json").toFile().apply { delete() }

    @AfterTest fun tearDown() { scope.cancel(); file.delete() }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) { if (System.currentTimeMillis() > end) fail("timed out: $what"); Thread.sleep(20) }
    }

    @Test fun oneAppleTvPerDeviceAndGesturesSendMappedButtons() {
        val hub = IosRemoteHub(store, settings, controller, file, publisher, scope)
        hub.start()
        try {
            waitFor("two Apple TVs") { hub.state.value.entries.size == 2 }
            assertEquals(setOf("tv", "ac"), published.keys, "archived devices stay hidden")
            assertEquals("TCL TV", published["tv"]!!.first().name)
            assertTrue(Regex("\\d{4}").matches(settings.settings.value.iosPin), "a random PIN is made on first start")

            // Not mapped yet: nothing is sent.
            hub.handleGesture("tv", IosGesture.SWIPE_UP)
            Thread.sleep(100); assertEquals(0, emitter.sent.size)

            hub.setMapping("tv", IosGesture.SWIPE_UP, "UP")
            hub.handleGesture("tv", IosGesture.SWIPE_UP)
            waitFor("IR sent and reported") { hub.state.value.lastEvent == "Swipe up → UP on TCL TV" }
            assertEquals(1, emitter.sent.size)

            // Learning captures instead of sending, only for the device being set up.
            hub.startLearning("tv")
            hub.handleGesture("tv", "ios_up")
            assertEquals("ios_up", hub.learn.value?.gesture)
            hub.handleGesture("tv", IosGesture.SWIPE_UP)
            Thread.sleep(100); assertEquals(1, emitter.sent.size)
            hub.stopLearning()

            // Hiding a device withdraws its Apple TV; renaming republishes under the new name with the same identity.
            val idBefore = published["tv"]!!.first().txt["rpBA"]
            settings.update { it.copy(iosHidden = listOf("ac")) }
            waitFor("ac withdrawn") { "ac" !in published.keys }
            store.update("tv") { it.copy(name = "Living room TV") }
            waitFor("renamed") { published["tv"]?.first()?.name == "Living room TV" }
            assertEquals(idBefore, published["tv"]!!.first().txt["rpBA"])
        } finally { hub.stop() }
        assertTrue(published.isEmpty(), "stop withdraws everything")
    }

    @Test fun identitiesAndPairingsSurviveRestartAndCanBeForgotten() {
        val hub = IosRemoteHub(store, settings, controller, file, publisher, scope)
        hub.start(); waitFor("started") { hub.state.value.entries.size == 2 }
        val id = published["tv"]!!.first().txt["rpBA"]
        hub.stop()

        val again = IosRemoteHub(store, settings, controller, file, publisher, scope)
        again.start(); waitFor("restarted") { again.state.value.entries.size == 2 }
        assertEquals(id, published["tv"]!!.first().txt["rpBA"], "same Apple TV identity after a restart")
        again.forgetDevice("tv")
        again.forgetPairings()
        assertEquals(0, again.state.value.pairedPhones)
        again.stop()
    }
}
