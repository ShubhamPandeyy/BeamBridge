package app.beambridge.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.beambridge.core.data.StandardKeys
import app.beambridge.core.data.TvRemote
import java.io.File

data class StbProvider(val id: Int, val title: String, val type: String?)

/** Read-only access to the DTH / cable provider remotes in assets/lcndb.db. Blocking: call from Dispatchers.IO. */
class StbRepository(private val context: Context) {
    private val db: SQLiteDatabase? by lazy { open() }

    private fun open(): SQLiteDatabase? = runCatching {
        val target = File(context.filesDir, "lcndb.db")
        val size = runCatching { context.assets.openFd("lcndb.db").use { it.length } }.getOrDefault(-1L)
        if (!target.exists() || size < 0 || target.length() != size) {
            context.assets.open("lcndb.db").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
    }.getOrNull()

    val available: Boolean get() = db != null

    fun providers(query: String): List<StbProvider> {
        val d = db ?: return emptyList()
        val sql = "SELECT p.id, p.title, p.type FROM providers p " +
            "WHERE EXISTS (SELECT 1 FROM provider_remotes pr WHERE pr.provider_id = p.id) AND p.title LIKE ? " +
            "ORDER BY CASE p.type WHEN 'DTH' THEN 0 ELSE 1 END, p.title LIMIT 300"
        return d.rawQuery(sql, arrayOf("%${query.trim()}%")).use { c ->
            buildList { while (c.moveToNext()) add(StbProvider(c.getInt(0), c.getString(1).orEmpty(), c.getString(2))) }
        }
    }

    /** Candidate remotes of a provider, shaped like TV remotes so the same encoder and wizard code applies. */
    fun remotes(providerId: Int): List<TvRemote> {
        val d = db ?: return emptyList()
        val heads = d.rawQuery(
            "SELECT r.id, r.protocol, r.length, r.toggle_mask FROM provider_remotes pr " +
                "JOIN remotes r ON r.id = pr.remote_id WHERE pr.provider_id = ? ORDER BY r.id",
            arrayOf(providerId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(listOf(c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3).orEmpty())) } }
        return heads.distinctBy { it[0] }.mapNotNull { h ->
            val buttons = linkedMapOf<String, String>()
            d.rawQuery("SELECT \"key\", code FROM key_codes WHERE remote_id = ? ORDER BY \"key\"", arrayOf(h[0].toString())).use { c ->
                while (c.moveToNext()) {
                    val key = c.getInt(0)
                    val code = c.getString(1) ?: continue
                    buttons[StandardKeys.name(key) ?: "KEY_$key"] = code
                }
            }
            if (buttons.isEmpty()) null
            else TvRemote(
                remoteId = h[0] as Int,
                protocolId = h[1] as Int,
                protocolName = "PROTOCOL_${h[1]}",
                bitLength = h[2] as Int,
                toggleMask = (h[3] as String).takeIf { it.isNotBlank() },
                buttons = buttons,
            )
        }
    }
}
