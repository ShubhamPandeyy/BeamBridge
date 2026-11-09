package app.beambridge.core.data

/**
 * Where each button goes on the remote-style layout (shared by the app and the web UI):
 *
 *   [mute]            [power]
 *          ( D-pad + OK )
 *   [ −   volume   + ]  [ −  channel  + ]
 *   [home]            [back]
 *   extras grid (4 columns, user-arrangeable, "" = empty cell)
 */
data class RemoteLayout(
    val mute: String?, val power: String?,
    val up: String?, val down: String?, val left: String?, val right: String?, val ok: String?,
    val volDown: String?, val volUp: String?, val chDown: String?, val chUp: String?,
    val home: String?, val back: String?,
    val extras: List<String>,
) {
    val hasDpad get() = listOfNotNull(up, down, left, right, ok).isNotEmpty()

    companion object {
        const val COLUMNS = 4

        fun of(visible: List<String>, grid: List<String>, exclude: Set<String> = emptySet()): RemoteLayout {
            val names = visible.filterNot { it.uppercase() in exclude }
            val used = mutableSetOf<String>()
            fun pick(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { k -> names.firstOrNull { it.equals(k, true) && it !in used } }?.also { used += it }
            val l = RemoteLayout(
                mute = pick("VOL_MUTE", "MUTE"), power = pick("POWER", "POWER_TOGGLE"),
                up = pick("UP"), down = pick("DOWN"), left = pick("LEFT"), right = pick("RIGHT"), ok = pick("OK", "SELECT", "ENTER"),
                volDown = pick("VOL_DOWN"), volUp = pick("VOL_UP"), chDown = pick("CH_DOWN"), chUp = pick("CH_UP"),
                home = pick("HOME", "TCL_HOME", "MENU"), back = pick("BACK", "EXIT"),
                extras = emptyList(),
            )
            val rest = names.filter { it !in used }
            val cells = if (grid.isEmpty()) rest else {
                val placed = grid.map { n -> if (n.isNotEmpty() && rest.any { it == n }) n else "" }
                placed + rest.filter { it !in placed }
            }
            return l.copy(extras = cells.dropLastWhile { it.isEmpty() })
        }
    }
}
