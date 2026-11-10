package app.beambridge.core

import app.beambridge.core.data.*
import app.beambridge.core.ir.*
import java.io.File
import kotlin.test.*

class HardwareTest {
    private val ds by lazy {
        val dir = listOf("../app/src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
        Datasets.load { File(dir, it).takeIf(File::exists)?.inputStream() }
    }

    @Test fun everyPatternIsEvenAndEndsOnLongSpace() {
        val sigs = listOf(
            IrCodeEncoder.encode(IrCode.Hex("NEC", "0x20DF10EF", 32)),
            IrCodeEncoder.encode(IrCode.Hex("RCA", "0xF2A0D5", 24)),
            IrCodeEncoder.encode(IrCode.Hex("SAMSUNG", "0xE0E040BF", 32)),
        )
        for (s in sigs) {
            assertEquals(1, s.pattern.size % 2, "raw encoders end on a stop mark: $s")
            val hw = s.forHardware()
            assertEquals(0, hw.size % 2)
            assertTrue(hw.last() >= IrSignal.TRAILING_GAP)
            assertContentEquals(s.pattern, hw.copyOf(s.pattern.size), "only a trailing space is added")
        }
    }

    @Test fun necUsesCarrierCycleTimings() {
        val p = IrCodeEncoder.encode(IrCode.Hex("NEC", "0x80000000", 32)).pattern
        assertEquals(1578, p[3], "first bit is 1 → 60 cycles @38 kHz")
        assertEquals(552, p[2])
    }

    @Test fun rcaTimingsUnchanged() {
        val p = IrCodeEncoder.encode(IrCode.Hex("RCA", "0x800000", 24)).pattern
        assertEquals(listOf(4000, 4000, 500, 2000, 500, 1000), p.take(6))
    }

    @Test fun acFramesKeepFullWidth() {
        var checked = 0
        for (r in ds.ac.manufacturers.flatMap { it.remotes }) {
            if (!r.supported) continue
            val off = AcCodec.frameWithWidth(r, AcState(power = false))
            val hex = r.codes["POWER"]?.removePrefix("0x") ?: continue
            assertTrue(off.second >= minOf(hex.length * 4, maxOf(r.bitLength, hex.length * 4)) - 3, "${r.name}: POWER frame cut to ${off.second} bits")
            assertEquals(java.math.BigInteger(hex, 16), off.first)
            checked++
        }
        assertTrue(checked > 50)
    }
}
