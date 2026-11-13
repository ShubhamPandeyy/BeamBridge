package app.beambridge.core.mdns

import java.io.ByteArrayOutputStream

/** Just enough DNS (RFC 1035) for multicast DNS host names: A-record questions and answers. */
object Dns {
    const val TYPE_A = 1
    const val TYPE_ANY = 255
    const val CLASS_IN = 1

    data class Question(val name: String, val type: Int, val unicast: Boolean)
    data class Record(val name: String, val type: Int, val data: ByteArray)
    data class Message(val isResponse: Boolean, val questions: List<Question>, val answers: List<Record>)

    private fun ByteArrayOutputStream.u16(v: Int) { write(v shr 8 and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Long) { u16((v shr 16 and 0xFFFF).toInt()); u16((v and 0xFFFF).toInt()) }

    private fun ByteArrayOutputStream.name(n: String) {
        n.trim('.').split('.').filter { it.isNotEmpty() }.forEach { l -> val b = l.toByteArray(); write(b.size); write(b) }
        write(0)
    }

    /** A question for [name]. [unicast] sets the "QU" bit, asking for a direct reply. */
    fun query(name: String, type: Int = TYPE_ANY, unicast: Boolean = false): ByteArray = ByteArrayOutputStream().apply {
        u16(0); u16(0); u16(1); u16(0); u16(0); u16(0)
        name(name); u16(type); u16(CLASS_IN or if (unicast) 0x8000 else 0)
    }.toByteArray()

    /** An authoritative answer "[name] is at [ip]" (cache-flush set, as mDNS expects). */
    fun response(name: String, ip: ByteArray, ttlSeconds: Long = 120): ByteArray = ByteArrayOutputStream().apply {
        require(ip.size == 4)
        u16(0); u16(0x8400); u16(0); u16(1); u16(0); u16(0)
        name(name); u16(TYPE_A); u16(CLASS_IN or 0x8000); u32(ttlSeconds); u16(4); write(ip)
    }.toByteArray()

    fun parse(d: ByteArray, len: Int = d.size): Message? = runCatching {
        fun u16(p: Int) = (d[p].toInt() and 0xFF shl 8) or (d[p + 1].toInt() and 0xFF)
        require(len >= 12)
        val flags = u16(2)
        val qd = u16(4); val an = u16(6)
        var pos = 12

        fun readName(start: Int): Pair<String, Int> {
            val sb = StringBuilder(); var p = start; var end = -1; var hops = 0
            while (true) {
                val l = d[p].toInt() and 0xFF
                when {
                    l == 0 -> { if (end < 0) end = p + 1; break }
                    l and 0xC0 == 0xC0 -> { if (end < 0) end = p + 2; p = (l and 0x3F shl 8) or (d[p + 1].toInt() and 0xFF); require(++hops < 20) }
                    else -> { require(p + 1 + l <= len); sb.append(String(d, p + 1, l)).append('.'); p += 1 + l }
                }
            }
            return sb.toString().trimEnd('.') to end
        }

        val qs = ArrayList<Question>()
        repeat(qd) {
            val (n, e) = readName(pos); pos = e
            val t = u16(pos); val c = u16(pos + 2); pos += 4
            qs += Question(n, t, c and 0x8000 != 0)
        }
        val ans = ArrayList<Record>()
        repeat(an) {
            val (n, e) = readName(pos); pos = e
            val t = u16(pos); val rdl = u16(pos + 8); pos += 10
            require(pos + rdl <= len)
            ans += Record(n, t, d.copyOfRange(pos, pos + rdl)); pos += rdl
        }
        Message(flags and 0x8000 != 0, qs, ans)
    }.getOrNull()
}

/** Host-name rules for `<name>.local`. */
object HostNames {
    const val DEFAULT = "remote"
    private val valid = Regex("[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?")

    fun isValid(name: String) = valid.matches(name)

    /** Lower-cases and swaps anything not allowed in a host name for '-'. Blank becomes [DEFAULT]. */
    fun sanitize(raw: String): String =
        raw.trim().lowercase().removeSuffix(".local").replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(32).trim('-').ifEmpty { DEFAULT }
}
