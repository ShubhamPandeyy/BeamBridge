package app.beambridge.core.data

import java.io.InputStream

/** The bundled reference datasets. Missing or malformed files degrade to empty catalogs and are reported in [problems]. */
class Datasets(
    val tv: TvCatalog,
    val ac: AcCatalog,
    val protocols: ProtocolCatalog,
    val problems: List<String> = emptyList(),
) {
    companion object {
        const val TV_FILE = "tv_remotes.json"
        const val AC_FILE = "ac_remotes.json"
        const val PROTOCOL_FILE = "ir_protocols.json"
        const val LCN_DB_FILE = "lcndb.db"
        /** Optional extra TV remotes in tv_remotes.json format (e.g. the TCL RCA remote), merged by brand name. */
        const val EXTRA_TV_FILE = "extra_tv_remotes.json"

        val EMPTY = Datasets(TvCatalog.EMPTY, AcCatalog.EMPTY, ProtocolCatalog.EMPTY)

        /** @param open returns the asset stream or null if absent. */
        fun load(open: (String) -> InputStream?): Datasets {
            val problems = mutableListOf<String>()
            fun <T> read(name: String, empty: T, parse: (String) -> T): T {
                val text = runCatching { open(name)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                    ?: return empty.also { problems += "$name missing" }
                return runCatching { parse(text) }.getOrElse { problems += "$name: ${it.message}"; empty }
            }
            return Datasets(
                tv = read(TV_FILE, TvCatalog.EMPTY, TvCatalog::parse).merge(
                    runCatching { open(EXTRA_TV_FILE)?.use { TvCatalog.parse(it.readBytes().toString(Charsets.UTF_8)) } }.getOrNull() ?: TvCatalog.EMPTY,
                ),
                ac = read(AC_FILE, AcCatalog.EMPTY, AcCatalog::parse),
                protocols = read(PROTOCOL_FILE, ProtocolCatalog.EMPTY, ProtocolCatalog::parse),
                problems = problems,
            )
        }
    }
}
