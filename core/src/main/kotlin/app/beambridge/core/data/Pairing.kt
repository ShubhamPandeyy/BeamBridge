package app.beambridge.core.data

import app.beambridge.core.ir.IrCode
import app.beambridge.core.server.BridgeController
import app.beambridge.core.server.SendResult

data class Brand(val id: Int, val name: String, val remotes: Int)

/** One remote profile to try during pairing. */
data class Candidate(val ref: CatalogRef, val label: String, val tv: TvRemote? = null, val ac: AcRemote? = null)

/** Set-top box remotes live in lcndb.db (SQLite), which only the Android app can open. */
interface StbSource {
    fun providers(query: String): List<Brand>
    fun remotes(providerId: Int): List<TvRemote>
    fun allRemotes(): List<TvRemote> = emptyList()
}

/**
 * Pairing logic shared by the app wizard and the web UI: brands, candidate remotes, test presses and device creation.
 * Candidates are only collapsed when two remotes are exactly identical, so every distinct profile gets tried.
 */
class PairingService(
    private val datasets: () -> Datasets,
    private val stb: () -> StbSource?,
    private val controller: BridgeController,
) {
    private val store get() = controller.store

    fun brands(type: DeviceType, query: String = ""): List<Brand> {
        val q = query.trim()
        val ds = datasets()
        return when (type) {
            DeviceType.TV -> ds.tv.manufacturers.filter { it.name.contains(q, true) }
                .map { m -> Brand(m.id, m.name, m.remotes.count { it.isSupported(ds.protocols) }) }.filter { it.remotes > 0 }
            DeviceType.AC -> ds.ac.manufacturers.filter { it.name.contains(q, true) }
                .map { m -> Brand(m.id, m.name, m.remotes.count { it.supported }) }.filter { it.remotes > 0 }
            DeviceType.SET_TOP_BOX -> stb()?.providers(q).orEmpty()
            DeviceType.OTHER -> emptyList()
        }
    }

    fun candidates(type: DeviceType, brandId: Int): List<Candidate> {
        val ds = datasets()
        return when (type) {
            DeviceType.TV -> ds.tv.brand(brandId)?.remotes.orEmpty().filter { it.isSupported(ds.protocols) }
                .distinctBy { Triple(it.protocolName, it.bitLength, it.buttons) }
                .map { Candidate(CatalogRef("tv", brandId, it.remoteId), "Remote ${it.remoteId}", tv = it) }
            DeviceType.AC -> ds.ac.manufacturers.firstOrNull { it.id == brandId }?.remotes.orEmpty().filter { it.supported }
                .distinctBy { Triple(it.protocolId, it.bitLength, it.codes) }
                .map { Candidate(CatalogRef("ac", brandId, it.remoteId), it.name, ac = it) }
            DeviceType.SET_TOP_BOX -> stb()?.remotes(brandId).orEmpty().filter { it.isSupported(ds.protocols) }
                .distinctBy { Triple(it.protocolName, it.bitLength, it.buttons) }
                .map { Candidate(CatalogRef("stb", brandId, it.remoteId), "Remote ${it.remoteId}", tv = it) }
            DeviceType.OTHER -> emptyList()
        }
    }

    /** The key pressed for a test: power when the appliance is off, something visible but harmless when it is on. */
    fun testKey(c: Candidate, deviceOn: Boolean): String = when {
        c.ac != null -> if (deviceOn) "TEMP_DOWN" else "POWER_ON"
        else -> {
            val r = c.tv!!
            val keys = if (deviceOn) StandardKeys.ON_TEST_KEYS else StandardKeys.TEST_KEYS
            keys.firstOrNull { it in r.buttons } ?: r.buttons.keys.first()
        }
    }

    private var acProbeTemp = 24

    fun test(c: Candidate, deviceOn: Boolean): SendResult = when {
        c.ac != null -> {
            // When the AC is already on, alternate between two temperatures so every press is a visible change.
            acProbeTemp = if (acProbeTemp == 24) 23 else 24
            val state = AcCodec.normalize(c.ac, AcState(power = true, mode = "cool", temperature = if (deviceOn) acProbeTemp else 24, fan = "auto"))
            controller.testAc(c.ac, state, previous = if (deviceOn) state.copy(temperature = 25) else AcState(power = false))
        }
        else -> controller.send(c.tv!!.irCode(testKey(c, deviceOn))!!, toggleKey = "pairing", label = "Pairing test ${testKey(c, deviceOn)} (${c.label})", source = "wizard")
    }

    fun create(type: DeviceType, c: Candidate, name: String, brandName: String?): SavedDevice {
        val clean = name.trim().ifEmpty { brandName ?: type.label }
        val id = store.newId(clean)
        val device = if (c.ac != null) SavedDevice(
            id = id, name = clean, type = DeviceType.AC, brand = brandName, catalog = c.ref,
            acState = AcCodec.normalize(c.ac, AcState(power = false, mode = "cool", temperature = c.ac.defaultTemperature)),
        ) else {
            val r = c.tv!!
            SavedDevice(
                id = id, name = clean, type = type, brand = brandName, catalog = c.ref,
                buttons = r.buttons.keys.sortedBy(StandardKeys::sortKey).map { SavedButton(it, r.irCode(it)!!) },
            )
        }
        return store.add(device)
    }

    /** A blank device the user fills with custom buttons or the finder. */
    fun createCustom(name: String, type: DeviceType = DeviceType.OTHER, first: SavedButton? = null): SavedDevice {
        val clean = name.trim().ifEmpty { "My device" }
        return store.add(SavedDevice(id = store.newId(clean), name = clean, type = type, buttons = listOfNotNull(first)))
    }

    fun brandName(type: DeviceType, brandId: Int): String? = when (type) {
        DeviceType.TV -> datasets().tv.brand(brandId)?.name
        DeviceType.AC -> datasets().ac.manufacturers.firstOrNull { it.id == brandId }?.name
        else -> null
    }
}

