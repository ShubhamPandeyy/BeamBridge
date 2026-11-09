package app.beambridge.core.ir

/** A ready-to-transmit IR burst: carrier frequency (Hz) + alternating mark/space durations (µs), starting with a mark. */
data class IrSignal(val frequency: Int, val pattern: IntArray) {
    val durationMicros: Long get() = pattern.fold(0L) { acc, v -> acc + v }

    override fun equals(other: Any?): Boolean =
        other is IrSignal && other.frequency == frequency && other.pattern.contentEquals(pattern)

    override fun hashCode(): Int = 31 * frequency + pattern.contentHashCode()
    override fun toString(): String = "IrSignal(${frequency}Hz, ${pattern.size} pulses, ${durationMicros}µs)"
}

/** Anything that can physically emit IR (ConsumerIrManager on Android, a fake in tests). */
interface IrEmitter {
    val isAvailable: Boolean
    fun transmit(signal: IrSignal)
}

class IrEncodingException(message: String) : IllegalArgumentException(message)

/** Builds a mark/space array, merging consecutive durations of the same kind (needed for Manchester codes). */
class PulseBuilder {
    private val out = ArrayList<Int>(128)
    private var lastIsMark: Boolean? = null

    fun mark(us: Int) = add(true, us)
    fun space(us: Int) = add(false, us)

    private fun add(isMark: Boolean, us: Int): PulseBuilder {
        if (us <= 0) return this
        if (out.isEmpty() && !isMark) return this // a pattern must start with a mark
        if (lastIsMark == isMark) out[out.lastIndex] = out.last() + us else out.add(us)
        lastIsMark = isMark
        return this
    }

    fun build(): IntArray = out.toIntArray()
}

object Bits {
    /** Parses "0xE0E040BF" / "E0E0 40BF" into exactly [bitLength] bits, MSB first. bitLength <= 0 → 4 bits per hex digit. */
    fun fromHex(hex: String, bitLength: Int = 0): BooleanArray {
        val clean = hex.trim().removePrefix("0x").removePrefix("0X").replace(Regex("[\\s_:-]"), "")
        if (clean.isEmpty() || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            throw IrEncodingException("Not a hex code: '$hex'")
        }
        val value = java.math.BigInteger(clean, 16)
        val n = if (bitLength > 0) bitLength else clean.length * 4
        return BooleanArray(n) { i -> value.testBit(n - 1 - i) }
    }

    fun fromLong(value: Long, bitLength: Int): BooleanArray =
        BooleanArray(bitLength) { i -> (value ushr (bitLength - 1 - i)) and 1L == 1L }

    fun toHex(bits: BooleanArray): String {
        var v = java.math.BigInteger.ZERO
        bits.forEach { v = v.shiftLeft(1).let { s -> if (it) s.setBit(0) else s } }
        val digits = (bits.size + 3) / 4
        return "0x" + v.toString(16).padStart(digits, '0')
    }
}
