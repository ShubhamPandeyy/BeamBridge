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
 * The last [max] commands, newest first. Kept in memory and saved to [file] so it survives restarts.
 * Saving is batched: a burst of presses (or a finder run) is written once, [writeDelayMs] after the last one, off the
 * sending thread. [listener] fires after every record; the app uses it for the "beamed" notification.
 */
class CommandHistory(private val file: File? = null, private val max: Int = 200, private val writeDelayMs: Long = 1500) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HistoryEntry.serializer())
    private val state = MutableStateFlow(file?.let { SafeFile.read(it, emptyList()) { t -> json.decodeFromString(serializer, t) } }.orEmpty())
    val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()
    @Volatile var listener: ((HistoryEntry) -> Unit)? = null

    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "history-writer").apply { isDaemon = true } }
    }
    private var pending: java.util.concurrent.ScheduledFuture<*>? = null

    fun record(e: HistoryEntry) {
        synchronized(this) {
            state.value = (listOf(e) + state.value).take(max)
            scheduleWrite()
        }
        runCatching { listener?.invoke(e) }
    }

    private fun scheduleWrite() {
        if (file == null) return
        if (writeDelayMs <= 0) { flush(); return }
        pending?.cancel(false)
        pending = writer.schedule({ flush() }, writeDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Writes the current entries now (also called when the app goes to the background). */
    fun flush() {
        val f = file ?: return
        val snapshot = synchronized(this) { state.value }
        runCatching { SafeFile.write(f, json.encodeToString(serializer, snapshot)) }
    }

    fun clear() = synchronized(this) { pending?.cancel(false); state.value = emptyList(); file?.delete() }
}
