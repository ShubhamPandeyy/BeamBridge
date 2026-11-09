package app.beambridge.core.ir

import java.util.Base64

/**
 * Interpreter for the compact "program" bytecode stored in ir_protocols.json / lcndb.db (remote_protocols.program).
 * The format is undocumented; it was reverse-engineered from the shipped datasets (154 of 156 programs decode).
 *
 *   00 hh ll        carrier frequency in Hz (big endian)
 *   28 n            time unit = n carrier cycles (default 1 cycle)
 *   20 n [m s]*n    n bit symbols as (mark, -space) pairs, 1-byte signed values, in units
 *   24 n [m s]*n    same with 16-bit values
 *   30/34 n         mark  n units          31/35 hh ll   mark  (16-bit)
 *   38/3c n         space n units          39/3d hh ll   space (16-bit)
 *   40/48 n         send the next n payload bits using the symbol table
 *   50/58           send all remaining payload bits
 * With 2 symbols a symbol carries 1 bit, with 4 symbols 2 bits (MSB first). Payload bits are sent MSB first.
 * Anything else (opcodes 0x12 / 0x18 appear in two programs) marks the program as unsupported.
 */
class IrProgram private constructor(
    val frequency: Int,
    private val unit: Int,
    private val symbols: List<IntArray>, // each = [markUnits, spaceUnits]
    private val ops: List<Op>,
) {
    private sealed interface Op {
        data class Mark(val units: Int) : Op
        data class Space(val units: Int) : Op
        data class Send(val bits: Int) : Op // bits <= 0 → all remaining
    }

    private fun us(units: Int): Int = (units.toLong() * unit * 1_000_000L / frequency).toInt()

    fun render(bits: BooleanArray): IrSignal {
        val p = PulseBuilder()
        val perSymbol = when (symbols.size) { 2 -> 1; 4 -> 2; 8 -> 3; 16 -> 4; else -> 1 }
        var pos = 0
        for (op in ops) when (op) {
            is Op.Mark -> p.mark(us(op.units))
            is Op.Space -> p.space(us(op.units))
            is Op.Send -> {
                val end = if (op.bits <= 0) bits.size else minOf(bits.size, pos + op.bits)
                while (pos < end) {
                    var idx = 0
                    for (k in 0 until perSymbol) idx = (idx shl 1) or if (pos + k < end && bits[pos + k]) 1 else 0
                    val s = symbols.getOrNull(idx) ?: throw IrEncodingException("Program has no symbol $idx")
                    p.mark(us(s[0])).space(us(s[1]))
                    pos += perSymbol
                }
                pos = end
            }
        }
        val pattern = p.build()
        if (pattern.isEmpty()) throw IrEncodingException("Program produced no pulses")
        return IrSignal(frequency, pattern)
    }

    companion object {
        fun parseBase64(program: String): IrProgram? = runCatching {
            val clean = program.trim()
            parse(Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4)))
        }.getOrNull()

        fun parse(b: ByteArray): IrProgram? {
            fun u(i: Int) = b[i].toInt() and 0xFF
            fun s8(i: Int) = b[i].toInt()
            fun s16(i: Int) = (b[i].toInt() shl 8) or u(i + 1)
            var freq = 38_000
            var unit = 1
            var symbols = emptyList<IntArray>()
            val ops = ArrayList<Op>()
            var i = 0
            try {
                while (i < b.size) {
                    when (val o = u(i)) {
                        0x00 -> { freq = (u(i + 1) shl 8) or u(i + 2); i += 3 }
                        0x28 -> { unit = u(i + 1); i += 2 }
                        0x20, 0x24 -> {
                            val n = u(i + 1); val wide = o == 0x24; val w = if (wide) 2 else 1
                            symbols = List(n) { k ->
                                val at = i + 2 + k * 2 * w
                                val m = if (wide) s16(at) else s8(at)
                                val s = if (wide) s16(at + 2) else s8(at + 1)
                                intArrayOf(kotlin.math.abs(m), kotlin.math.abs(s))
                            }
                            i += 2 + 2 * n * w
                        }
                        0x30, 0x34 -> { ops += Op.Mark(u(i + 1)); i += 2 }
                        0x31, 0x35 -> { ops += Op.Mark(u(i + 1) shl 8 or u(i + 2)); i += 3 }
                        0x38, 0x3c -> { ops += Op.Space(u(i + 1)); i += 2 }
                        0x39, 0x3d -> { ops += Op.Space(u(i + 1) shl 8 or u(i + 2)); i += 3 }
                        0x40, 0x48 -> { ops += Op.Send(u(i + 1)); i += 2 }
                        0x50, 0x58 -> { ops += Op.Send(0); i += 1 }
                        else -> return null
                    }
                }
            } catch (_: IndexOutOfBoundsException) {
                return null
            }
            if (freq <= 0 || unit <= 0 || symbols.isEmpty() || ops.none { it is Op.Send }) return null
            return IrProgram(freq, unit, symbols, ops)
        }
    }
}
