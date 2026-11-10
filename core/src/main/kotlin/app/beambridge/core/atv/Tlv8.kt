package app.beambridge.core.atv

/** TLV8 (tag, length, value) used inside pairing messages. Values over 255 bytes are split and re-joined. */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A

    /** kTLVError_Authentication: wrong PIN or unknown iPhone. */
    const val ERROR_AUTH = 0x02

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 2 <= data.size) {
            val tag = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            val end = minOf(pos + 2 + len, data.size)
            out[tag] = (out[tag] ?: ByteArray(0)) + data.copyOfRange(pos + 2, end)
            pos += 2 + len
        }
        return out
    }

    fun write(vararg entries: Pair<Int, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((tag, value) in entries) {
            var pos = 0
            // An empty value is still written once (tag + zero length).
            do {
                val n = minOf(255, value.size - pos)
                out.write(tag); out.write(n); out.write(value, pos, n)
                pos += n
            } while (pos < value.size)
        }
        return out.toByteArray()
    }
}
