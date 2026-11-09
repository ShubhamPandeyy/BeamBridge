package app.beambridge.service

import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class ServerStatus(val running: Boolean = false, val url: String? = null, val error: String? = null)

/** Process-wide server state, written by [BridgeService] and observed by the UI. */
object BridgeRuntime {
    val status = MutableStateFlow(ServerStatus())
}

object NetUtil {
    /** Best LAN IPv4 address: Wi-Fi first, then hotspot / ethernet, then any site-local address. */
    fun localIpv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { nif.name to it.hostAddress } }
        fun rank(name: String) = when {
            name.startsWith("wlan") -> 0
            name.startsWith("ap") || name.startsWith("swlan") -> 1
            name.startsWith("eth") -> 2
            else -> 3
        }
        candidates.minByOrNull { rank(it.first) }?.second
    }.getOrNull()
}
