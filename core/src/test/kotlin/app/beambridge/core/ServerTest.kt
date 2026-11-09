package app.beambridge.core

import app.beambridge.core.data.*
import app.beambridge.core.ir.*
import app.beambridge.core.server.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.test.*

private class FakeEmitter : IrEmitter {
    val sent = mutableListOf<IrSignal>()
    override val isAvailable = true
    override fun transmit(signal: IrSignal) { sent += signal }
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
    private val controller = BridgeController(store, emitter) { Datasets.EMPTY }
    private val server = BridgeHttpServer("127.0.0.1", port, controller, Endpoints { "http://192.168.1.50:8080" })

    @BeforeTest fun up() = server.startServer()
    @AfterTest fun down() = server.stop()

    private fun call(method: String, path: String, body: String? = null, type: String = "application/x-www-form-urlencoded"): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).readBytes().decodeToString()
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
        assertEquals(400, call("POST", "/api/raw", "frequency=38000").first)
        assertEquals(3, emitter.sent.size)
        assertEquals(listOf(9000, 4500, 560, 560), emitter.sent[0].pattern.toList())
    }

    @Test fun webUiServed() {
        val (code, body) = call("GET", "/")
        assertEquals(200, code)
        assertTrue("BeamBridge" in body)
    }
}
