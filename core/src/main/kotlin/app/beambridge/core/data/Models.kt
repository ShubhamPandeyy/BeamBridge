package app.beambridge.core.data

import app.beambridge.core.ir.IrCode
import kotlinx.serialization.Serializable

@Serializable
enum class DeviceType(val label: String) {
    TV("TV"), AC("Air conditioner"), SET_TOP_BOX("Set-top box"), OTHER("Other");
}

@Serializable
data class SavedButton(
    val name: String,
    val code: IrCode,
    val custom: Boolean = false,
)

/** Where a device came from in the bundled catalogs, so AC state frames can be rebuilt later. */
@Serializable
data class CatalogRef(
    val source: String,            // "tv" | "ac" | "stb"
    val manufacturerId: Int? = null,
    val remoteId: Int? = null,
)

@Serializable
data class AcState(
    val power: Boolean = true,
    val mode: String = "cool",   // cool | auto | heat | fan | dry
    val temperature: Int = 24,   // °C
    val fan: String = "auto",    // auto | low | medium | high
)

@Serializable
data class SavedDevice(
    val id: String,
    val name: String,
    val type: DeviceType,
    val brand: String? = null,
    val catalog: CatalogRef? = null,
    val buttons: List<SavedButton> = emptyList(),
    val acState: AcState? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun button(name: String): SavedButton? =
        buttons.firstOrNull { it.name == name } ?: buttons.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

object Slug {
    fun of(text: String): String =
        text.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "device" }

    fun unique(text: String, taken: Collection<String>): String {
        val base = of(text)
        if (base !in taken) return base
        var i = 2
        while ("${base}_$i" in taken) i++
        return "${base}_$i"
    }
}
