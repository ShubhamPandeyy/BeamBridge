package app.beambridge.core

import app.beambridge.core.data.*
import app.beambridge.core.ir.*
import app.beambridge.core.server.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.Base64
import kotlin.test.*

internal class FakeEmitter : IrEmitter {
    val sent = java.util.Collections.synchronizedList(mutableListOf<IrSignal>())
    override val isAvailable = true
    override fun transmit(signal: IrSignal) { sent += signal }
}

private val realDatasets: Datasets by lazy {
    val dir = listOf("../app/src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
    Datasets.load { name -> File(dir, name).takeIf { it.exists() }?.inputStream() }
}

class ServerTest {
    private val emitter = FakeEmitter()
    private val store = InMemoryDeviceStore(listOf(
        SavedDevice("tv_living_room", "Living room", DeviceType.TV, "Samsung", buttons = listOf(
            SavedButton("POWER", IrCode.Hex("SAMSUNG_NEC", "0xe0e040bf", 32, 6)),
            SavedButton("MY_RAW", IrCode.Raw(38000, listOf(9000, 4500, 560, 560)), custom = true),
        )),
    ))
    private val port = ServerSocket(0).use { it.localPort }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = InMemorySettingsStore()
    private val controller = BridgeController(store, emitter) { realDatasets }
    private val bridge = Bridge(
        controller,
        PairingService({ realDatasets }, { null }, controller),
        Finder(controller, { realDatasets }, { null }, scope),
        settings,
        Endpoints { "http://192.168.1.50:8080" },
    )
    private val server = BridgeHttpServer("127.0.0.1", port, bridge)

    @BeforeTest fun up() = server.startServer()
    @AfterTest fun down() { server.stop(); scope.cancel() }

    private fun call(method: String, path: String, body: String? = null, type: String = "application/x-www-form-urlencoded", auth: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        auth?.let { c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(it.toByteArray())) }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = ((if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)).decodeToString()
        return code to text
    }

    @Test fun devicesListsButtonsWithUrls() {
        val (code, body) = call("GET", "/api/devices")
        assertEquals(200, code)
        assertTrue("http://192.168.1.50:8080/api/blast?device=tv_living_room&button=POWER" in body.replace("\\/", "/"), body)
    }

    @Test fun blastGetAndPost() {
        assertEquals(200, call("GET", "/api/blast?device=tv_living_room&button=POWER").first)
        assertEquals(200, call("POST", "/api/blast?device=tv_living_room&button=power").first)
        assertEquals(2, emitter.sent.size)
        assertEquals(404, call("GET", "/api/blast?device=nope&button=POWER").first)
        assertEquals(404, call("GET", "/api/blast?device=tv_living_room&button=NOPE").first)
        assertEquals(400, call("GET", "/api/blast?device=tv_living_room").first)
    }

    @Test fun rawForms() {
        assertEquals(200, call("POST", "/api/raw", "frequency=38000&pattern=9000,4500,560,560").first)
        assertEquals(200, call("POST", "/api/raw", """{"frequency":38000,"pattern":[9000,4500,560]}""", "application/json").first)
        assertEquals(200, call("POST", "/api/raw", "protocol=NEC&code=0x00FF00FF&bits=32").first)
        assertEquals(200, call("POST", "/api/raw", "ir=RCA 0xF2A0D5").first)
        assertEquals(400, call("POST", "/api/raw", "frequency=38000").first)
        assertEquals(4, emitter.sent.size)
        assertEquals(listOf(9000, 4500, 560, 560), emitter.sent[0].pattern.toList())
    }

    @Test fun webUiServed() {
        val (code, body) = call("GET", "/")
        assertEquals(200, code)
        assertTrue("BeamBridge" in body)
    }

    @Test fun customDeviceButtonsHideArchiveDelete() {
        val (c1, b1) = call("POST", "/api/devices", "type=other&name=Projector&button=Power&ir=NEC 0x20DF10EF 32")
        assertEquals(201, c1, b1)
        assertTrue("\"id\":\"projector\"" in b1)
        assertEquals(201, call("POST", "/api/devices/projector/buttons", "name=input&ir=9000,4500,560,560").first)
        assertEquals(200, call("GET", "/api/blast?device=projector&button=INPUT").first)
        assertEquals(400, call("POST", "/api/devices/projector/buttons", "name=bad").first)
        assertTrue("\"hidden\":true" in call("POST", "/api/devices/projector/buttons/INPUT", "hidden=true").second)
        call("POST", "/api/devices/projector/buttons", "name=a&ir=0x1")
        call("POST", "/api/devices/projector/buttons", "name=b&ir=0x2")
        call("POST", "/api/devices/projector/buttons/INPUT", "hidden=false")
        val moved = call("POST", "/api/devices/projector/buttons/B", "cell=6").second
        assertTrue("\"extras\":[\"INPUT\",\"A\",\"\",\"\",\"\",\"\",\"B\"]" in moved, moved)
        assertTrue("\"archived\":true" in call("POST", "/api/devices/projector", "archived=true").second)
        assertEquals(200, call("DELETE", "/api/devices/projector/buttons/INPUT").first)
        assertEquals(200, call("DELETE", "/api/devices/projector").first)
        assertNull(store.get("projector"))
    }

    @Test fun pairingThroughApi() {
        val (_, brands) = call("GET", "/api/catalog/brands?type=tv&q=tcl")
        assertTrue("\"name\":\"TCL\"" in brands, brands)
        val (_, cands) = call("GET", "/api/catalog/candidates?type=tv&brand=22&on=false")
        assertEquals(4, Regex("\"index\"").findAll(cands).count(), cands) // 3 dataset remotes + the RCA remote from the TCL repo
        assertTrue("\"test_key\":\"VOL_UP\"" in call("GET", "/api/catalog/candidates?type=tv&brand=22&on=true").second)
        assertEquals(200, call("POST", "/api/catalog/test", "type=tv&brand=22&index=3&on=false").first)
        val (c, body) = call("POST", "/api/devices", "type=tv&brand=22&index=3&name=Bedroom TV")
        assertEquals(201, c, body)
        assertTrue("YOUTUBE" in body)
    }

    @Test fun passwordAuth() {
        assertEquals(200, call("POST", "/api/settings", "auth=password&username=me&password=secret").first)
        assertEquals(401, call("GET", "/api/devices").first)
        assertEquals(401, call("GET", "/api/devices", auth = "me:wrong").first)
        assertEquals(200, call("GET", "/api/devices", auth = "me:secret").first)
        val (_, s) = call("GET", "/api/settings", auth = "me:secret")
        assertFalse("secret" in s, "password must never be returned")
        assertEquals(400, call("POST", "/api/settings", "password=x", auth = "me:secret").first) // too short
    }

    @Test fun networkCheck() {
        val (code, body) = call("GET", "/api/network/check?address=0.0.0.0&port=$port")
        assertEquals(200, code)
        assertTrue("\"available\":false" in body, body) // our own test server holds this port
    }

    @Test fun finderRunsStopsAndAccepts() {
        store.add(SavedDevice("mystery", "Mystery TV", DeviceType.OTHER))
        call("POST", "/api/finder/start", "key=POWER&interval=300")
        Thread.sleep(1100)
        val (_, stopped) = call("POST", "/api/finder/stop")
        assertTrue("\"running\":false" in stopped, stopped)
        val sent = Regex("\"sent\":(\\d+)").find(stopped)!!.groupValues[1].toInt()
        assertTrue(sent >= 2, stopped)
        assertTrue("\"window\":[{" in stopped)
        val idx = Regex("\"index\":(\\d+)").find(stopped)!!.groupValues[1]
        assertEquals(200, call("POST", "/api/finder/test", "index=$idx").first)
        assertEquals(200, call("POST", "/api/finder/accept", "device=mystery&index=$idx&name=power").first)
        assertNotNull(store.get("mystery")!!.button("POWER"))
    }

    // ---- round 4 ----

    @Test fun caseInsensitiveDeleteAnd404ForMissingButton() {
        call("POST", "/api/devices", "type=other&name=Living Room&button=Power&ir=NEC 0x20DF10EF 32")
        assertEquals(404, call("DELETE", "/api/devices/living_room/buttons/NOPE").first)
        assertEquals(200, call("DELETE", "/api/devices/LIVING_ROOM/buttons/power").first)
        assertTrue(store.get("living_room")!!.buttons.isEmpty())
        assertEquals(200, call("DELETE", "/api/devices/LIVING_ROOM").first)
        assertNull(store.get("living_room"))
    }

    @Test fun invalidSettingsAreRejected() {
        assertEquals(400, call("POST", "/api/settings", "port=abc").first)
        assertEquals(400, call("POST", "/api/settings", "auth=admin").first)
        assertEquals(400, call("POST", "/api/settings", "wake_lock=maybe").first)
        assertEquals(400, call("POST", "/api/settings", "bind_address=10.9.9.9").first)
        assertEquals(8080, settings.settings.value.port)
    }

    @Test fun historyRecordsSourceAndResult() {
        call("GET", "/api/blast?device=tv_living_room&button=power")
        call("GET", "/api/blast?device=tv_living_room&button=NOPE")
        val h = call("GET", "/api/history?limit=5").second
        assertTrue("\"button\":\"POWER\",\"source\":\"api\",\"ok\":true" in h, h)
        assertTrue("\"ok\":false" in h, h)
        assertEquals(400, call("GET", "/api/history?limit=0").first)
    }

    @Test fun scenesSaveRunAndValidate() {
        assertEquals(400, call("POST", "/api/scenes", "name=Bad&steps=tv_living_room:NOPE").first)
        val (c, b) = call("POST", "/api/scenes", """{"name":"Movie night","steps":[{"device":"tv_living_room","button":"POWER"},{"wait":20},{"device":"tv_living_room","button":"my_raw"}]}""", "application/json")
        assertEquals(201, c, b)
        val before = emitter.sent.size
        val run = call("POST", "/api/scene?name=movie%20night").second
        assertTrue("\"status\":\"ok\"" in run, run)
        assertEquals(before + 2, emitter.sent.size)
        assertEquals(200, call("DELETE", "/api/scenes/movie_night").first)
    }

    @Test fun toggleOnlyFlipsAfterSuccess() {
        val failing = object : IrEmitter { override val isAvailable = true; var fail = true; val sent = mutableListOf<IrSignal>()
            override fun transmit(signal: IrSignal) { if (fail) error("busy"); sent += signal } }
        val st = InMemoryDeviceStore(listOf(SavedDevice("p", "P", DeviceType.OTHER, buttons = listOf(SavedButton("A", IrCode.Hex("RC5", "0x0C", 12, toggleMask = "0x800"))))))
        val c = BridgeController(st, failing) { realDatasets }
        assertTrue(c.blast("p", "A") is SendResult.Failed)
        failing.fail = false
        c.blast("p", "A"); c.blast("p", "A")
        assertNotEquals(failing.sent[0].pattern.toList(), failing.sent[1].pattern.toList(), "toggle must alternate between successful sends")
        val ref = FakeEmitter(); val c2 = BridgeController(InMemoryDeviceStore(st.devices.value), ref) { realDatasets }
        c2.blast("p", "A")
        assertEquals(ref.sent[0].pattern.toList(), failing.sent[0].pattern.toList(), "a failed send must not consume a toggle phase")
    }
}
