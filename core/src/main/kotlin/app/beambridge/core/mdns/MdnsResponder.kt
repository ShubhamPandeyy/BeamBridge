package app.beambridge.core.mdns

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.DatagramPacket
import java.net.NetworkInterface

/**
 * Answers multicast DNS questions for `<name>.local` with this phone's Wi-Fi address, so `http://remote.local:6969`
 * keeps working when the router hands the phone a new IP (the owner of this class is then simply restarted with the new one).
 *
 * Before answering it asks the network whether someone else already uses the name; if so it moves to `<name>-2`, `-3`, ...
 * Only IPv4 (A records) is answered. Needs a Wi-Fi multicast lock on Android (held by the service).
 */
class MdnsResponder(
    private val baseName: String,
    private val ip: Inet4Address,
    private val onChange: () -> Unit = {},
) {
    enum class State { STARTING, READY, FAILED }

    @Volatile var state = State.STARTING; private set
    /** The name actually claimed (differs from the asked-for one after a clash). */
    @Volatile var name: String = baseName; private set
    @Volatile var error: String? = null; private set
    /** null until known; true once we heard our own announcement come back through the network socket. */
    @Volatile var loopbackOk: Boolean? = null; private set

    val renamed get() = name != baseName
    val host get() = "$name.local"

    @Volatile private var running = false
    private var socket: MulticastSocket? = null

    fun start() { Thread({ run() }, "mdns-responder").apply { isDaemon = true }.start() }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    /** The answer to give for an incoming packet, or null when it is not a question about our name. */
    fun reply(packet: ByteArray, len: Int = packet.size): ByteArray? {
        val m = Dns.parse(packet, len) ?: return null
        if (m.isResponse) return null
        val ask = m.questions.any { it.name.equals(host, true) && (it.type == Dns.TYPE_A || it.type == Dns.TYPE_ANY) }
        return if (ask) Dns.response(host, ip.address) else null
    }

    private fun fail(msg: String) { error = msg; state = State.FAILED; onChange() }

    private fun send(s: MulticastSocket, data: ByteArray) {
        runCatching { s.send(DatagramPacket(data, data.size, InetAddress.getByName(GROUP), PORT)) }
    }

    private fun run() {
        try {
            val nif = NetworkInterface.getByInetAddress(ip)
            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT))
            if (nif != null) s.networkInterface = nif
            s.timeToLive = 255
            s.soTimeout = 200
            socket = s
            val group = InetAddress.getByName(GROUP)
            if (nif != null) s.joinGroup(InetSocketAddress(group, PORT), nif) else @Suppress("DEPRECATION") s.joinGroup(group)
            running = true

            var candidate = baseName
            var n = 1
            while (running && taken(s, candidate)) {
                if (++n > 9) { fail("No free name like $baseName.local on this network"); return }
                candidate = "$baseName-$n"
            }
            if (!running) return
            name = candidate
            state = State.READY
            onChange()
            val ann = Dns.response(host, ip.address)
            send(s, ann)
            val readyAt = System.currentTimeMillis()
            var sentSecond = false
            val buf = ByteArray(1500)
            while (running) {
                val now = System.currentTimeMillis()
                if (!sentSecond && now - readyAt > 1000) { send(s, ann); sentSecond = true }
                if (loopbackOk == null && now - readyAt > 3000) { loopbackOk = false; onChange() }
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val out = reply(buf, p.length)
                if (out != null) send(s, out) else if (loopbackOk == null && heardSelf(buf, p.length)) { loopbackOk = true; onChange() }
            }
        } catch (e: Exception) {
            if (running || state == State.STARTING) fail(e.message ?: "Could not start the name responder")
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun heardSelf(buf: ByteArray, len: Int): Boolean {
        val m = Dns.parse(buf, len) ?: return false
        return m.isResponse && m.answers.any { it.type == Dns.TYPE_A && it.name.equals(host, true) && it.data.contentEquals(ip.address) }
    }

    /** True if another device on the network answers for [candidate].local with a different address. */
    private fun taken(s: MulticastSocket, candidate: String): Boolean {
        send(s, Dns.query("$candidate.local", Dns.TYPE_ANY, unicast = true))
        val end = System.currentTimeMillis() + 700
        val buf = ByteArray(1500)
        while (running && System.currentTimeMillis() < end) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
            val m = Dns.parse(buf, p.length) ?: continue
            if (m.isResponse && m.answers.any { it.type == Dns.TYPE_A && it.name.equals("$candidate.local", true) && !it.data.contentEquals(ip.address) }) return true
        }
        return false
    }

    companion object {
        const val GROUP = "224.0.0.251"
        const val PORT = 5353
    }
}
