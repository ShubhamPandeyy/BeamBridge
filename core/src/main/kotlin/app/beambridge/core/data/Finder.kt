package app.beambridge.core.data

import app.beambridge.core.ir.IrCode
import app.beambridge.core.ir.ProgramLookup
import app.beambridge.core.server.BridgeController
import app.beambridge.core.server.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class FinderCode(val label: String, val code: IrCode)

data class FinderState(
    val key: String? = null,
    val running: Boolean = false,
    /** How many codes have been sent so far (index of the next one). */
    val sent: Int = 0,
    val total: Int = 0,
    /** 0 = the 5 most recent codes, 1 = the 5 before those, … */
    val page: Int = 0,
    val intervalMs: Long = 900,
) {
    val done: Boolean get() = total > 0 && sent >= total && !running
}

/**
 * "TV finder": fires every stored code for one function (power, volume up, …) until the user says stop, then lets
 * them pick the code that worked from the last few sent. Shared by the app and the web UI.
 */
class Finder(
    private val controller: BridgeController,
    private val datasets: () -> Datasets,
    private val stb: () -> StbSource?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(FinderState())
    val state: StateFlow<FinderState> = _state.asStateFlow()
    private var codes: List<FinderCode> = emptyList()
    private var job: Job? = null

    /** All distinct codes in the catalogs (TV json, extra remotes, set-top DB) for [key]. */
    fun codesFor(key: String): List<FinderCode> {
        val ds = datasets()
        val programs: ProgramLookup = ds.protocols
        val out = LinkedHashMap<Triple<String, String, Int>, FinderCode>()
        fun addRemote(brand: String, r: TvRemote) {
            if (!r.isSupported(programs)) return
            val name = r.buttons.keys.firstOrNull { it.equals(key, true) } ?: return
            val code = r.irCode(name) as IrCode.Hex
            out.putIfAbsent(Triple(code.protocol, code.code.lowercase(), code.bits), FinderCode("$brand · ${r.remoteId}", code))
        }
        ds.tv.manufacturers.forEach { m -> m.remotes.forEach { addRemote(m.name, it) } }
        runCatching { stb()?.allRemotes() }.getOrNull()?.forEach { addRemote("Set-top", it) }
        return out.values.toList()
    }

    fun start(key: String, intervalMs: Long = _state.value.intervalMs) {
        job?.cancel()
        codes = codesFor(key)
        _state.value = FinderState(key = key.uppercase(), running = codes.isNotEmpty(), total = codes.size, intervalMs = intervalMs)
        if (codes.isNotEmpty()) loop()
    }

    /** Continue sending from where the finder stopped. */
    fun resume() {
        val s = _state.value
        if (s.key == null || s.sent >= s.total) return
        _state.update { it.copy(running = true, page = 0) }
        loop()
    }

    private fun loop() {
        job?.cancel()
        job = scope.launch {
            while (isActive && _state.value.running && _state.value.sent < codes.size) {
                controller.send(codes[_state.value.sent].code, toggleKey = "finder")
                _state.update { it.copy(sent = it.sent + 1) }
                delay(_state.value.intervalMs)
            }
            _state.update { it.copy(running = false) }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = false, page = 0) }
    }

    fun reset() { stop(); codes = emptyList(); _state.value = FinderState() }

    fun setPage(page: Int) = _state.update { it.copy(page = page.coerceIn(0, maxPage())) }
    private fun maxPage() = ((_state.value.sent - 1) / 5).coerceAtLeast(0)

    /** The codes on the current page, newest first, with their index in the run. */
    fun window(page: Int = _state.value.page): List<Pair<Int, FinderCode>> {
        val end = _state.value.sent - page * 5
        return (end - 1 downTo (end - 5).coerceAtLeast(0)).filter { it in codes.indices }.map { it to codes[it] }
    }

    fun test(index: Int): SendResult =
        codes.getOrNull(index)?.let { controller.send(it.code, toggleKey = "finder") } ?: SendResult.NotFound("No code #$index")

    /** Save the chosen code as a button on [deviceId]. */
    fun accept(deviceId: String, buttonName: String, index: Int): SavedDevice? {
        val c = codes.getOrNull(index) ?: return null
        val name = buttonName.trim().uppercase().replace(Regex("\\s+"), "_").ifEmpty { _state.value.key ?: "BUTTON" }
        val d = controller.store.upsertButton(deviceId, SavedButton(name, c.code, custom = true))
        reset()
        return d
    }
}
