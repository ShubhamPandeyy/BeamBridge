package app.beambridge.core.ir

/** Pronto hex ("0000 006D 0022 0002 0157 00AC ...") ⇄ raw pulses. Only learned (0000) codes are supported. */
object Pronto {
    private const val CLOCK = 0.241246 // µs per Pronto frequency unit

    fun decode(pronto: String): IrSignal {
        val words = pronto.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map {
            it.toIntOrNull(16) ?: throw IrEncodingException("Bad Pronto word '$it'")
        }
        if (words.size < 6) throw IrEncodingException("Pronto code too short")
        if (words[0] != 0) throw IrEncodingException("Only raw Pronto codes (type 0000) are supported")
        val freqUnit = words[1].takeIf { it > 0 } ?: throw IrEncodingException("Pronto frequency is zero")
        val frequency = (1_000_000.0 / (freqUnit * CLOCK)).toInt()
        val pairs = words[2] + words[3]
        val bursts = words.drop(4)
        if (bursts.size < pairs * 2) throw IrEncodingException("Pronto code declares $pairs pairs but has ${bursts.size / 2}")
        val period = 1_000_000.0 / frequency
        val pattern = bursts.take(pairs * 2).map { (it * period).toInt().coerceAtLeast(1) }.toIntArray()
        return IrSignal(frequency, pattern)
    }

    fun encode(signal: IrSignal): String {
        val freqUnit = (1_000_000.0 / (signal.frequency * CLOCK)).toInt()
        val period = 1_000_000.0 / signal.frequency
        val p = if (signal.pattern.size % 2 == 0) signal.pattern else signal.pattern + 10_000
        val words = listOf(0, freqUnit, p.size / 2, 0) + p.map { (it / period).toInt().coerceAtLeast(1) }
        return words.joinToString(" ") { "%04X".format(it) }
    }
}
