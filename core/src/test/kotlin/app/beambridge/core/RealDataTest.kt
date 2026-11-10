package app.beambridge.core

import app.beambridge.core.data.*
import app.beambridge.core.ir.*
import java.io.File
import kotlin.test.*

private val assets: File = listOf("../app/src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
private val datasets: Datasets by lazy { Datasets.load { name -> File(assets, name).takeIf { it.exists() }?.inputStream() } }

class RealDataTest {
    @Test fun datasetsLoad() {
        assertEquals(emptyList(), datasets.problems)
        assertEquals(71, datasets.tv.manufacturers.size)
        assertEquals(149, datasets.tv.remoteCount) // 148 + TCL RCA remote
        assertEquals(156, datasets.protocols.protocols.size)
        assertEquals(208, datasets.ac.remoteCount)
        println("protocol programs decoded: ${datasets.protocols.supportedCount}/156")
        assertTrue(datasets.protocols.supportedCount >= 150)
    }

    @Test fun everySupportedTvButtonEncodes() {
        var ok = 0; var skipped = 0
        for (m in datasets.tv.manufacturers) for (r in m.remotes) {
            if (!r.isSupported(datasets.protocols)) { skipped++; continue }
            for (name in r.buttons.keys) {
                val sig = IrCodeEncoder.encode(r.irCode(name)!!, toggleState = true, programs = datasets.protocols)
                assertTrue(sig.pattern.size > 4, "${m.name}/${r.remoteId}/$name")
                assertTrue(sig.pattern.all { it > 0 })
                assertTrue(sig.frequency in 30_000..60_000, "freq ${sig.frequency} ${m.name}/${r.remoteId}")
                ok++
            }
        }
        println("TV buttons encoded: $ok, remotes skipped (no protocol): $skipped")
        assertTrue(ok > 2500)
    }

    @Test fun samsungPowerMatchesNec() {
        val r = datasets.tv.remote(3, 52)!!
        val sig = IrCodeEncoder.encode(r.irCode("POWER")!!)
        assertEquals(38_000, sig.frequency)
        assertEquals(4500, sig.pattern[0]); assertEquals(4500, sig.pattern[1])
        assertEquals(2 + 64 + 1, sig.pattern.size)
    }

    @Test fun lgProtocolProgramRendersLikeNec() {
        // "LG Protocol 1": 28-bit frame, 16/8 unit header, 1/1 and 1/3 bits, 1-unit stop mark.
        val sig = datasets.protocols.program(5)!!.render(Bits.fromHex("0x88c0051", 28))
        assertEquals(1 + 1 + 28 * 2 + 1, sig.pattern.size) // header mark+space, 28 bit pairs, stop mark
        assertTrue(sig.pattern[0] in 7_500..8_500, "header mark ${sig.pattern[0]}")
        assertTrue(sig.pattern[1] in 3_500..4_500)
    }

    @Test fun acRemotesBuildFrames() {
        var ok = 0; var unsupported = 0
        for (m in datasets.ac.manufacturers) for (r in m.remotes) {
            if (!r.supported) { unsupported++; continue }
            for (mode in r.modes) for (t in listOf(r.temperatures.first, r.temperatures.last)) {
                val s = AcCodec.signal(r, AcState(true, mode, t, r.fanSpeeds.firstOrNull() ?: "auto"))
                assertTrue(s.pattern.isNotEmpty())
                ok++
            }
            AcCodec.signal(r, AcState(false))
        }
        println("AC frames built: $ok, unsupported remotes: $unsupported of ${datasets.ac.remoteCount}")
        assertTrue(ok > 500)
    }

    @Test fun acRejectsUnsupportedValues() {
        val r = datasets.ac.manufacturers.flatMap { it.remotes }.first { it.supported }
        assertFailsWith<app.beambridge.core.ir.IrEncodingException> { AcCodec.frame(r, AcState(true, "turbo", 24, r.fanSpeeds.firstOrNull() ?: "auto")) }
        assertFailsWith<app.beambridge.core.ir.IrEncodingException> { AcCodec.frame(r, AcState(true, r.modes.first(), 99, r.fanSpeeds.firstOrNull() ?: "auto")) }
        if (r.fanSpeeds.isNotEmpty()) assertFailsWith<app.beambridge.core.ir.IrEncodingException> { AcCodec.frame(r, AcState(true, r.modes.first(), 24, "hurricane")) }
    }

    @Test fun lgAcPowerOffIsKnownFrame() {
        val lg = datasets.ac.manufacturers.flatMap { it.remotes }.first { it.name.startsWith("LG AC 1") }
        assertEquals(java.math.BigInteger("88c0051", 16), AcCodec.frame(lg, AcState(power = false)))
    }

    @Test fun rc5ToggleFlipsMask() {
        val code = IrCode.Hex("RC5", "0x0fef", 12, 1, "0x800")
        val a = IrCodeEncoder.encode(code, false)
        val b = IrCodeEncoder.encode(code, true)
        assertNotEquals(a, b)
        assertEquals(36_000, a.frequency)
    }
}

class InputTest {
    @Test fun freeTextCodes() {
        assertEquals(IrCode.Hex("NEC", "0x20DF10EF", 32), CodeInput.parseText("NEC 0x20DF10EF 32"))
        assertEquals(IrCode.Hex("NEC", "0x20DF10EF", 0), CodeInput.parseText("0x20DF10EF"))
        assertTrue(CodeInput.parseText("0000 006D 0002 0000 0157 00AC 0015 0689") is IrCode.Pronto)
        assertEquals(IrCode.Raw(38000, listOf(9000, 4500, 560)), CodeInput.parseText("9000, 4500, 560"))
    }

    @Test fun rcaMatchesTclLearnedTiming() {
        val sig = IrCodeEncoder.encode(IrCode.Hex("RCA", "0xF2A0D5", 24))
        assertEquals(listOf(4000, 4000, 500, 2000), sig.pattern.take(4))
        assertEquals(2 + 48 + 1, sig.pattern.size)
    }
}
