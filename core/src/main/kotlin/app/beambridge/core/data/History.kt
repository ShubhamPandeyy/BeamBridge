package app.beambridge.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** One transmitted (or attempted) command. [source] is app | web | api | scene | wizard | finder. */
@Serializable
data class HistoryEntry(
    val time: Long,
    val device: String? = null,
    val deviceName: String? = null,
    val button: String,
    val source: String,
    val ok: Boolean,
    val message: String? = null,
)

/**
 * The last [max] commands, newest first. Kept in memory and (optionally) saved to [file] so it survives restarts.
 * [listener] fires after every record; the app uses it for the "beamed" notification.
 */
class CommandHistory(private val file: File? = null, private val max: Int = 200) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HistoryEntry.serializer())
    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()
    @Volatile var listener: ((HistoryEntry) -> Unit)? = null

    private fun load(): List<HistoryEntry> =
        runCatching { file?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(serializer, it) } }.getOrNull().orEmpty()

    fun record(e: HistoryEntry) {
        synchronized(this) {
            state.value = (listOf(e) + state.value).take(max)
            file?.let { f -> runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json.encodeToString(serializer, state.value))
                tmp.renameTo(f)
            } }
        }
        runCatching { listener?.invoke(e) }
    }

    fun clear() = synchronized(this) { state.value = emptyList(); file?.delete() }
}
