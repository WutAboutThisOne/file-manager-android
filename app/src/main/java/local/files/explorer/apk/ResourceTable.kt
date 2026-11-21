package local.files.explorer.apk

/**
 * Minimal reader for resources.arsc: enough to name resource ids, resolve string values
 * (app labels) and find the file behind a drawable/mipmap (app icons) for a screen density.
 */
class ResourceTable private constructor() {
    /** One value of a resource in one configuration. */
    data class Entry(val config: Config, val value: ResValue?, val isComplex: Boolean, val parent: Int = 0, val bag: List<Pair<Int, ResValue>> = emptyList())

    /** The parts of ResTable_config the app cares about. density 0 = default, 0xFFFE = anydpi, 0xFFFF = nodpi. */
    data class Config(val language: String, val country: String, val density: Int, val sdk: Int) {
        val isDefaultLocale get() = language.isEmpty()
        override fun toString() = buildList {
            if (language.isNotEmpty()) add(if (country.isNotEmpty()) "$language-r$country" else language)
            if (density != 0) add(densityName(density))
            if (sdk != 0) add("v$sdk")
        }.joinToString("-").ifEmpty { "default" }
    }

    private class Type(val name: String) { val entries = HashMap<Int, MutableList<Entry>>(); val keys = HashMap<Int, String>() }
    private class Pkg(val id: Int, val name: String) { val types = HashMap<Int, Type>() }

    private val packages = HashMap<Int, Pkg>()
    private var globalStrings: StringPool? = null

    val packageNames: List<String> get() = packages.values.map { it.name }
    val resourceCount: Int get() = packages.values.sumOf { p -> p.types.values.sumOf { it.keys.size } }

    /** "type/name" (with "package:" for framework ids), or null when the id isn't in this table. */
    fun nameOf(id: Int): String? {
        val pkgId = (id ushr 24) and 0xFF
        val p = packages[pkgId] ?: return if (pkgId == 0x01) frameworkName(id) else null
        val t = p.types[(id ushr 16) and 0xFF] ?: return null
        val key = t.keys[id and 0xFFFF] ?: return null
        return "${t.name}/$key"
    }

    private fun frameworkName(id: Int): String? = BinaryXml.frameworkAttrs[id]?.let { "android:attr/$it" }

    fun entries(id: Int): List<Entry> =
        packages[(id ushr 24) and 0xFF]?.types?.get((id ushr 16) and 0xFF)?.entries?.get(id and 0xFFFF).orEmpty()

    /** The string for a (global pool) string value. */
    fun string(v: ResValue): String? = if (v.type == ResValue.STRING) globalStrings?.get(v.data) else null

    /** Resolves [id] to a displayable string, following references; prefers the default locale (or [language]). */
    fun resolveString(id: Int, language: String? = null, depth: Int = 0): String? {
        if (depth > 16) return null
        val all = entries(id).filter { !it.isComplex && it.value != null }
        val e = all.firstOrNull { language != null && it.config.language == language }
            ?: all.firstOrNull { it.config.isDefaultLocale } ?: all.firstOrNull() ?: return null
        val v = e.value!!
        return when (v.type) {
            ResValue.STRING -> string(v)
            ResValue.REFERENCE, ResValue.DYNAMIC_REFERENCE -> resolveString(v.data, language, depth + 1)
            else -> ResValue.format(v.type, v.data)
        }
    }

    /**
     * Files that back resource [id] (following references), best density first.
     * Values are paths inside the APK such as "res/mipmap-xxxhdpi-v4/ic_launcher.png".
     */
    fun resolveFiles(id: Int, depth: Int = 0): List<Pair<Config, String>> {
        if (depth > 16) return emptyList()
        val out = mutableListOf<Pair<Config, String>>()
        for (e in entries(id)) {
            val v = e.value ?: continue
            when (v.type) {
                ResValue.STRING -> string(v)?.let { out += e.config to it }
                ResValue.REFERENCE, ResValue.DYNAMIC_REFERENCE -> out += resolveFiles(v.data, depth + 1)
            }
        }
        return out.sortedByDescending { densityRank(it.first.density) }
    }

