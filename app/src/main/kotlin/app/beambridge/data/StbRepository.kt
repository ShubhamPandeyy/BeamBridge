package app.beambridge.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.beambridge.core.data.Brand
import app.beambridge.core.data.StandardKeys
import app.beambridge.core.data.StbSource
import app.beambridge.core.data.TvRemote
import java.io.File

/** Read-only access to the DTH / cable provider remotes in assets/lcndb.db. Blocking: call off the main thread. */
class StbRepository(private val context: Context) : StbSource {
    private val db: SQLiteDatabase? by lazy { open() }

    private fun open(): SQLiteDatabase? = runCatching {
        val target = File(context.filesDir, "lcndb.db")
        val size = runCatching { context.assets.openFd("lcndb.db").use { it.length } }.getOrDefault(-1L)
        if (!target.exists() || size < 0 || target.length() != size) {
            context.assets.open("lcndb.db").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
    }.getOrNull()

    override fun providers(query: String): List<Brand> {
        val d = db ?: return emptyList()
        val sql = "SELECT p.id, p.title, COUNT(pr.remote_id) FROM providers p JOIN provider_remotes pr ON pr.provider_id = p.id " +
            "WHERE p.title LIKE ? GROUP BY p.id ORDER BY CASE p.type WHEN 'DTH' THEN 0 ELSE 1 END, p.title LIMIT 300"
        return d.rawQuery(sql, arrayOf("%${query.trim()}%")).use { c ->
            buildList { while (c.moveToNext()) add(Brand(c.getInt(0), c.getString(1).orEmpty(), c.getInt(2))) }
        }
    }

    override fun remotes(providerId: Int): List<TvRemote> = load(
        "SELECT DISTINCT r.id, r.protocol, r.length, r.toggle_mask FROM provider_remotes pr JOIN remotes r ON r.id = pr.remote_id " +
            "WHERE pr.provider_id = ? ORDER BY r.id",
        arrayOf(providerId.toString()),
    )

    private val all by lazy {
        load("SELECT DISTINCT r.id, r.protocol, r.length, r.toggle_mask FROM provider_remotes pr JOIN remotes r ON r.id = pr.remote_id ORDER BY r.id", emptyArray())
    }

    override fun allRemotes(): List<TvRemote> = all

    private data class Head(val id: Int, val protocol: Int, val length: Int, val mask: String)

    private fun load(sql: String, args: Array<String>): List<TvRemote> {
        val d = db ?: return emptyList()
        val heads = d.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(Head(c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3).orEmpty())) }
        }
        return heads.mapNotNull { h ->
            val buttons = linkedMapOf<String, String>()
            d.rawQuery("SELECT \"key\", code FROM key_codes WHERE remote_id = ? ORDER BY \"key\"", arrayOf(h.id.toString())).use { c ->
                while (c.moveToNext()) {
                    val code = c.getString(1) ?: continue
                    buttons[StandardKeys.name(c.getInt(0)) ?: "KEY_${c.getInt(0)}"] = code
                }
            }
            if (buttons.isEmpty()) null
            else TvRemote(h.id, h.protocol, "PROTOCOL_${h.protocol}", h.length, h.mask.takeIf { it.isNotBlank() }, buttons)
        }
    }
}
