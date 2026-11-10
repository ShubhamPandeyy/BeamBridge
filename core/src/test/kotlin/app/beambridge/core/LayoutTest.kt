package app.beambridge.core

import app.beambridge.core.data.RemoteLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayoutTest {
    private val keys = listOf("POWER", "VOL_MUTE", "UP", "DOWN", "LEFT", "RIGHT", "OK", "VOL_UP", "VOL_DOWN",
        "CH_UP", "CH_DOWN", "HOME", "BACK", "MENU", "INPUT", "NETFLIX", "1")

    @Test fun defaultScreenHasOnlyFixedSlots() {
        val l = RemoteLayout.of(keys)
        assertEquals("MENU", l.settings)
        assertNull(l.chUp)
        assertEquals(emptyList(), l.extras)
        assertEquals(listOf("CH_UP", "CH_DOWN", "INPUT", "NETFLIX", "1"), l.available)
    }

    @Test fun addedAndCustomKeysAppear() {
        val l = RemoteLayout.of(keys + "MY_KEY", onScreen = listOf("CH_UP", "CH_DOWN", "netflix"), custom = listOf("MY_KEY"))
        assertEquals("CH_UP", l.chUp)
        assertEquals(listOf("NETFLIX", "MY_KEY"), l.extras)
        assertEquals(listOf("INPUT", "1"), l.available)
    }
}
