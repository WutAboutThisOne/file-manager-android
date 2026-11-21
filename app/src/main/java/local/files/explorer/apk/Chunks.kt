package local.files.explorer.apk

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BadResourceException(message: String) : IOException(message)

/** Little-endian cursor over Android's resource chunk format (frameworks/base/libs/androidfw/ResourceTypes). */
internal class Buf(val bytes: ByteArray) {
    val bb: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun u8(at: Int): Int = check(at, 1).let { bytes[at].toInt() and 0xFF }
    fun u16(at: Int): Int = check(at, 2).let { bb.getShort(at).toInt() and 0xFFFF }
    fun u32(at: Int): Long = check(at, 4).let { bb.getInt(at).toLong() and 0xFFFFFFFFL }
    fun i32(at: Int): Int = check(at, 4).let { bb.getInt(at) }
    private fun check(at: Int, n: Int) { if (at < 0 || at + n > bytes.size) throw BadResourceException("Truncated resource data at $at") }
}

internal object ChunkType {
    const val NULL = 0x0000
    const val STRING_POOL = 0x0001
    const val TABLE = 0x0002
    const val XML = 0x0003
    const val XML_START_NAMESPACE = 0x0100
    const val XML_END_NAMESPACE = 0x0101
    const val XML_START_ELEMENT = 0x0102
    const val XML_END_ELEMENT = 0x0103
    const val XML_CDATA = 0x0104
    const val XML_RESOURCE_MAP = 0x0180
    const val TABLE_PACKAGE = 0x0200
    const val TABLE_TYPE = 0x0201
    const val TABLE_TYPE_SPEC = 0x0202
    const val TABLE_LIBRARY = 0x0203
}

/** ResStringPool: UTF-8 or UTF-16 strings, decoded lazily. */
internal class StringPool(private val b: Buf, private val start: Int) {
    private val headerSize = b.u16(start + 2)
    val count = b.u32(start + 8).toInt()
    private val flags = b.u32(start + 16)
    private val stringsStart = b.u32(start + 20).toInt()
    private val utf8 = flags and 0x100L != 0L
    private val cache = arrayOfNulls<String>(count.coerceIn(0, 1_000_000))

    init { if (count < 0 || count > 1_000_000) throw BadResourceException("Implausible string pool size $count") }

    operator fun get(i: Int): String? {
        if (i < 0 || i >= count) return null
        cache[i]?.let { return it }
        val off = start + stringsStart + b.i32(start + headerSize + i * 4)
        val s = if (utf8) {
            var p = off
            // utf16 length, then utf8 byte length; each 1 or 2 bytes (high bit = 2 bytes).
            p += if (b.u8(p) and 0x80 != 0) 2 else 1
            var len = b.u8(p)
            if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or b.u8(p + 1); p += 2 } else p += 1
            if (p + len > b.bytes.size) throw BadResourceException("String past end of data")
            String(b.bytes, p, len, Charsets.UTF_8)
        } else {
            var p = off
            var len = b.u16(p)
            if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or b.u16(p + 2); p += 4 } else p += 2
            if (p + len * 2 > b.bytes.size) throw BadResourceException("String past end of data")
            String(b.bytes, p, len * 2, Charsets.UTF_16LE)
        }
        cache[i] = s
        return s
    }
}

/** Res_value: (dataType, data). */
data class ResValue(val type: Int, val data: Int) {
    companion object {
        const val NULL = 0x00; const val REFERENCE = 0x01; const val ATTRIBUTE = 0x02; const val STRING = 0x03
        const val FLOAT = 0x04; const val DIMENSION = 0x05; const val FRACTION = 0x06; const val DYNAMIC_REFERENCE = 0x07
        const val DYNAMIC_ATTRIBUTE = 0x08
        const val INT_DEC = 0x10; const val INT_HEX = 0x11; const val BOOLEAN = 0x12
        const val COLOR_ARGB8 = 0x1c; const val COLOR_RGB8 = 0x1d; const val COLOR_ARGB4 = 0x1e; const val COLOR_RGB4 = 0x1f

        private val dimUnits = arrayOf("px", "dp", "sp", "pt", "in", "mm")
        private val fracUnits = arrayOf("%", "%p")
        private val radixMults = floatArrayOf(1f / (1 shl 8), 1f / (1 shl 15), 1f / (1 shl 23), 1f / (1L shl 31).toFloat())

        fun complex(data: Int): Float {
            val mantissa = data and (0xFFFFFF shl 8)
            val radix = (data shr 4) and 0x3
            return mantissa * radixMults[radix]
        }

        private fun num(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

        /** Plain-text form of a value that doesn't need the string pool or the resource table. */
        fun format(type: Int, data: Int): String = when (type) {
            NULL -> if (data == 1) "@empty" else "@null"
            REFERENCE, DYNAMIC_REFERENCE -> if (data == 0) "@null" else "@0x%08x".format(data)
            ATTRIBUTE, DYNAMIC_ATTRIBUTE -> "?0x%08x".format(data)
            FLOAT -> num(java.lang.Float.intBitsToFloat(data))
            DIMENSION -> num(complex(data)) + dimUnits.getOrElse(data and 0xF) { "?" }
            FRACTION -> num(complex(data) * 100) + fracUnits.getOrElse(data and 0xF) { "?" }
            INT_HEX -> "0x%x".format(data)
            BOOLEAN -> if (data != 0) "true" else "false"
            COLOR_ARGB8, COLOR_ARGB4 -> "#%08x".format(data)
            COLOR_RGB8, COLOR_RGB4 -> "#%06x".format(data and 0xFFFFFF)
            else -> data.toString()
        }
    }
}