    /** A color value for [id] (ARGB), if it is (or points to) a plain color. */
    fun resolveColor(id: Int, depth: Int = 0): Int? {
        if (depth > 16) return null
        val v = entries(id).firstOrNull { !it.isComplex && it.config.isDefaultLocale }?.value ?: return null
        return when (v.type) {
            in ResValue.COLOR_ARGB8..ResValue.COLOR_RGB4 -> if (v.type == ResValue.COLOR_RGB8 || v.type == ResValue.COLOR_RGB4) v.data or 0xFF000000.toInt() else v.data
            ResValue.REFERENCE -> resolveColor(v.data, depth + 1)
            else -> null
        }
    }

    /** A short text listing of every resource (for viewing resources.arsc). Capped at [maxLines]. */
    fun dump(maxLines: Int = 20_000): String {
        val sb = StringBuilder()
        var lines = 0
        sb.append("# resources.arsc — ${packages.size} package(s), $resourceCount resources\n")
        for (p in packages.values.sortedBy { it.id }) {
            sb.append("\npackage ${p.name} (0x%02x)\n".format(p.id))
            for ((tid, t) in p.types.toSortedMap()) {
                for ((eid, key) in t.keys.toSortedMap()) {
                    if (++lines > maxLines) { sb.append("… (truncated)\n"); return sb.toString() }
                    val id = (p.id shl 24) or (tid shl 16) or eid
                    sb.append("  0x%08x @%s/%s".format(id, t.name, key))
                    val es = t.entries[eid].orEmpty()
                    val first = es.firstOrNull { it.config.isDefaultLocale && it.config.density == 0 } ?: es.firstOrNull()
                    if (first != null) {
                        sb.append(" = ").append(describe(first))
                        if (es.size > 1) sb.append("  (+${es.size - 1} config${if (es.size > 2) "s" else ""})")
                    }
                    sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun describe(e: Entry): String {
        if (e.isComplex) return "{${e.bag.size} item${if (e.bag.size == 1) "" else "s"}}"
        val v = e.value ?: return "?"
        return when (v.type) {
            ResValue.STRING -> "\"" + (string(v) ?: "").take(120).replace("\n", "\\n") + "\""
            ResValue.REFERENCE -> nameOf(v.data)?.let { "@$it" } ?: ResValue.format(v.type, v.data)
            else -> ResValue.format(v.type, v.data)
        }
    }

    companion object {
        fun densityName(d: Int) = when (d) {
            120 -> "ldpi"; 160 -> "mdpi"; 213 -> "tvdpi"; 240 -> "hdpi"; 320 -> "xhdpi"; 480 -> "xxhdpi"; 640 -> "xxxhdpi"
            0xFFFE -> "anydpi"; 0xFFFF -> "nodpi"; else -> "${d}dpi"
        }

        /** Higher is preferred for icons: real densities by size, anydpi (adaptive) after, nodpi/default last. */
        fun densityRank(d: Int) = when (d) { 0xFFFE -> 50; 0xFFFF -> 2; 0 -> 1; else -> 100 + d }

        fun parse(bytes: ByteArray): ResourceTable {
            val b = Buf(bytes)
            if (bytes.size < 12 || b.u16(0) != ChunkType.TABLE) throw BadResourceException("Not a resources.arsc table")
            val t = ResourceTable()
            val total = b.u32(4).toInt().coerceAtMost(bytes.size)
            var pos = b.u16(2)
            while (pos + 8 <= total) {
                val type = b.u16(pos)
                val size = b.u32(pos + 4).toInt()
                if (size < 8 || pos + size > total) throw BadResourceException("Bad table chunk at $pos")
                when (type) {
                    ChunkType.STRING_POOL -> if (t.globalStrings == null) t.globalStrings = StringPool(b, pos)
                    ChunkType.TABLE_PACKAGE -> t.readPackage(b, pos, size)
                }
                pos += size
            }
            return t
        }
    }

    private fun readPackage(b: Buf, start: Int, size: Int) {
        val headerSize = b.u16(start + 2)
        val id = b.u32(start + 8).toInt()
        val nameChars = CharArray(128) { b.u16(start + 12 + it * 2).toChar() }
        val name = String(nameChars).substringBefore('\u0000')
        val typeStringsOff = b.u32(start + 268).toInt()
        val keyStringsOff = b.u32(start + 276).toInt()
        val typeStrings = StringPool(b, start + typeStringsOff)
        val keyStrings = StringPool(b, start + keyStringsOff)
        val pkg = packages.getOrPut(id) { Pkg(id, name) }
        var pos = start + headerSize
        val end = start + size
        while (pos + 8 <= end) {
            val ctype = b.u16(pos)
            val csize = b.u32(pos + 4).toInt()
            if (csize < 8 || pos + csize > end) throw BadResourceException("Bad package chunk at $pos")
            if (ctype == ChunkType.TABLE_TYPE) readType(b, pos, pkg, typeStrings, keyStrings)
            pos += csize
        }
    }

    private fun readType(b: Buf, start: Int, pkg: Pkg, typeStrings: StringPool, keyStrings: StringPool) {
        val headerSize = b.u16(start + 2)
        val size = b.u32(start + 4).toInt()
        val typeId = b.u8(start + 8)
        val flags = b.u8(start + 9)
        val entryCount = b.u32(start + 12).toInt()
        val entriesStart = b.u32(start + 16).toInt()
        val cfg = start + 20
        val cfgSize = b.u32(cfg).toInt()
        fun ascii(at: Int): String {
            val c0 = b.u8(at); val c1 = b.u8(at + 1)
            if (c0 == 0) return ""
            if (c0 and 0x80 != 0) return "" // packed 3-letter codes: rare, ignored
            return "${c0.toChar()}${c1.toChar()}"
        }
        val config = Config(
            language = if (cfgSize >= 12) ascii(cfg + 8) else "",
            country = if (cfgSize >= 12) ascii(cfg + 10) else "",
            density = if (cfgSize >= 16) b.u16(cfg + 14) else 0,
            sdk = if (cfgSize >= 28) b.u16(cfg + 24) else 0,
        )
        val type = pkg.types.getOrPut(typeId) { Type(typeStrings[typeId - 1] ?: "type$typeId") }
        val sparse = flags and 0x01 != 0
        val offset16 = flags and 0x02 != 0
        val idx = start + headerSize
        val n = entryCount.coerceAtMost(65536)
        for (i in 0 until n) {
            val entryIndex: Int
            val off: Int
            when {
                sparse -> { entryIndex = b.u16(idx + i * 4); off = b.u16(idx + i * 4 + 2) * 4 }
                offset16 -> { entryIndex = i; val o = b.u16(idx + i * 2); if (o == 0xFFFF) continue; off = o * 4 }
                else -> { entryIndex = i; val o = b.u32(idx + i * 4); if (o == 0xFFFFFFFFL) continue; off = o.toInt() }
            }
            val e = start + entriesStart + off
            if (e + 8 > start + size) continue
            val eSize = b.u16(e)
            val eFlags = b.u16(e + 2)
            if (eFlags and 0x08 != 0) { // FLAG_COMPACT: key in size slot, type in high byte of flags, data inline
                keyStrings[eSize]?.let { type.keys[entryIndex] = it }
                type.entries.getOrPut(entryIndex) { mutableListOf() } += Entry(config, ResValue(eFlags ushr 8, b.i32(e + 4)), false)
                continue
            }
            keyStrings[b.i32(e + 4)]?.let { type.keys[entryIndex] = it }
            if (eFlags and 0x01 != 0) {
                val parent = b.i32(e + 8)
                val count = b.u32(e + 12).toInt().coerceAtMost(10_000)
                val bag = ArrayList<Pair<Int, ResValue>>(count)
                var m = e + eSize
                repeat(count) {
                    if (m + 12 > start + size) return@repeat
                    bag += b.i32(m) to ResValue(b.u8(m + 7), b.i32(m + 8))
                    m += 12
                }
                type.entries.getOrPut(entryIndex) { mutableListOf() } += Entry(config, null, true, parent, bag)
            } else {
                val v = e + eSize
                if (v + 8 > start + size) continue
                type.entries.getOrPut(entryIndex) { mutableListOf() } += Entry(config, ResValue(b.u8(v + 3), b.i32(v + 4)), false)
            }
        }
    }
}
