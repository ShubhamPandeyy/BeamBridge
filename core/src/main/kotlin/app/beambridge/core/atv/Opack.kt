package app.beambridge.core.atv

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OPACK, Apple's compact binary format used by the Companion protocol (think "binary JSON").
 * Ported from atv-core (MIT, github.com/corvofeng/atv-core), which ports pyatv's `opack`.
 * Dicts keep insertion order. Integers are unsigned up to 64 bits.
 */
sealed interface OValue {
    data object Null : OValue
    data class Bool(val v: Boolean) : OValue
    data class Uuid(val v: ByteArray) : OValue { override fun equals(other: Any?) = other is Uuid && v.contentEquals(other.v); override fun hashCode() = v.contentHashCode() }
    data class Time(val raw: Long) : OValue
    data class Int(val v: Long) : OValue
    data class Dbl(val v: Double) : OValue
    data class Str(val v: String) : OValue
    data class Bytes(val v: ByteArray) : OValue { override fun equals(other: Any?) = other is Bytes && v.contentEquals(other.v); override fun hashCode() = v.contentHashCode() }
    data class Arr(val v: List<OValue>) : OValue
    data class Dict(val v: List<Pair<OValue, OValue>>) : OValue

    operator fun get(key: String): OValue? = (this as? Dict)?.v?.firstOrNull { (k, _) -> k is Str && k.v == key }?.second
    val long: Long? get() = (this as? Int)?.v
    val double: Double? get() = when (this) { is Dbl -> v; is Int -> v.toDouble(); else -> null }
    val string: String? get() = (this as? Str)?.v
    val bytes: ByteArray? get() = (this as? Bytes)?.v

    companion object {
        fun dict(vararg pairs: Pair<String, OValue>) = Dict(pairs.map { Str(it.first) to it.second })
        fun of(s: String) = Str(s)
        fun of(i: Long) = Int(i)
        fun of(b: ByteArray) = Bytes(b)
    }
}

class OpackException(message: String) : Exception(message)

object Opack {
    fun pack(value: OValue): ByteArray = packInner(value, ArrayList())

    private fun le(size: kotlin.Int, v: Long): ByteArray = ByteArray(size) { i -> (v ushr (8 * i)).toByte() }

    private fun packInner(value: OValue, objects: MutableList<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        when (value) {
            OValue.Null -> out.write(0x04)
            is OValue.Bool -> out.write(if (value.v) 1 else 2)
            is OValue.Uuid -> { out.write(0x05); out.write(value.v) }
            is OValue.Time -> { out.write(0x06); out.write(le(8, value.raw)) }
            is OValue.Int -> {
                val i = value.v
                when {
                    i in 0 until 0x28 -> out.write((i + 8).toInt())
                    java.lang.Long.compareUnsigned(i, 0xFF) <= 0 -> { out.write(0x30); out.write(le(1, i)) }
                    java.lang.Long.compareUnsigned(i, 0xFFFF) <= 0 -> { out.write(0x31); out.write(le(2, i)) }
                    java.lang.Long.compareUnsigned(i, 0xFFFF_FFFFL) <= 0 -> { out.write(0x32); out.write(le(4, i)) }
                    else -> { out.write(0x33); out.write(le(8, i)) }
                }
            }
            is OValue.Dbl -> { out.write(0x36); out.write(le(8, java.lang.Double.doubleToRawLongBits(value.v))) }
            is OValue.Str -> {
                val b = value.v.toByteArray(Charsets.UTF_8)
                writeSized(out, b.size, 0x40, 0x20, 0x61, 0x62, 0x64); out.write(b)
            }
            is OValue.Bytes -> { writeSized(out, value.v.size, 0x70, 0x20, 0x91, 0x92, 0x93); out.write(value.v) }
            is OValue.Arr -> {
                out.write(0xD0 + minOf(value.v.size, 0xF))
                value.v.forEach { out.write(packInner(it, objects)) }
                if (value.v.size >= 0xF) out.write(0x03)
            }
            is OValue.Dict -> {
                out.write(0xE0 + minOf(value.v.size, 0xF))
                value.v.forEach { (k, v) -> out.write(packInner(k, objects)); out.write(packInner(v, objects)) }
                if (value.v.size >= 0xF) out.write(0x03)
            }
        }
        val packed = out.toByteArray()
        if (packed.size > 1) {
            val index = objects.indexOfFirst { it.contentEquals(packed) }
            if (index >= 0) {
                return when {
                    index < 0x21 -> byteArrayOf((0xA0 + index).toByte())
                    index <= 0xFF -> byteArrayOf(0xC1.toByte(), index.toByte())
                    else -> byteArrayOf(0xC2.toByte()) + le(2, index.toLong())
                }
            }
            objects.add(packed)
        }
        return packed
    }

