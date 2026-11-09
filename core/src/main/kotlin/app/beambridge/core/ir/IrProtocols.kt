package app.beambridge.core.ir

/**
 * Encoders for the IR protocols referenced by the bundled datasets.
 * Hex codes in the datasets are the protocol's raw bit stream written MSB-first (LIRC / IRremote convention),
 * e.g. Samsung POWER = 0xE0E040BF.
 */
sealed class IrProtocol(val key: String, val defaultFrequency: Int, val defaultBits: Int) {

    abstract fun encode(bits: BooleanArray, frequency: Int = defaultFrequency): IrSignal

    fun encodeHex(hex: String, bitLength: Int = 0, frequency: Int? = null): IrSignal =
        encode(Bits.fromHex(hex, if (bitLength > 0) bitLength else defaultBits), frequency ?: defaultFrequency)

    /** Pulse-distance protocols: every bit is a fixed mark followed by a short (0) or long (1) space. */
    open class PulseDistance(
        key: String,
        frequency: Int,
        bits: Int,
        private val headerMark: Int,
        private val headerSpace: Int,
        private val bitMark: Int,
        private val zeroSpace: Int,
        private val oneSpace: Int,
        private val stopMark: Int = bitMark,
        private val trailingGap: Int = 0,
    ) : IrProtocol(key, frequency, bits) {
        override fun encode(bits: BooleanArray, frequency: Int): IrSignal {
            val p = PulseBuilder().mark(headerMark).space(headerSpace)
            bits.forEach { p.mark(bitMark).space(if (it) oneSpace else zeroSpace) }
            p.mark(stopMark).space(trailingGap)
            return IrSignal(frequency, p.build())
        }
    }

    data object Nec : PulseDistance("NEC", 38_000, 32, 9000, 4500, 560, 560, 1690)
    data object Samsung : PulseDistance("SAMSUNG", 38_000, 32, 4500, 4500, 560, 560, 1690)
    data object Jvc : PulseDistance("JVC", 38_000, 16, 8400, 4200, 526, 524, 1574)
    data object Panasonic : PulseDistance("PANASONIC", 37_000, 48, 3456, 1728, 432, 432, 1296)
    /** RCA (used by TCL): 4 ms / 4 ms leader, 500 µs marks, 1000 µs (0) or 2000 µs (1) spaces, 24 bits MSB first. */
    data object Rca : PulseDistance("RCA", 38_000, 24, 4000, 4000, 500, 1000, 2000)
    data object Sharp : PulseDistance("SHARP", 38_000, 15, 0, 0, 320, 680, 1680)

    /** Sony SIRC: pulse-width, 12/15/20 bits, frame sent 3× on a 45 ms period. */
    data object Sony : IrProtocol("SONY", 40_000, 12) {
        override fun encode(bits: BooleanArray, frequency: Int): IrSignal {
            val p = PulseBuilder()
            repeat(3) { frame ->
                var used = 2400 + 600
                p.mark(2400).space(600)
                bits.forEach { b ->
                    val m = if (b) 1200 else 600
                    p.mark(m).space(600); used += m + 600
                }
                if (frame < 2) p.space((45_000 - used).coerceAtLeast(600))
            }
            return IrSignal(frequency, p.build())
        }
    }

    /** Philips RC5: Manchester, 889 µs half-bit, 1 = space→mark. Codes ≤12 bits get the two start bits prepended. */
    data object Rc5 : IrProtocol("RC5", 36_000, 13) {
        private const val T = 889
        override fun encode(bits: BooleanArray, frequency: Int): IrSignal {
            // 12-bit codes carry [toggle, address, command]; 13-bit codes already include the field bit.
            val frame = when {
                bits.size <= 12 -> booleanArrayOf(true, true) + bits
                bits.size == 13 -> booleanArrayOf(true) + bits
                else -> bits.copyOf()
            }
            val p = PulseBuilder()
            frame.forEach { if (it) p.space(T).mark(T) else p.mark(T).space(T) }
            return IrSignal(frequency, p.build())
        }
    }

    /** Philips RC6 mode 0: leader, start bit, 3 mode bits, double-width toggle (trailer) bit, then data. 1 = mark→space. */
    data object Rc6 : IrProtocol("RC6", 36_000, 20) {
        private const val T = 444
        override fun encode(bits: BooleanArray, frequency: Int): IrSignal {
            // 20-bit codes already contain mode(3)+toggle(1); the toggle is applied by the caller through the dataset's toggle mask.
            val body = if (bits.size >= 20) bits.copyOf() else BooleanArray(4) + bits
            val p = PulseBuilder().mark(6 * T).space(2 * T)
            p.mark(T).space(T) // start bit (always 1)
            body.forEachIndexed { i, b ->
                val t = if (i == 3) 2 * T else T
                if (b) p.mark(t).space(t) else p.space(t).mark(t)
            }
            return IrSignal(frequency, p.build())
        }
    }

    companion object {
        val all: List<IrProtocol> by lazy { listOf(Nec, Samsung, Sony, Rc5, Rc6, Jvc, Panasonic, Sharp, Rca) }

        /** Built-in encoder for a dataset protocol (by name, falling back to the numeric ids 0-8). Null → needs a program. */
        fun resolve(name: String?, id: Int? = null): IrProtocol? {
            val n = name.orEmpty().uppercase()
            return when {
                "SAMSUNG" in n -> Samsung
                "SONY" in n || "SIRC" in n -> Sony
                "RC6" in n -> Rc6
                "RC5" in n -> Rc5
                "PANASONIC" in n || "KASEIKYO" in n -> Panasonic
                "JVC" in n -> Jvc
                "SHARP" in n -> Sharp
                n == "RCA" || n.startsWith("RCA_") -> Rca
                n == "NEC" || n.startsWith("NEC") -> Nec
                n.startsWith("PROTOCOL_") || n.isEmpty() -> when (id) {
                    0 -> Nec; 1 -> Rc5; 2 -> Rc6; 6 -> Samsung; 7 -> Sony; 8 -> Panasonic
                    else -> null
                }
                else -> null
            }
        }
    }
}
