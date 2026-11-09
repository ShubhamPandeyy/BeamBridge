package app.beambridge.service

import kotlinx.coroutines.flow.MutableStateFlow

data class ServerStatus(val running: Boolean = false, val url: String? = null, val error: String? = null)

/** Process-wide server state, written by [BridgeService] and observed by the UI. */
object BridgeRuntime {
    val status = MutableStateFlow(ServerStatus())
    /** Host (null = all interfaces) and port the socket is bound to while running. */
    @Volatile var bound: Pair<String?, Int>? = null
}