    private fun writeSized(out: java.io.ByteArrayOutputStream, len: kotlin.Int, small: kotlin.Int, smallMax: kotlin.Int, m1: kotlin.Int, m2: kotlin.Int, m4: kotlin.Int) {
        when {
            len <= smallMax -> out.write(small + len)
            len <= 0xFF -> { out.write(m1); out.write(len) }
            len <= 0xFFFF -> { out.write(m2); out.write(le(2, len.toLong())) }
            else -> { out.write(m4); out.write(le(4, len.toLong())) }
        }
    }

    /** Unpacks one value; returns it and the number of bytes consumed. */
    fun unpack(data: ByteArray): Pair<OValue, kotlin.Int> {
        val r = Reader(data)
        val v = r.read()
        return v to r.pos
    }

    private class Reader(val d: ByteArray) {
        var pos = 0
        val objects = ArrayList<OValue>()

        private fun need(n: kotlin.Int, what: String) { if (pos + n > d.size) throw OpackException("truncated $what") }
        private fun leAt(start: kotlin.Int, size: kotlin.Int): Long { var v = 0L; for (i in 0 until size) v = v or ((d[start + i].toLong() and 0xFF) shl (8 * i)); return v }
        private fun take(n: kotlin.Int, what: String): ByteArray { need(n, what); return d.copyOfRange(pos, pos + n).also { pos += n } }

        fun read(): OValue {
            need(1, "input")
            val m = d[pos].toInt() and 0xFF
            pos++
            var addRef = true
            val v: OValue = when {
                m == 0x01 -> { addRef = false; OValue.Bool(true) }
                m == 0x02 -> { addRef = false; OValue.Bool(false) }
                m == 0x04 -> { addRef = false; OValue.Null }
                m == 0x05 -> OValue.Uuid(take(16, "uuid"))
                m == 0x06 -> { need(8, "time"); OValue.Time(leAt(pos, 8)).also { pos += 8 } }
                m in 0x08..0x2F -> { addRef = false; OValue.Int((m - 8).toLong()) }
                m == 0x35 -> { need(4, "f32"); OValue.Dbl(ByteBuffer.wrap(d, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()).also { pos += 4 } }
                m == 0x36 -> { need(8, "f64"); OValue.Dbl(java.lang.Double.longBitsToDouble(leAt(pos, 8))).also { pos += 8 } }
                (m and 0xF0) == 0x30 -> {
                    val size = 1 shl (m and 0xF)
                    if (size > 8) throw OpackException("int wider than 8 bytes")
                    need(size, "int"); OValue.Int(leAt(pos, size)).also { pos += size }
                }
                m in 0x40..0x60 -> OValue.Str(String(take(m - 0x40, "string"), Charsets.UTF_8))
                m in 0x61..0x64 -> {
                    val sl = m and 0xF; need(sl, "string length"); val n = leAt(pos, sl).toInt(); pos += sl
                    OValue.Str(String(take(n, "string"), Charsets.UTF_8))
                }
                m in 0x70..0x90 -> OValue.Bytes(take(m - 0x70, "bytes"))
                m in 0x91..0x94 -> {
                    val sl = 1 shl ((m and 0xF) - 1); need(sl, "bytes length"); val n = leAt(pos, sl).toInt(); pos += sl
                    OValue.Bytes(take(n, "bytes"))
                }
                (m and 0xF0) == 0xD0 -> {
                    addRef = false
                    val count = m and 0xF
                    val items = ArrayList<OValue>()
                    if (count == 0xF) { while (true) { need(1, "list"); if (d[pos].toInt() == 0x03) { pos++; break }; items.add(read()) } }
                    else repeat(count) { items.add(read()) }
                    OValue.Arr(items)
                }
                (m and 0xE0) == 0xE0 -> {
                    addRef = false
                    val count = m and 0xF
                    val items = ArrayList<Pair<OValue, OValue>>()
                    if (count == 0xF) { while (true) { need(1, "dict"); if (d[pos].toInt() == 0x03) { pos++; break }; val k = read(); items.add(k to read()) } }
                    else repeat(count) { val k = read(); items.add(k to read()) }
                    OValue.Dict(items)
                }
                m in 0xA0..0xC0 -> { addRef = false; objects.getOrNull(m - 0xA0) ?: throw OpackException("bad object reference") }
                m in 0xC1..0xC4 -> {
                    addRef = false
                    val sl = m - 0xC0; need(sl, "reference"); val idx = leAt(pos, sl).toInt(); pos += sl
                    objects.getOrNull(idx) ?: throw OpackException("bad object reference")
                }
                else -> throw OpackException("unknown OPACK marker 0x${m.toString(16)}")
            }
            if (addRef && v !in objects) objects.add(v)
            return v
        }
    }
}
