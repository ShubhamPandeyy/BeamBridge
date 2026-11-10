package app.beambridge.core

import app.beambridge.core.data.*
import kotlin.test.*

class GridTest {
    private val keys = listOf("POWER", "VOL_MUTE", "UP", "DOWN", "LEFT", "RIGHT", "OK", "VOL_UP", "VOL_DOWN", "HOME", "BACK", "MENU", "NETFLIX", "INPUT")
    private val layout = RemoteLayout.of(keys, onScreen = listOf("NETFLIX", "INPUT"))

    @Test fun defaultGridFitsFiveByEight() {
        val g = RemoteGrid.resolve(layout, emptyList(), 5, 8)
        assertEquals(8, g.rows)
        assertTrue(g.tiles.all { it.col + it.w <= 5 })
        assertEquals(Tile("@DPAD", 1, 1, 3, 3), g.tiles.first { it.id == "@DPAD" })
        for (a in g.tiles) for (b in g.tiles) if (a !== b) assertFalse(a.overlaps(b), "$a overlaps $b")
    }

    @Test fun moveResizeAndCollisions() {
        val g = RemoteGrid.resolve(layout, emptyList(), 5, 8)
        val ok = RemoteGrid.moveTile(g, "NETFLIX", 3, 7, 2, 1).getOrThrow()
        assertEquals(Tile("NETFLIX", 3, 7, 2, 1), ok.first { it.id == "NETFLIX" })
        assertTrue(RemoteGrid.moveTile(g, "NETFLIX", 1, 1, 1, 1).isFailure, "on top of the D-pad")
        assertTrue(RemoteGrid.moveTile(g, "@DPAD", 0, 1, 2, 2).isFailure, "below minimum size")
        assertTrue(RemoteGrid.moveTile(g, "POWER", 4, 0, 2, 1).isFailure, "off the right edge")
    }

    @Test fun resolveDropsGoneKeysAndPlacesNewOnes() {
        val saved = listOf(Tile("POWER", 0, 0), Tile("GONE", 1, 0), Tile("@DPAD", 1, 1, 3, 3))
        val g = RemoteGrid.resolve(layout, saved, 5, 8)
        assertNull(g.tiles.firstOrNull { it.id == "GONE" })
        assertEquals(Tile("POWER", 0, 0), g.tiles.first { it.id == "POWER" })
        assertNotNull(g.tiles.firstOrNull { it.id == "NETFLIX" })
        for (a in g.tiles) for (b in g.tiles) if (a !== b) assertFalse(a.overlaps(b))
    }
}

class StorageTest {
    @Test fun corruptFileIsKeptAside() {
        val dir = kotlin.io.path.createTempDirectory("bb").toFile()
        val f = java.io.File(dir, "devices.json").apply { writeText("{ not json") }
        val store = JsonFileDeviceStore(f)
        kotlin.test.assertTrue(store.devices.value.isEmpty())
        store.add(SavedDevice("tv", "TV", DeviceType.TV))
        val backup = dir.listFiles()!!.single { it.name.startsWith("devices.json.corrupt-") }
        kotlin.test.assertEquals("{ not json", backup.readText())
        dir.deleteRecursively()
    }
}
