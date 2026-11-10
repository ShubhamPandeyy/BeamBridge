package app.beambridge.core.data

import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe JSON file I/O shared by every store.
 * - [write]: temp file, fsync, then rename, so a power cut leaves either the old or the new file, never half of one.
 * - [read]: an unreadable file is moved aside to `<name>.corrupt-<time>` before the store starts empty, so the next
 *   save can never overwrite the user's only copy.
 */
object SafeFile {
    fun <T> read(file: File, empty: T, decode: (String) -> T): T {
        if (!file.exists()) return empty
        return runCatching { decode(file.readText()) }.getOrElse {
            runCatching { file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"), overwrite = false) }
            empty
        }
    }

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("Could not replace ${file.name}")
        }
    }
}
