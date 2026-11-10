package app.beambridge.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Local repository of the user's appliances. Shared by the Compose UI and the HTTP server. */
interface DeviceStore {
    val devices: StateFlow<List<SavedDevice>>
    fun get(id: String): SavedDevice?
    fun add(device: SavedDevice): SavedDevice
    fun update(id: String, transform: (SavedDevice) -> SavedDevice): SavedDevice?
    fun remove(id: String)

    fun newId(name: String): String = Slug.unique(name, devices.value.map { it.id })

    fun upsertButton(deviceId: String, button: SavedButton, replacing: String? = null) =
        update(deviceId) { d ->
            val key = replacing ?: button.name
            val idx = d.buttons.indexOfFirst { it.name.equals(key, ignoreCase = true) }
            val list = d.buttons.toMutableList()
            if (idx >= 0) list[idx] = button else list.add(button)
            d.copy(buttons = list)
        }

    /** Removes a button (name matched case-insensitively). @return false if the device or button doesn't exist. */
    fun removeButton(deviceId: String, name: String): Boolean {
        val d = get(deviceId) ?: return false
        if (d.buttons.none { it.name.equals(name, ignoreCase = true) }) return false
        update(d.id) { cur -> cur.copy(buttons = cur.buttons.filterNot { it.name.equals(name, ignoreCase = true) }) }
        return true
    }
}

@Serializable
private data class StoreFile(val version: Int = 1, val devices: List<SavedDevice> = emptyList())

/** JSON-file backed store; writes atomically (tmp + rename). Thread-safe. */
class JsonFileDeviceStore(private val file: File) : DeviceStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val state = MutableStateFlow(load())
    override val devices: StateFlow<List<SavedDevice>> = state.asStateFlow()

    private fun load(): List<SavedDevice> =
        SafeFile.read(file, emptyList()) { json.decodeFromString(StoreFile.serializer(), it).devices }

    @Synchronized
    private fun commit(list: List<SavedDevice>) {
        SafeFile.write(file, json.encodeToString(StoreFile.serializer(), StoreFile(devices = list)))
        state.value = list
    }

    override fun get(id: String): SavedDevice? =
        state.value.firstOrNull { it.id == id } ?: state.value.firstOrNull { it.id.equals(id, ignoreCase = true) }

    @Synchronized
    override fun add(device: SavedDevice): SavedDevice {
        val fixed = if (state.value.any { it.id == device.id }) device.copy(id = newId(device.id)) else device
        commit(state.value + fixed)
        return fixed
    }

    @Synchronized
    override fun update(id: String, transform: (SavedDevice) -> SavedDevice): SavedDevice? {
        val current = get(id) ?: return null
        val next = transform(current)
        commit(state.value.map { if (it.id == current.id) next else it })
        return next
    }

    @Synchronized
    override fun remove(id: String) { val d = get(id) ?: return; commit(state.value.filterNot { it.id == d.id }) }
}

/** In-memory store for tests and previews. */
class InMemoryDeviceStore(initial: List<SavedDevice> = emptyList()) : DeviceStore {
    private val state = MutableStateFlow(initial)
    override val devices: StateFlow<List<SavedDevice>> = state.asStateFlow()
    override fun get(id: String) = state.value.firstOrNull { it.id.equals(id, ignoreCase = true) }
    @Synchronized override fun add(device: SavedDevice) = device.also { state.value = state.value + it }
    @Synchronized override fun update(id: String, transform: (SavedDevice) -> SavedDevice): SavedDevice? {
        val cur = get(id) ?: return null
        val next = transform(cur)
        state.value = state.value.map { if (it.id == cur.id) next else it }
        return next
    }
    @Synchronized override fun remove(id: String) { val d = get(id) ?: return; state.value = state.value.filterNot { it.id == d.id } }
}
