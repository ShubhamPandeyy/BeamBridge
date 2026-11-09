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
    fun remote(manufacturerId: Int, remoteId: Int) =
        manufacturers.firstOrNull { it.id == manufacturerId }?.remotes?.firstOrNull { it.remoteId == remoteId }

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

    fun frame(remote: AcRemote, state: AcState, previous: AcState? = null): BigInteger {
        if (!state.power) parse(remote, "POWER")?.let { return it }
        if (state.power && previous?.power == false) parse(remote, "POWER_ON")?.let { return it }
        val mode = parse(remote, AcRemote.MODE_KEYS[state.mode] ?: "bCooling")
            ?: throw IrEncodingException("This remote has no '${state.mode}' mode")
        var frame = mode
        parse(remote, "temp_${state.temperature.coerceIn(remote.temperatures)}")?.let { frame = frame.xor(it) }
        parse(remote, AcRemote.FAN_KEYS[state.fan] ?: "dSpeedAuto")?.let { frame = frame.xor(it) }
        return frame
    }

    fun signal(remote: AcRemote, state: AcState, previous: AcState? = null): IrSignal {
        val program = remote.program ?: throw IrEncodingException("Protocol '${remote.protocolTitle}' is not supported yet")
        val value = frame(remote, state, previous)
        val n = remote.bitLength
        return program.render(BooleanArray(n) { i -> value.testBit(n - 1 - i) })
    }

    fun hex(remote: AcRemote, state: AcState): String = Bits.toHex(
        frame(remote, state).let { v -> BooleanArray(remote.bitLength) { i -> v.testBit(remote.bitLength - 1 - i) } },
    )

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
