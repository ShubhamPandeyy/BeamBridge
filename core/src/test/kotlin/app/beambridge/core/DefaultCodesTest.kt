package app.beambridge.core

import app.beambridge.core.data.*
import app.beambridge.core.ir.*
import java.io.File
import kotlin.test.*

/** Guards the "codes don't work out of the box" class of bugs across the whole bundled TV dataset. */
class DefaultCodesTest {
    private val ds by lazy {
        val dir = listOf("../app/src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
        Datasets.load { File(dir, it).takeIf(File::exists)?.inputStream() }
    }

    /** Decodes a mark/space pattern of a pulse-distance protocol back to the bit string (MSB first). */
    private fun decode(p: IntArray, oneSpace: Int, zeroSpace: Int): String {
        val sb = StringBuilder()
        var i = 2 // skip header mark+space
        while (i + 1 < p.size - 1) {
            val s = p[i + 1]
            sb.append(if (kotlin.math.abs(s - oneSpace) < kotlin.math.abs(s - zeroSpace)) '1' else '0')
            i += 2
        }
        return sb.toString()
    }

    @Test fun everyNecAndSamsungCodeRoundTripsThroughThePulseTrain() {
        var checked = 0
        for (m in ds.tv.manufacturers) for (r in m.remotes) {
            val proto = IrProtocol.resolve(r.protocolName, r.protocolId)
            if (proto != IrProtocol.Nec && proto != IrProtocol.Samsung) continue
            for (name in r.buttons.keys) {
                val code = r.irCode(name) as? IrCode.Hex ?: continue
                val sig = IrCodeEncoder.encode(code)
                val hw = sig.forHardware()
                assertEquals(0, hw.size % 2, "${m.name}/${r.remoteId}/$name must be even for the Qualcomm HAL")
                assertTrue(hw.all { it > 0 })
                val width = if (code.bits > 0) code.bits else 32
                val expected = Bits.fromHex(code.code, width).joinToString("") { if (it) "1" else "0" }
                assertEquals(expected, decode(sig.pattern, 1578, 552), "${m.name}/${r.remoteId}/$name bit order")
                checked++
            }
        }
        assertTrue(checked > 1000, "expected the dataset to be exercised, got $checked")
    }

    @Test fun reportSupportCoverage() {
        var total = 0; var failed = 0; val bad = sortedSetOf<String>()
        for (m in ds.tv.manufacturers) for (r in m.remotes) for (name in r.buttons.keys) {
            val code = r.irCode(name) ?: continue
            if (!r.supported) continue
            total++
            runCatching { IrCodeEncoder.encode(code).forHardware() }
                .onFailure { failed++; bad += "${r.protocolName}(${r.protocolId})" }
        }
        println("COVERAGE total=$total failed=$failed unsupported=$bad")
        assertEquals(0, failed, "supported remotes must always encode: $bad")
    }
}
