package app.beambridge.core.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigInteger

/** How a saved button is stored. Everything resolves to an [IrSignal] at transmit time. */
@Serializable
sealed interface IrCode {
    /**
     * Protocol + hex payload, e.g. SAMSUNG_NEC / 0xE0E040BF / 32 bits.
     * [protocolId] lets "PROTOCOL_n" resolve to a bytecode program from the protocol catalog.
     * [toggleMask] (hex, e.g. "0x800") is XOR-ed into the code on every other press (RC5/RC6 toggle bit).
     */
    @Serializable
    @SerialName("hex")
    data class Hex(
        val protocol: String,
        val code: String,
        val bits: Int = 0,
        val protocolId: Int? = null,
        val toggleMask: String? = null,
        val frequency: Int? = null,
    ) : IrCode

    /** Raw alternating mark/space durations in µs. */
    @Serializable
    @SerialName("raw")
    data class Raw(val frequency: Int, val pattern: List<Int>) : IrCode

    @Serializable
    @SerialName("pronto")
    data class Pronto(val pronto: String) : IrCode
}

fun interface ProgramLookup {
    fun program(protocolId: Int): IrProgram?
    companion object { val NONE = ProgramLookup { null } }
}

object IrCodeEncoder {
    /**
     * @param toggleState current toggle phase for protocols with a toggle bit; callers flip it per press.
     */
    fun encode(code: IrCode, toggleState: Boolean = false, programs: ProgramLookup = ProgramLookup.NONE): IrSignal = when (code) {
        is IrCode.Raw -> {
            if (code.pattern.isEmpty()) throw IrEncodingException("Raw pattern is empty")
            if (code.pattern.any { it <= 0 }) throw IrEncodingException("Raw pattern values must be positive")
            IrSignal(code.frequency, code.pattern.toIntArray())
        }
        is IrCode.Pronto -> app.beambridge.core.ir.Pronto.decode(code.pronto)
        is IrCode.Hex -> encodeHex(code, toggleState, programs)
    }

    private fun encodeHex(code: IrCode.Hex, toggleState: Boolean, programs: ProgramLookup): IrSignal {
        val protocol = IrProtocol.resolve(code.protocol, code.protocolId)
        val program = if (protocol == null) code.protocolId?.let { programs.program(it) } else null
        if (protocol == null && program == null) throw IrEncodingException("Unsupported protocol '${code.protocol}'")
        val width = if (code.bits > 0) code.bits else protocol?.defaultBits ?: 0
        var bits = Bits.fromHex(code.code, width)
        if (toggleState && !code.toggleMask.isNullOrBlank()) {
            val mask = BigInteger(code.toggleMask.trim().removePrefix("0x").removePrefix("0X"), 16)
            val value = bits.fold(BigInteger.ZERO) { acc, b -> acc.shiftLeft(1).let { if (b) it.setBit(0) else it } }
            val flipped = value.xor(mask)
            bits = BooleanArray(bits.size) { i -> flipped.testBit(bits.size - 1 - i) }
        }
        return protocol?.encode(bits, code.frequency ?: protocol.defaultFrequency) ?: program!!.render(bits)
    }

    /** Parses "9000,4500,560,..." (commas/spaces) as used by POST /api/raw. */
    fun parsePattern(text: String): List<Int> =
        text.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.map {
            it.trim().removePrefix("+").removePrefix("-").toIntOrNull()
                ?: throw IrEncodingException("Bad pattern value '$it'")
        }
}
