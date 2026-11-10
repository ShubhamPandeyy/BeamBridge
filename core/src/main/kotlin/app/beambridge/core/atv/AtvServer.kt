package app.beambridge.core.atv

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A Bonjour (mDNS) record the platform must publish so iPhones can find a fake Apple TV. */
data class MdnsService(val type: String, val name: String, val port: Int, val txt: Map<String, String>)

/**
 * One fake Apple TV: the Companion port (where the remote talks), plus the MRP and AirPlay ports iOS probes during
 * discovery. All ports are picked by the OS and published through [services]; publishing is the platform's job
 * (Android: NsdManager).
 */
class AtvServer(
    val identity: AtvIdentity,
    private val pin: () -> String,
    private val pairings: AtvPairings,
    private val requirePairing: () -> Boolean,
    private val listener: AtvSessionListener,
    private val bindAddress: InetAddress? = null,
) {
    private val sockets = CopyOnWriteArrayList<ServerSocket>()
    private val connections = CopyOnWriteArrayList<Pair<Socket, CompanionSession>>()
    private val sessionIds = ConcurrentHashMap<String, Long>()
    @Volatile private var running = false

    var companionPort = 0; private set
    var mrpPort = 0; private set
    var airplayPort = 0; private set

    /** True while at least one iPhone has an open, verified remote session. */
    val hasRemote: Boolean get() = connections.any { it.second.established }

    fun start(): AtvServer {
        check(!running) { "already started" }
        running = true
        try {
            companionPort = listen("atv-companion") { s -> handleCompanion(s) }
            mrpPort = listen("atv-mrp") { s -> drain(s) }
            airplayPort = listen("atv-airplay") { s -> airplayOk(s) }
        } catch (e: Exception) { stop(); throw e }
        return this
    }

    fun stop() {
        running = false
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        connections.forEach { runCatching { it.first.close() } }
        connections.clear()
    }

    private fun listen(name: String, handler: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 16, bindAddress)
        sockets += ss
        Thread({
            while (running && !ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                Thread({ runCatching { handler(s) }; runCatching { s.close() } }, "$name-conn").apply { isDaemon = true }.start()
            }
        }, name).apply { isDaemon = true }.start()
        return ss.localPort
    }

    private fun drain(s: Socket) { val buf = ByteArray(4096); val i = s.getInputStream(); while (i.read(buf) >= 0) Unit }

    private fun airplayOk(s: Socket) {
        s.soTimeout = 5000
        runCatching { s.getInputStream().read(ByteArray(4096)) }
        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
    }

    private class Closed : Exception()

    private fun handleCompanion(socket: Socket) {
        val peerIp = socket.inetAddress?.hostAddress ?: "?"
        val peer = "$peerIp:${socket.port}"
        // A new connection from the same phone replaces its half-open older ones (iOS reconnects often).
        connections.filter { it.first.inetAddress?.hostAddress == peerIp && !it.second.established }.forEach { runCatching { it.first.close() } }
        val session = CompanionSession(socket.getOutputStream(), identity, pin, pairings, requirePairing, listener, peer)
        session.onSystemInfo = { device ->
            connections.filter { it.second !== session && it.second.clientDeviceId == device }.forEach { runCatching { it.first.close() } }
            sessionIds.getOrPut(device) { session.remoteSessionId }
        }
        val entry = socket to session
        connections += entry
        listener.onStatus("connected", peer)
        try {
            socket.soTimeout = 500
            val input = socket.getInputStream()
            val hdr = ByteArray(4)
            while (running) {
                readFully(input, hdr, session)
                val type = hdr[0].toInt() and 0xFF
                if (!FrameType.valid(type)) break
                val len = ((hdr[1].toInt() and 0xFF) shl 16) or ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                if (len > CompanionSession.MAX_FRAME) break
                val payload = ByteArray(len)
                readFully(input, payload, session)
                session.handleFrame(type, payload, hdr.copyOf())
            }
        } catch (_: Closed) {
        } catch (_: java.net.SocketException) { // the phone hung up or we're stopping
        } catch (e: Exception) {
            listener.onStatus("error", "$peer: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connections -= entry
            listener.onStatus("disconnected", peer)
        }
    }

    /** Blocking read that survives socket timeouts (used only to enforce [CompanionSession.deadline]). */
    private fun readFully(input: InputStream, buf: ByteArray, session: CompanionSession) {
        var off = 0
        while (off < buf.size) {
            val n = try { input.read(buf, off, buf.size - off) } catch (_: SocketTimeoutException) {
                val dl = session.deadline
                if (!running || (dl != null && !session.established && System.currentTimeMillis() > dl)) throw Closed()
                continue
            }
            if (n < 0) throw Closed()
            off += n
        }
    }

    /** The three Bonjour records iOS needs, with the TXT values that unlock the remote (rpFl gates volume keys etc.). */
    fun services(): List<MdnsService> {
        val id = identity.serverIdentifier
        fun hash(prefix: String, n: Int) = MessageDigest.getInstance("SHA-256").digest((prefix + id).toByteArray()).copyOf(n)
            .joinToString("") { "%02x".format(it) }
        val uniqueId = id.replace("-", "").uppercase()
        val name = identity.name
        return listOf(
            MdnsService("_mediaremotetv._tcp", name, mrpPort, linkedMapOf(
                "Name" to name, "UniqueIdentifier" to uniqueId, "SystemBuildVersion" to "22K160",
                "LocalAirPlayReceiverPairingIdentity" to hash("airplay-identity:", 8).uppercase(),
                "ModelName" to "Apple TV", "AllowPairing" to "YES",
            )),
            MdnsService("_companion-link._tcp", name, companionPort, linkedMapOf(
                "rpMac" to "1", "rpHA" to identity.deviceId.replace(":", ""), "rpHN" to hash("rpHN:", 6).uppercase(),
                "rpVr" to SOURCE_VERSION, "rpMd" to CompanionSession.DEVICE_MODEL, "rpFl" to "0x36782",
                "rpAD" to hash("rpAD:", 6).uppercase(), "rpHI" to hash("rpHI:", 6).uppercase(), "rpBA" to identity.deviceId,
            )),
            MdnsService("_airplay._tcp", name, airplayPort, linkedMapOf(
                "deviceid" to identity.deviceId, "features" to "0x5A7FFFF7,0x1E", "flags" to "0x44",
                "model" to CompanionSession.DEVICE_MODEL, "srcvers" to SOURCE_VERSION, "vv" to "2",
                "pi" to id, "pk" to hash("pk:", 16), "name" to name,
            )),
        )
    }

    companion object { const val SOURCE_VERSION = "715.2" }
}
