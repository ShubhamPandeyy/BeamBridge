package app.beambridge.core.data

import app.beambridge.core.ir.IrCode
import app.beambridge.core.ir.IrProgram
import app.beambridge.core.ir.IrProtocol
import app.beambridge.core.ir.ProgramLookup
import kotlinx.serialization.json.*

/**
 * Parsers for the bundled reference datasets. The shapes match the real files (see assets/README_ASSETS.md);
 * unknown keys are ignored and numbers may arrive as strings.
 */
private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
internal fun JsonElement?.int(): Int? = str()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
internal fun JsonElement?.bool(): Boolean? = str()?.let { it.equals("true", true) || it == "1" }
internal fun JsonObject.first(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { this[it] }
internal fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

data class Manufacturer<R>(val id: Int, val name: String, val remotes: List<R>)

// ---------------------------------------------------------------- Protocols (programs)

data class ProtocolInfo(val id: Int, val title: String, val irp: String?, val program: IrProgram?)

class ProtocolCatalog(val protocols: List<ProtocolInfo>) : ProgramLookup {
    private val byId = protocols.associateBy { it.id }
    override fun program(protocolId: Int): IrProgram? = byId[protocolId]?.program
    val supportedCount get() = protocols.count { it.program != null }

    companion object {
        val EMPTY = ProtocolCatalog(emptyList())

        fun parse(text: String): ProtocolCatalog {
            val list = lenientJson.parseToJsonElement(text).objects().mapNotNull { o ->
                val id = o["id"].int() ?: return@mapNotNull null
                ProtocolInfo(id, o["title"].str() ?: "Protocol $id", o["irp_notation"].str(), o["program"].str()?.let(IrProgram::parseBase64))
            }
            return ProtocolCatalog(list)
        }
    }
}

// ---------------------------------------------------------------- TV

data class TvRemote(
    val remoteId: Int,
    val protocolId: Int?,
    val protocolName: String,
    val bitLength: Int,
    val toggleMask: String?,
    val buttons: Map<String, String>, // name → hex code
) {
    fun irCode(buttonName: String): IrCode? = buttons[buttonName]?.let {
        IrCode.Hex(protocolName, it, bitLength, protocolId, toggleMask)
    }

    /** True when a built-in encoder or a bytecode program exists for this remote's protocol. */
    fun isSupported(programs: ProgramLookup): Boolean =
        IrProtocol.resolve(protocolName, protocolId) != null || protocolId?.let { programs.program(it) } != null
}

class TvCatalog(val manufacturers: List<Manufacturer<TvRemote>>) {
    val remoteCount get() = manufacturers.sumOf { it.remotes.size }
    fun brand(id: Int) = manufacturers.firstOrNull { it.id == id }
    fun remote(manufacturerId: Int, remoteId: Int) = brand(manufacturerId)?.remotes?.firstOrNull { it.remoteId == remoteId }

    /** Adds [extra] remotes, joining brands by name (case-insensitive). */
    fun merge(extra: TvCatalog): TvCatalog {
        val byName = manufacturers.associateBy { it.name.lowercase() }.toMutableMap()
        for (m in extra.manufacturers) {
            val cur = byName[m.name.lowercase()]
            byName[m.name.lowercase()] = if (cur == null) m else cur.copy(remotes = cur.remotes + m.remotes.filter { r -> cur.remotes.none { it.remoteId == r.remoteId } })
        }
        return TvCatalog(byName.values.sortedBy { it.name.lowercase() })
    }

    companion object {
        val EMPTY = TvCatalog(emptyList())

        fun parse(text: String): TvCatalog {
            val list = lenientJson.parseToJsonElement(text).objects().mapIndexedNotNull { idx, m ->
                val name = m["manufacturer_name"].str() ?: return@mapIndexedNotNull null
                val remotes = m["remotes"].objects().mapIndexedNotNull { ri, r ->
                    val buttons = linkedMapOf<String, String>()
                    (r["buttons"] as? JsonObject)?.forEach { (key, v) ->
                        (v as? JsonObject)?.get("code").str()?.let { buttons[key] = it }
                    }
                    if (buttons.isEmpty()) return@mapIndexedNotNull null
                    TvRemote(
                        remoteId = r["remote_id"].int() ?: (idx * 1000 + ri),
                        protocolId = r["protocol_id"].int(),
                        protocolName = r["protocol_name"].str() ?: "NEC",
                        bitLength = r["bit_length"].int() ?: 0,
                        toggleMask = r["toggle_mask"].str()?.takeIf { it.isNotBlank() },
                        buttons = buttons,
                    )
                }
                if (remotes.isEmpty()) null else Manufacturer(m["manufacturer_id"].int() ?: idx, name, remotes)
            }
            return TvCatalog(list.sortedBy { it.name.lowercase() })
        }
    }
}

/** Standard key index shared by every remote in the datasets. */
object StandardKeys {
    val names: Map<Int, String> = buildMap {
        (0..9).forEach { put(it, "$it") }
        putAll(mapOf(
            10 to "VOL_UP", 11 to "VOL_DOWN", 12 to "VOL_MUTE", 13 to "CH_UP", 14 to "CH_DOWN",
            15 to "POWER", 16 to "SELECT", 17 to "BACK", 18 to "EXIT", 19 to "MENU", 20 to "INFO",
            21 to "UP", 22 to "DOWN", 23 to "LEFT", 24 to "RIGHT", 25 to "OK",
            26 to "RED", 27 to "GREEN", 28 to "YELLOW", 29 to "BLUE",
            30 to "PLAY", 31 to "REWIND", 32 to "FAST_FORWARD", 33 to "RECORD", 34 to "PAUSE", 35 to "STOP",
            36 to "SOURCE", 37 to "OPTIONS", 39 to "GUIDE", 40 to "PREVIOUS", 41 to "NEXT", 42 to "KEY_42",
        ))
    }
    fun name(id: Int?): String? = id?.let { names[it] }

    /** Keys the pairing wizard prefers for "did the appliance react?" checks, in order. */
    val TEST_KEYS = listOf("POWER", "VOL_MUTE", "VOL_UP", "CH_UP", "MENU")

    /** Keys used to test a remote when the appliance is already on: something visible but harmless. */
    val ON_TEST_KEYS = listOf("VOL_UP", "VOL_MUTE", "CH_UP", "MENU", "INFO", "VOL_DOWN", "SOURCE")

    /** Functions offered by the TV finder, in display order. */
    val FINDER_KEYS = listOf("POWER", "VOL_UP", "VOL_DOWN", "VOL_MUTE", "CH_UP", "CH_DOWN", "UP", "DOWN", "LEFT", "RIGHT",
        "OK", "BACK", "HOME", "MENU", "SOURCE", "EXIT", "INFO", "GUIDE") + (0..9).map { "$it" }

    private val order = listOf("POWER", "HOME", "SOURCE", "VOL_UP", "VOL_DOWN", "VOL_MUTE", "CH_UP", "CH_DOWN",
        "UP", "DOWN", "LEFT", "RIGHT", "OK", "SELECT", "BACK", "EXIT", "MENU", "GUIDE", "INFO", "OPTIONS",
        "PLAY", "PAUSE", "STOP", "REWIND", "FAST_FORWARD", "PREVIOUS", "NEXT", "RECORD",
        "RED", "GREEN", "YELLOW", "BLUE") + (0..9).map { "$it" }
    fun sortKey(name: String): Int = order.indexOf(name.uppercase()).let { if (it < 0) order.size else it }
}
