package app.beambridge.core.server

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

data class BindOption(val address: String, val label: String)

object Net {
    /** Site-local IPv4 addresses by interface, best (Wi-Fi) first. */
    fun localAddresses(): List<BindOption> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
                    .map { BindOption(it.hostAddress, label(nif.name)) to rank(nif.name) }
            }
            .sortedBy { it.second }.map { it.first }.distinctBy { it.address }
    }.getOrElse { emptyList() }

    fun localIpv4(): String? = localAddresses().firstOrNull()?.address

    /** Every choice offered in settings: auto, each local address, all interfaces. */
    fun bindOptions(): List<BindOption> =
        listOf(BindOption("auto", "Automatic (Wi-Fi)")) + localAddresses() + BindOption("0.0.0.0", "All interfaces")

    /** Host to pass to the server socket: null = all interfaces. Returns "" when a fixed address is not present now. */
    fun resolve(bindAddress: String): String? = when (bindAddress) {
        "auto" -> localIpv4()
        "0.0.0.0", "" -> null
        else -> if (localAddresses().any { it.address == bindAddress }) bindAddress else ""
    }

    /** Address shown in URLs for a resolved bind host. */
    fun publicHost(resolved: String?): String? = resolved ?: localIpv4()

    /** True if nothing else is listening on address:port. */
    fun isAvailable(address: String?, port: Int): Boolean = runCatching {
        ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(if (address == null) InetSocketAddress(port) else InetSocketAddress(InetAddress.getByName(address), port))
        }
        true
    }.getOrDefault(false)

    private fun rank(name: String) = when {
        name.startsWith("wlan") -> 0
        name.startsWith("ap") || name.startsWith("swlan") -> 1
        name.startsWith("eth") -> 2
        else -> 3
    }

    private fun label(name: String) = when {
        name.startsWith("wlan") -> "Wi-Fi"
        name.startsWith("ap") || name.startsWith("swlan") -> "Hotspot"
        name.startsWith("eth") -> "Ethernet"
        name.startsWith("rmnet") || name.startsWith("ccmni") -> "Mobile"
        else -> name
    }
}
