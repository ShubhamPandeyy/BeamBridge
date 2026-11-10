package app.beambridge.core.data

import kotlinx.serialization.Serializable

/**
 * One item on the free-form remote grid. [id] is a button name, or a group:
 * "@DPAD" (arrows + OK), "@VOL" (volume −/+ pill), "@CH" (channel −/+ pill).
 * Position and size are in grid cells, (0,0) = top-left.
 */
@Serializable
data class Tile(val id: String, val col: Int, val row: Int, val w: Int = 1, val h: Int = 1) {
    val isGroup get() = id.startsWith("@")
    fun overlaps(o: Tile) = col < o.col + o.w && o.col < col + w && row < o.row + o.h && o.row < row + h
    fun cells() = sequence { for (r in row until row + h) for (c in col until col + w) yield(r to c) }
}

/**
 * iOS/launcher-style layout: every key or group is a tile on a [cols] × [rows] grid that the user can move and resize.
 * The grid grows downward when more keys are added than fit (the screen scrolls only then).
 */
data class RemoteGrid(val cols: Int, val rows: Int, val tiles: List<Tile>) {
    companion object {
        const val DEFAULT_COLS = 5
        const val DEFAULT_ROWS = 8
        val COLS = 3..6
        val ROWS = 4..16

        /** Smallest allowed size for each kind of tile. */
        fun minSize(id: String): Pair<Int, Int> = when (id) {
            "@DPAD" -> 3 to 3
            "@VOL", "@CH" -> 2 to 1
            else -> 1 to 1
        }

        fun maxSize(id: String, cols: Int): Pair<Int, Int> = when (id) {
            "@DPAD" -> cols to cols
            else -> cols to 3
        }

        /** Default arrangement built from the classic layout (mute/power, D-pad, volume, back/settings/home, extras). */
        fun default(l: RemoteLayout, cols: Int = DEFAULT_COLS): List<Tile> {
            val out = mutableListOf<Tile>()
            var row = 0
            if (l.mute != null || l.power != null) {
                l.mute?.let { out += Tile(it, 0, row) }; l.power?.let { out += Tile(it, cols - 1, row) }; row++
            }
            if (l.hasDpad) { val s = minOf(cols, 3.coerceAtLeast(cols - 2)); out += Tile("@DPAD", (cols - s) / 2, row, s, s); row += s }
            if (l.volDown != null || l.volUp != null) { out += Tile("@VOL", 0, row, cols, 1); row++ }
            if (l.chDown != null || l.chUp != null) { out += Tile("@CH", 0, row, cols, 1); row++ }
            if (l.back != null || l.settings != null || l.home != null) {
                l.back?.let { out += Tile(it, 0, row) }; l.settings?.let { out += Tile(it, cols / 2, row) }; l.home?.let { out += Tile(it, cols - 1, row) }; row++
            }
            var c = 0
            for (n in l.extras.filter { it.isNotEmpty() }) {
                out += Tile(n, c, row); c++
                if (c == cols) { c = 0; row++ }
            }
            return out
        }

        /** Ids that should be on screen for this layout. */
        fun wanted(l: RemoteLayout): List<String> = buildList {
            listOfNotNull(l.mute, l.power).forEach(::add)
            if (l.hasDpad) add("@DPAD")
            if (l.volDown != null || l.volUp != null) add("@VOL")
            if (l.chDown != null || l.chUp != null) add("@CH")
            listOfNotNull(l.back, l.settings, l.home).forEach(::add)
            l.extras.filter { it.isNotEmpty() }.forEach(::add)
        }

        /**
         * Reconciles a saved arrangement with what the device currently shows: drops tiles for keys that are gone
         * (hidden/removed), clamps anything out of bounds, and drops new keys into the first free 1×1 cell.
         */
        fun resolve(l: RemoteLayout, saved: List<Tile>, cols: Int, rows: Int): RemoteGrid {
            val wanted = wanted(l)
            if (saved.isEmpty()) return RemoteGrid(cols, maxOf(rows, (default(l, cols).maxOfOrNull { it.row + it.h } ?: 0)), default(l, cols))
            val keep = mutableListOf<Tile>()
            for (t in saved) {
                if (wanted.none { it.equals(t.id, true) } || keep.any { it.id.equals(t.id, true) }) continue
                val (mw, mh) = minSize(t.id)
                val w = t.w.coerceIn(mw, cols); val h = t.h.coerceAtLeast(mh)
                val fixed = t.copy(id = wanted.first { it.equals(t.id, true) }, w = w, h = h, col = t.col.coerceIn(0, cols - w), row = t.row.coerceAtLeast(0))
                keep += if (keep.none { it.overlaps(fixed) }) fixed else place(keep, fixed.id, w, h, cols)
            }
            for (id in wanted) if (keep.none { it.id.equals(id, true) }) {
                val (w, h) = if (id == "@VOL" || id == "@CH") cols to 1 else minSize(id)
                keep += place(keep, id, w, h, cols)
            }
            return RemoteGrid(cols, maxOf(rows, keep.maxOfOrNull { it.row + it.h } ?: 0), keep)
        }

        /** First free spot (row-major) for a [w]×[h] tile. */
        fun place(taken: List<Tile>, id: String, w: Int, h: Int, cols: Int): Tile {
            var r = 0
            while (true) {
                for (c in 0..cols - w) {
                    val t = Tile(id, c, r, w, h)
                    if (taken.none { it.overlaps(t) }) return t
                }
                r++
            }
        }

        /**
         * Moves/resizes [id] to the given cell rectangle. Returns the new tile list, or an error when the spot is taken,
         * out of bounds or below the tile's minimum size.
         */
        fun moveTile(grid: RemoteGrid, id: String, col: Int, row: Int, w: Int, h: Int): Result<List<Tile>> {
            val t = grid.tiles.firstOrNull { it.id.equals(id, true) } ?: return Result.failure(IllegalArgumentException("No tile '$id'"))
            val (mw, mh) = minSize(t.id); val (xw, xh) = maxSize(t.id, grid.cols)
            if (w < mw || h < mh) return Result.failure(IllegalArgumentException("${t.id} must be at least ${mw}×$mh"))
            if (w > xw || h > xh) return Result.failure(IllegalArgumentException("${t.id} can be at most ${xw}×$xh"))
            if (col < 0 || row < 0 || col + w > grid.cols) return Result.failure(IllegalArgumentException("Outside the ${grid.cols}-column grid"))
            val next = Tile(t.id, col, row, w, h)
            val others = grid.tiles.filter { it !== t }
            others.firstOrNull { it.overlaps(next) }?.let { return Result.failure(IllegalArgumentException("Spot taken by ${it.id}")) }
            return Result.success(others + next)
        }
    }
}
