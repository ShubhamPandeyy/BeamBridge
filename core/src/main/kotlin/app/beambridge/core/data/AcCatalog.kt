package app.beambridge.core.data

import app.beambridge.core.ir.Bits
import app.beambridge.core.ir.IrCode
import app.beambridge.core.ir.IrEncodingException
import app.beambridge.core.ir.IrProgram
import app.beambridge.core.ir.IrSignal
import kotlinx.serialization.json.*
import java.math.BigInteger

/**
 * One air-conditioner remote from ac_remotes.json.
 *
 * Every frame is [bitLength] bits. `codes` holds a full frame for POWER (and sometimes POWER_ON) plus XOR deltas
 * for mode (bCooling…), temperature (temp_NN), fan speed (dSpeed…) and direction (eDirection…). The frame for a
 * state is the XOR of the selected deltas. See [AcCodec] for the caveats.
 */
data class AcRemote(
    val remoteId: Int,
    val name: String,
    val protocolId: Int,
    val protocolTitle: String,
    val program: IrProgram?,
    val bitLength: Int,
    val defaultTemperature: Int,
    val codes: Map<String, String>,
    val hasRules: Boolean,
) {
    val temperatures: IntRange
        get() {
            val t = codes.keys.filter { it.startsWith("temp_") }.mapNotNull { it.removePrefix("temp_").toIntOrNull() }
            return if (t.isEmpty()) 16..30 else t.min()..t.max()
        }
    val modes: List<String> get() = MODE_KEYS.filterValues { it in codes }.keys.toList()
    val fanSpeeds: List<String> get() = FAN_KEYS.filterValues { it in codes }.keys.toList()
    val supported: Boolean get() = program != null && "POWER" in codes && modes.isNotEmpty()

    companion object {
        val MODE_KEYS = linkedMapOf("cool" to "bCooling", "auto" to "bAuto", "heat" to "bHeat", "fan" to "bFan", "dry" to "bDehumidify")
        val FAN_KEYS = linkedMapOf("auto" to "dSpeedAuto", "low" to "dSpeedLow", "medium" to "dSpeedMedium", "high" to "dSpeedHigh")
    }
}

class AcCatalog(val manufacturers: List<Manufacturer<AcRemote>>) {
    val remoteCount get() = manufacturers.sumOf { it.remotes.size }
    private val index by lazy { manufacturers.flatMap { m -> m.remotes.map { (m.id to it.remoteId) to it } }.toMap() }
    fun remote(manufacturerId: Int, remoteId: Int) = index[manufacturerId to remoteId]

    companion object {
        val EMPTY = AcCatalog(emptyList())

        fun parse(text: String): AcCatalog {
            val json = Json { isLenient = true; ignoreUnknownKeys = true }
            val list = json.parseToJsonElement(text).objects().mapIndexedNotNull { idx, m ->
                val name = m["manufacturer_name"].str() ?: return@mapIndexedNotNull null
                val remotes = m["remotes"].objects().mapIndexedNotNull { ri, r ->
                    val codes = (r["codes"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonObject)?.get("code").str()?.let { k to it }
                    }?.toMap().orEmpty()
                    if (codes.isEmpty()) return@mapIndexedNotNull null
                    val proto = r["protocol"] as? JsonObject
                    val rules = (r["rules"] as? JsonObject)?.values.orEmpty().any { it.str().orEmpty().isNotBlank() }
                    AcRemote(
                        remoteId = r["ac_remote_id"].int() ?: (idx * 1000 + ri),
                        name = r["name"].str() ?: "$name remote",
                        protocolId = proto?.get("id").int() ?: -1,
                        protocolTitle = proto?.get("title").str().orEmpty(),
                        program = proto?.get("program").str()?.let(IrProgram::parseBase64),
                        bitLength = r["bit_length"].int() ?: 0,
                        defaultTemperature = (r["defaults"] as? JsonObject)?.get("temperature").int() ?: 24,
                        codes = codes,
                        hasRules = rules,
                    )
                }
                if (remotes.isEmpty()) null else Manufacturer(m["manufacturer_id"].int() ?: idx, name, remotes)
            }
            return AcCatalog(list.sortedBy { it.name.lowercase() })
        }
    }
}

/**
 * Builds AC frames.
 *
 * UNVERIFIED AGAINST HARDWARE: the XOR-composition was derived by analysing the dataset (it reproduces the known
 * LG power-off frame 0x88C0051 and the temperature/checksum deltas for the base mode), but checksums for modes other
 * than the base one, and remotes with non-empty `rules`, may be wrong. Treat AC control as experimental.
 */
object AcCodec {
    private fun parse(remote: AcRemote, key: String): BigInteger? =
        remote.codes[key]?.let { BigInteger(it.trim().removePrefix("0x").removePrefix("0X"), 16) }

