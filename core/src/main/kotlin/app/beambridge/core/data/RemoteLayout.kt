package app.beambridge.core.data

/**
 * Where each button goes on the remote-style layout (shared by the app and the web UI):
 *
 *   [mute]            [power]
 *          ( D-pad + OK )
 *   [ −      volume      + ]
 *   [back]  [settings]  [home]
 *   added keys: channel pill + [extras] (keys the user added; placed on the free-form [RemoteGrid])
 *
 * The default screen only has the fixed slots. Other keys the device supports are listed in [available] and appear
 * once the user adds them ([SavedDevice.onScreen]); custom keys the user created are always shown.
 */
data class RemoteLayout(
    val mute: String?, val power: String?,
    val up: String?, val down: String?, val left: String?, val right: String?, val ok: String?,
    val volDown: String?, val volUp: String?, val chDown: String?, val chUp: String?,
    val home: String?, val back: String?, val settings: String?,
    val extras: List<String>,
    val available: List<String> = emptyList(),
) {
    val hasDpad get() = listOfNotNull(up, down, left, right, ok).isNotEmpty()

    companion object {
        fun of(
            visible: List<String>, exclude: Set<String> = emptySet(),
            onScreen: Collection<String> = emptyList(), custom: Collection<String> = emptyList(),
        ): RemoteLayout {
            val names = visible.filterNot { it.uppercase() in exclude }
            val shown = (onScreen + custom).map { it.uppercase() }.toSet()
            val used = mutableSetOf<String>()
            fun pick(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { k -> names.firstOrNull { it.equals(k, true) && it !in used } }?.also { used += it }
            fun pickAdded(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { k -> names.firstOrNull { it.equals(k, true) && it !in used && it.uppercase() in shown } }?.also { used += it }
            val l = RemoteLayout(
                mute = pick("VOL_MUTE", "MUTE"), power = pick("POWER", "POWER_TOGGLE"),
                up = pick("UP"), down = pick("DOWN"), left = pick("LEFT"), right = pick("RIGHT"), ok = pick("OK", "SELECT", "ENTER"),
                volDown = pick("VOL_DOWN"), volUp = pick("VOL_UP"), chDown = pickAdded("CH_DOWN"), chUp = pickAdded("CH_UP"),
                home = pick("HOME", "TCL_HOME", "SMART_HUB"), back = pick("BACK", "EXIT", "RETURN"),
                settings = pick("SETTINGS", "TCL_SETTINGS", "OPTIONS", "MENU", "TOOLS"),
                extras = emptyList(),
            )
            val rest = names.filter { it !in used }
            val on = rest.filter { it.uppercase() in shown }
            return l.copy(extras = on, available = rest.filter { it !in on })
        }
    }
}
