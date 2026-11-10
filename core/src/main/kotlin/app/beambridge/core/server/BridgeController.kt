package app.beambridge.core.server

import app.beambridge.core.data.*
import app.beambridge.core.ir.*
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Builds the public URLs shown in the app ("Copy URL Endpoint") and returned by the API. */
class Endpoints(private val baseUrl: () -> String?) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    fun base(): String = baseUrl() ?: "http://<phone-ip>:8080"
    fun blast(deviceId: String, button: String) = "${base()}/api/blast?device=${enc(deviceId)}&button=${enc(button)}"
    fun device(deviceId: String) = "${base()}/api/devices/${enc(deviceId)}"
    fun ac(deviceId: String, s: AcState) =
        "${base()}/api/ac?device=${enc(deviceId)}&power=${if (s.power) "on" else "off"}&mode=${s.mode}&temp=${s.temperature}&fan=${s.fan}"
}

sealed interface SendResult {
    data class Ok(val signal: IrSignal) : SendResult
    data class NotFound(val message: String) : SendResult
    data class Failed(val message: String) : SendResult
}

/** The single place where a "press" turns into IR. Used by both the UI and the HTTP server. */
class BridgeController(
    val store: DeviceStore,
    private val emitter: IrEmitter,
    val history: CommandHistory = CommandHistory(),
    private val datasets: () -> Datasets,
) {
    private fun log(device: SavedDevice?, button: String, source: String, r: SendResult) = history.record(
        HistoryEntry(
            time = System.currentTimeMillis(), device = device?.id, deviceName = device?.name, button = button, source = source,
            ok = r is SendResult.Ok, message = when (r) { is SendResult.Ok -> null; is SendResult.Failed -> r.message; is SendResult.NotFound -> r.message },
        ),
    )

    private val toggles = ConcurrentHashMap<String, Boolean>()
    private val lock = Any()

    val emitterAvailable: Boolean get() = emitter.isAvailable

    /** Sends a code that isn't a saved button. Logged to history when [label] is given. */
    fun send(code: IrCode, toggleKey: String? = null, label: String? = null, source: String = "app"): SendResult =
        sendCode(code, toggleKey).also { if (label != null) log(null, label, source, it) }

    private fun sendCode(code: IrCode, toggleKey: String? = null): SendResult = try {
        // RC5/RC6 toggle phase: computed here, but only committed once the frame actually went out.
        val toggle = toggleKey?.let { k -> !(toggles[k] ?: true) } ?: false
        transmit(IrCodeEncoder.encode(code, toggle, datasets().protocols)).also { r ->
            if (r is SendResult.Ok && toggleKey != null) toggles[toggleKey] = toggle
        }
    } catch (e: IrEncodingException) {
        SendResult.Failed(e.message ?: "Encoding failed")
    } catch (e: Exception) {
        SendResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    private fun transmit(signal: IrSignal): SendResult =
        if (!emitter.isAvailable) SendResult.Failed("This phone has no IR emitter")
        else {
            synchronized(lock) { emitter.transmit(signal) }
            SendResult.Ok(signal)
        }

    /** One-off AC frame for a catalog remote that is not saved yet (pairing wizard). */
    fun testAc(remote: AcRemote, state: AcState, previous: AcState? = null): SendResult = try {
        AcCodec.validate(remote, state)?.let { throw IrEncodingException(it) }
        transmit(AcCodec.signal(remote, state, previous))
    } catch (e: IrEncodingException) {
        SendResult.Failed(e.message ?: "AC encoding failed")
    }

    fun acRemote(device: SavedDevice): AcRemote? {
        val ref = device.catalog?.takeIf { it.source == "ac" } ?: return null
        return datasets().ac.remote(ref.manufacturerId ?: return null, ref.remoteId ?: return null)
    }

    /** Buttons for display and the API: stored buttons for TV/STB, generated actions for an AC. */
    fun buttonNames(device: SavedDevice): List<String> =
        if (device.type == DeviceType.AC) AcCodec.actions(acRemote(device)) + device.buttons.map { it.name }
        else device.buttons.sortedBy { StandardKeys.sortKey(it.name) }.map { it.name }

    /** Visible buttons only (hidden ones stay callable through the API). */
    fun visibleButtons(device: SavedDevice): List<String> = buttonNames(device).filterNot { n -> device.hidden.any { it.equals(n, true) } }

    fun blast(deviceId: String, buttonName: String, source: String = "app"): SendResult {
        val device = store.get(deviceId)
        val r = blastInner(device, deviceId, buttonName)
        log(device, device?.button(buttonName)?.name ?: buttonName.uppercase(), source, r)
        return r
    }

    private fun blastInner(device: SavedDevice?, deviceId: String, buttonName: String): SendResult {
        device ?: return SendResult.NotFound("Unknown device '$deviceId'")
        device.button(buttonName)?.let { return sendCode(it.code, toggleKey = device.id) }
        if (device.type == DeviceType.AC) {
            val remote = acRemote(device)
            val stored = device.acState ?: AcState(temperature = remote?.defaultTemperature ?: 24)
            val current = remote?.let { AcCodec.normalize(it, stored) } ?: stored
            val next = AcCodec.apply(remote, current, buttonName)
            if (next != null) return sendAc(device, remote, current, next)
        }
        return SendResult.NotFound("Device '${device.id}' has no button '$buttonName'")
    }

    fun raw(frequency: Int, pattern: List<Int>, source: String = "app") = send(IrCode.Raw(frequency, pattern), label = "RAW", source = source)

    /** Applies an AC state patch, transmits it, and persists the new state on success. */
    fun ac(deviceId: String, source: String = "app", patch: (AcState) -> AcState): Pair<SendResult, AcState?> {
        val (r, s) = acInner(deviceId, patch)
        val d = store.get(deviceId)
        log(d, s?.let { "AC ${if (it.power) "${it.mode} ${it.temperature}° fan ${it.fan}" else "off"}" } ?: "AC", source, r)
        return r to s
    }

    private fun acInner(deviceId: String, patch: (AcState) -> AcState): Pair<SendResult, AcState?> {
        val device = store.get(deviceId) ?: return SendResult.NotFound("Unknown device '$deviceId'") to null
        if (device.type != DeviceType.AC) return SendResult.Failed("'${device.id}' is not an AC") to null
        val remote = acRemote(device) ?: return SendResult.Failed("AC profile for '${device.id}' not found in ac_remotes.json") to null
        val current = AcCodec.normalize(remote, device.acState ?: AcState(temperature = remote.defaultTemperature))
        val next = patch(current)
        return sendAc(device, remote, current, next) to next
    }

    private fun sendAc(device: SavedDevice, remote: AcRemote?, current: AcState, next: AcState): SendResult {
        if (remote == null) return SendResult.Failed("AC profile for '${device.id}' not found in ac_remotes.json")
        AcCodec.validate(remote, next)?.let { return SendResult.Failed(it) }
        val result = try {
            transmit(AcCodec.signal(remote, next, current))
        } catch (e: IrEncodingException) {
            SendResult.Failed(e.message ?: "AC encoding failed")
        }
        if (result is SendResult.Ok) store.update(device.id) { it.copy(acState = next) }
        return result
    }
}
