package app.beambridge.core.server

import app.beambridge.core.data.*

/** Platform callbacks (implemented by the Android service, faked in tests). */
interface BridgeHooks {
    /** Settings that affect the socket (port, address) changed: rebind. */
    fun restartServer() {}
    /** Address/port the server is currently bound to, so "is it free?" checks can ignore our own socket. */
    fun boundTo(): Pair<String?, Int>? = null
}

/** Everything the HTTP API and the app share. */
class Bridge(
    val controller: BridgeController,
    val pairing: PairingService,
    val finder: Finder,
    val settings: SettingsStore,
    val endpoints: Endpoints,
    val hooks: BridgeHooks = object : BridgeHooks {},
) {
    val store: DeviceStore get() = controller.store

    /** Validates and stores settings; restarts the socket if address or port changed. Returns an error message or null. */
    fun applySettings(next: BridgeSettings): String? {
        next.validate()?.let { return it }
        val old = settings.settings.value
        if (next.port != old.port || next.bindAddress != old.bindAddress) {
            val host = Net.resolve(next.bindAddress)
            if (host == "") return "${next.bindAddress} is not on this phone right now"
            if (!isFree(host, next.port)) return "Port ${next.port} is busy on ${host ?: "all interfaces"}"
        }
        settings.update { next }
        if (next.port != old.port || next.bindAddress != old.bindAddress) hooks.restartServer()
        return null
    }

    fun isFree(host: String?, port: Int): Boolean {
        val bound = hooks.boundTo()
        if (bound != null && bound.second == port && (bound.first == host || bound.first == null || host == null)) return true
        return Net.isAvailable(host, port)
    }

    // ---- device editing (shared by app and web) ----

    fun setArchived(id: String, archived: Boolean) = store.update(id) { it.copy(archived = archived) }

    fun setHidden(id: String, button: String, hidden: Boolean) = store.update(id) { d ->
        val rest = d.hidden.filterNot { it.equals(button, true) }
        d.copy(hidden = if (hidden) rest + button else rest)
    }

    /** Remote layout for a device's visible buttons (AC actions live in the AC panel, not the grid). */
    fun layout(d: SavedDevice): RemoteLayout {
        val exclude = if (d.type == DeviceType.AC) AcCodec.actions(controller.acRemote(d)).toSet() else emptySet()
        return RemoteLayout.of(controller.visibleButtons(d), d.grid, exclude)
    }

    /** Puts [button] into extras-grid cell [cell], swapping with whatever was there. */
    fun moveButton(id: String, button: String, cell: Int) = store.update(id) { d ->
        val cells = layout(d).extras.toMutableList()
        val from = cells.indexOf(button)
        if (from < 0 || cell < 0) return@update d
        while (cells.size <= cell) cells.add("")
        cells[from] = cells[cell]; cells[cell] = button
        d.copy(grid = cells.dropLastWhile { it.isEmpty() })
    }
}
