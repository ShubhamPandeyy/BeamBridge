package app.beambridge.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [url] is the address to use (the `.local` name when it works, else the IP); [ipUrl] is always the plain IP address
 * as a backup; [notice] is a short heads-up about the name (renamed after a clash, not resolving, ...).
 */
data class ServerStatus(
    val running: Boolean = false, val url: String? = null, val error: String? = null,
    val ipUrl: String? = null, val notice: String? = null,
)

/** Process-wide server state, written by [BridgeService] and observed by the UI. */
object BridgeRuntime {
    val status = MutableStateFlow(ServerStatus())
    /** Host (null = all interfaces) and port the socket is bound to while running. */
    @Volatile var bound: Pair<String?, Int>? = null
}
