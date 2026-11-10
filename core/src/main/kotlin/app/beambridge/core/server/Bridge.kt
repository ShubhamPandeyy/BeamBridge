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
    val scenes: SceneStore = InMemorySceneStore(),
) {
    val store: DeviceStore get() = controller.store
    val history: CommandHistory get() = controller.history

    // ---- scenes / macros ----

    /** Saves a scene, checking every step names an existing device and button. Returns an error or the saved scene. */
    fun saveScene(id: String?, name: String, steps: List<SceneStep>): Result<Scene> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Scene needs a name"))
        if (steps.isEmpty()) return Result.failure(IllegalArgumentException("Scene needs at least one step"))
        steps.forEachIndexed { i, st ->
            if (st.isWait) { if (st.delayMs !in 1..60_000) return Result.failure(IllegalArgumentException("Step ${i + 1}: wait must be 1-60000 ms")) }
            else {
                val d = store.get(st.device) ?: return Result.failure(IllegalArgumentException("Step ${i + 1}: unknown device '${st.device}'"))
                if (controller.buttonNames(d).none { it.equals(st.button, true) } && !(d.type == DeviceType.AC && AcCodec.apply(controller.acRemote(d), d.acState ?: AcState(), st.button) != null))
                    return Result.failure(IllegalArgumentException("Step ${i + 1}: '${d.name}' has no button '${st.button}'"))
            }
        }
        val existing = id?.let { scenes.get(it) }
        return Result.success(scenes.save(Scene(existing?.id ?: scenes.newId(name), name.trim(), steps)))
    }

    /**
     * Runs a scene step by step on the calling thread (one transmitter, so steps go out one after another with
     * [gapMs] between presses). Stops at nothing: a failed step is reported and the rest still run.
     */
    fun runScene(scene: Scene, source: String = "scene", gapMs: Long = 350): List<Pair<SceneStep, SendResult?>> =
        scene.steps.mapIndexed { i, st ->
            if (st.isWait) { Thread.sleep(st.delayMs); st to null }
            else {
                if (i > 0 && !scene.steps[i - 1].isWait) Thread.sleep(gapMs)
                st to controller.blast(st.device, st.button, source)
            }
        }

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
        return RemoteLayout.of(controller.visibleButtons(d), d.grid, exclude, d.onScreen, d.buttons.filter { it.custom }.map { it.name })
    }

    /** Adds a supported key to (or removes it from) the main remote screen. */
    fun setOnScreen(id: String, button: String, on: Boolean) = store.update(id) { d ->
        val rest = d.onScreen.filterNot { it.equals(button, true) }
        val hidden = if (on) d.hidden.filterNot { it.equals(button, true) } else d.hidden
        d.copy(onScreen = if (on) rest + button else rest, hidden = hidden, grid = if (on) d.grid else d.grid.map { if (it.equals(button, true)) "" else it })
    }

    // ---- free-form grid ----

    fun grid(d: SavedDevice): RemoteGrid = RemoteGrid.resolve(layout(d), d.tiles, d.gridCols.coerceIn(RemoteGrid.COLS), d.gridRows.coerceIn(RemoteGrid.ROWS))

    /** Moves/resizes one tile; on success the whole resolved arrangement is saved. Returns an error message or null. */
    fun moveTile(id: String, tile: String, col: Int, row: Int, w: Int, h: Int): String? {
        val d = store.get(id) ?: return "Unknown device '$id'"
        return RemoteGrid.moveTile(grid(d), tile, col, row, w, h).fold({ tiles -> store.update(d.id) { it.copy(tiles = tiles) }; null }, { it.message })
    }

    fun setGridSize(id: String, cols: Int?, rows: Int?): String? {
        if (cols != null && cols !in RemoteGrid.COLS) return "Columns must be ${RemoteGrid.COLS.first}-${RemoteGrid.COLS.last}"
        if (rows != null && rows !in RemoteGrid.ROWS) return "Rows must be ${RemoteGrid.ROWS.first}-${RemoteGrid.ROWS.last}"
        store.update(id) { d ->
            val c = cols ?: d.gridCols
            // A column change re-flows from the default so nothing ends up off-grid.
            d.copy(gridCols = c, gridRows = rows ?: d.gridRows, tiles = if (c != d.gridCols) emptyList() else d.tiles)
        } ?: return "Unknown device '$id'"
        return null
    }

    fun resetGrid(id: String) = store.update(id) { it.copy(tiles = emptyList()) }

    fun setFavorite(id: String, button: String, on: Boolean) = store.update(id) { d ->
        val rest = d.favorites.filterNot { it.equals(button, true) }
        d.copy(favorites = if (on) rest + button else rest)
    }

    fun setSwipe(id: String, swipe: Boolean) = store.update(id) { it.copy(swipeDpad = swipe) }

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