    /** Null when [state] can be encoded for [remote]; otherwise a message naming the bad value and the valid ones. */
    fun validate(remote: AcRemote, state: AcState): String? = when {
        !state.power -> null   // the power-off frame doesn't depend on mode/fan/temperature
        state.mode !in remote.modes -> "Unsupported mode '${state.mode}'. Use: ${remote.modes.joinToString()}"
        remote.fanSpeeds.isNotEmpty() && state.fan !in remote.fanSpeeds -> "Unsupported fan '${state.fan}'. Use: ${remote.fanSpeeds.joinToString()}"
        state.temperature !in remote.temperatures -> "Temperature ${state.temperature} out of range ${remote.temperatures.first}-${remote.temperatures.last}"
        else -> null
    }

    /** Fixes up a *stored* state (e.g. from an older version) so it only uses values this remote supports. */
    fun normalize(remote: AcRemote, s: AcState): AcState = s.copy(
        mode = if (s.mode in remote.modes) s.mode else remote.modes.firstOrNull() ?: s.mode,
        fan = if (remote.fanSpeeds.isEmpty() || s.fan in remote.fanSpeeds) s.fan else remote.fanSpeeds.first(),
        temperature = s.temperature.coerceIn(remote.temperatures),
    )

    fun frame(remote: AcRemote, state: AcState, previous: AcState? = null): BigInteger = frameWithWidth(remote, state, previous).first

    /** Codes that make up the frame for [state] (a full frame, or a base frame + XOR deltas). */
    private fun parts(remote: AcRemote, state: AcState, previous: AcState?): List<String> {
        validate(remote, state)?.let { throw IrEncodingException(it) }
        if (!state.power) remote.codes["POWER"]?.let { return listOf("POWER") }
        if (state.power && previous?.power == false) remote.codes["POWER_ON"]?.let { return listOf("POWER_ON") }
        val mode = AcRemote.MODE_KEYS.getValue(state.mode)
        if (mode !in remote.codes) throw IrEncodingException("This remote has no '${state.mode}' mode")
        return listOfNotNull(mode, "temp_${state.temperature}".takeIf { it in remote.codes }, AcRemote.FAN_KEYS[state.fan]?.takeIf { it in remote.codes })
    }

    /**
     * Frame value and its bit width. Deltas are stored with leading zeros stripped, so they line up from the right
     * (byte-for-byte the same as XOR-ing fixed-length byte arrays). The width is [AcRemote.bitLength], unless one of
     * the codes is a whole nibble or more wider (e.g. a separate, longer POWER frame): then that code's own width is
     * used, so nothing is cut off the front and the frame doesn't shift.
     */
    fun frameWithWidth(remote: AcRemote, state: AcState, previous: AcState? = null): Pair<BigInteger, Int> {
        val keys = parts(remote, state, previous)
        var value = BigInteger.ZERO
        var width = remote.bitLength
        for (k in keys) {
            val hex = remote.codes.getValue(k).trim().removePrefix("0x").removePrefix("0X")
            value = value.xor(BigInteger(hex, 16))
            val w = hex.length * 4
            if (w - remote.bitLength >= 4) width = maxOf(width, w)
        }
        if (width <= 0) width = maxOf(value.bitLength(), 8)
        return value to width
    }

    private fun bits(v: BigInteger, n: Int) = BooleanArray(n) { i -> v.testBit(n - 1 - i) }

    fun signal(remote: AcRemote, state: AcState, previous: AcState? = null): IrSignal {
        val program = remote.program ?: throw IrEncodingException("Protocol '${remote.protocolTitle}' is not supported yet")
        val (value, n) = frameWithWidth(remote, state, previous)
        return program.render(bits(value, n))
    }

    /** Named one-tap actions exposed as buttons (and therefore as copyable URLs) for an AC. */
    fun actions(remote: AcRemote?): List<String> = buildList {
        add("POWER_ON"); add("POWER_OFF"); add("TEMP_UP"); add("TEMP_DOWN")
        remote?.modes?.forEach { add("MODE_${it.uppercase()}") }
        remote?.fanSpeeds?.forEach { add("FAN_${it.uppercase()}") }
    }

    fun apply(remote: AcRemote?, state: AcState, action: String): AcState? {
        val a = action.uppercase()
        val range = remote?.temperatures ?: 16..30
        return when {
            a == "POWER_ON" -> state.copy(power = true)
            a == "POWER_OFF" -> state.copy(power = false)
            a == "POWER" || a == "POWER_TOGGLE" -> state.copy(power = !state.power)
            a == "TEMP_UP" -> state.copy(power = true, temperature = (state.temperature + 1).coerceIn(range))
            a == "TEMP_DOWN" -> state.copy(power = true, temperature = (state.temperature - 1).coerceIn(range))
            a.startsWith("MODE_") -> state.copy(power = true, mode = a.removePrefix("MODE_").lowercase())
            a.startsWith("FAN_") -> state.copy(power = true, fan = a.removePrefix("FAN_").lowercase())
            else -> null
        }
    }
}