/** Parses the code fields shared by the API and the app editor. */
object CodeInput {
    /**
     * Accepts: `protocol` + `code` [+ `bits`], `pronto`, or `frequency` + `pattern`.
     * Also a single free-text `ir` value: "NEC 0x20DF10EF 32", "0000 006D …" (Pronto) or "9000,4500,560,…" (raw, 38 kHz).
     */
    fun parse(p: Map<String, String>): IrCode? {
        p["ir"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return parseText(it, p["frequency"]?.toIntOrNull()) }
        return when {
            !p["pronto"].isNullOrBlank() -> IrCode.Pronto(p.getValue("pronto").trim())
            !(p["code"] ?: p["hex"]).isNullOrBlank() -> IrCode.Hex(
                protocol = p["protocol"]?.uppercase() ?: "NEC",
                code = (p["code"] ?: p.getValue("hex")).trim(),
                bits = p["bits"]?.toIntOrNull() ?: 0,
                frequency = p["frequency"]?.toIntOrNull(),
            )
            !p["pattern"].isNullOrBlank() -> IrCode.Raw(
                p["frequency"]?.toIntOrNull() ?: 38_000,
                app.beambridge.core.ir.IrCodeEncoder.parsePattern(p.getValue("pattern")),
            )
            else -> null
        }
    }

    fun parseText(text: String, frequency: Int? = null): IrCode {
        val t = text.trim()
        val words = t.split(Regex("\\s+"))
        return when {
            words.size >= 4 && words.all { it.length == 4 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } } -> IrCode.Pronto(t)
            words.first().any { it.isLetter() } && !words.first().startsWith("0x", true) && words.size >= 2 ->
                IrCode.Hex(words[0].uppercase(), words[1], words.getOrNull(2)?.toIntOrNull() ?: 0)
            t.startsWith("0x", true) -> IrCode.Hex("NEC", words[0], words.getOrNull(1)?.toIntOrNull() ?: 0)
            else -> IrCode.Raw(frequency ?: 38_000, app.beambridge.core.ir.IrCodeEncoder.parsePattern(t))
        }
    }

    /** Inverse of [parseText], for pre-filling editors. */
    fun format(code: IrCode): String = when (code) {
        is IrCode.Hex -> listOfNotNull(code.protocol, code.code, code.bits.takeIf { it > 0 }?.toString()).joinToString(" ")
        is IrCode.Pronto -> code.pronto
        is IrCode.Raw -> code.pattern.joinToString(",")
    }

    const val EXAMPLES = "NEC 0x20DF10EF 32 · SAMSUNG 0xE0E040BF · RCA 0xF2A0D5 · 9000,4500,560,560,… · 0000 006D 0022 0002 …"
}
