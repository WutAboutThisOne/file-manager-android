package local.files.explorer.apk

/** One element of a decoded Android binary XML document. */
class XmlElement(val namespace: String?, val name: String) {
    val attributes = mutableListOf<XmlAttr>()
    val children = mutableListOf<XmlElement>()
    var text: String? = null

    fun attr(name: String, ns: String? = BinaryXml.ANDROID_NS): XmlAttr? =
        attributes.firstOrNull { it.name == name && (ns == null || it.namespace == ns) }

    /** Children with [name], at any depth below this element when [deep]. */
    fun all(name: String, deep: Boolean = false): List<XmlElement> =
        if (!deep) children.filter { it.name == name } else buildList { fun w(e: XmlElement) { e.children.forEach { if (it.name == name) add(it); w(it) } }; w(this@XmlElement) }
}

/** [raw] is the string form if present; [value] the typed value. [resId] = the attribute's resource id (android:...). */
data class XmlAttr(val namespace: String?, val name: String, val raw: String?, val value: ResValue, val resId: Int) {
    val isReference get() = value.type == ResValue.REFERENCE || value.type == ResValue.DYNAMIC_REFERENCE
    val intValue: Int? get() = when (value.type) {
        ResValue.INT_DEC, ResValue.INT_HEX, ResValue.BOOLEAN -> value.data
        else -> raw?.toIntOrNull()
    }
}

/**
 * Decoder for compiled XML (AndroidManifest.xml and compiled res XML files inside an APK).
 * Self-contained: needs no Android APIs, so it runs in plain JVM tests.
 */
object BinaryXml {
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** True for data that starts like a compiled XML chunk (RES_XML_TYPE, header size 8). */
    fun isBinaryXml(head: ByteArray): Boolean =
        head.size >= 8 && head[0].toInt() == 0x03 && head[1].toInt() == 0x00 && head[2].toInt() == 0x08 && head[3].toInt() == 0x00

    /** Names for framework attributes that shrinkers strip from the string pool (id -> name). */
    internal val frameworkAttrs: Map<Int, String> = mapOf(
        0x01010000 to "theme", 0x01010001 to "label", 0x01010002 to "icon", 0x01010003 to "name",
        0x01010006 to "permission", 0x0101000e to "enabled", 0x0101000f to "debuggable", 0x01010010 to "exported",
        0x01010011 to "process", 0x01010018 to "authorities", 0x0101001d to "launchMode", 0x0101001e to "screenOrientation",
        0x01010020 to "description", 0x01010024 to "value", 0x01010025 to "resource", 0x01010026 to "mimeType",
        0x01010027 to "scheme", 0x01010028 to "host", 0x01010029 to "port", 0x0101002a to "path",
        0x0101002b to "pathPrefix", 0x0101002c to "pathPattern", 0x01010199 to "drawable",
        0x0101020c to "minSdkVersion", 0x0101021b to "versionCode", 0x0101021c to "versionName",
        0x01010270 to "targetSdkVersion", 0x01010271 to "maxSdkVersion", 0x01010280 to "allowBackup",
        0x0101028e to "required", 0x0101052c to "roundIcon", 0x01010572 to "compileSdkVersion",
        0x01010573 to "compileSdkVersionCodename", 0x01010599 to "foregroundServiceType", 0x01010003 to "name",
        0x0101000c to "grantUriPermissions", 0x01010202 to "priority", 0x0101001f to "configChanges",
        0x010103a7 to "isolatedProcess", 0x01010473 to "extractNativeLibs", 0x010104ec to "supportsRtl",
        0x01010527 to "networkSecurityConfig", 0x0101057a to "appComponentFactory", 0x01010601 to "requestLegacyExternalStorage",
    )

    /** Parses [bytes]; [resolve] turns references into "@type/name" (or null to keep the hex id). */
    fun parse(bytes: ByteArray): XmlElement {
        val b = Buf(bytes)
        if (bytes.size < 8 || b.u16(0) != ChunkType.XML) throw BadResourceException("Not a compiled XML file")
        val total = b.u32(4).toInt().coerceAtMost(bytes.size)
        var pos = b.u16(2)
        var pool: StringPool? = null
        var resMap = IntArray(0)
        val root = XmlElement(null, "#document")
        val stack = ArrayDeque<XmlElement>().apply { addLast(root) }
        var guard = 0
        while (pos + 8 <= total) {
            if (++guard > 5_000_000) throw BadResourceException("Too many XML chunks")
            val type = b.u16(pos)
            val headerSize = b.u16(pos + 2)
            val size = b.u32(pos + 4).toInt()
            if (size < 8 || pos + size > total) throw BadResourceException("Bad XML chunk at $pos")
            when (type) {
                ChunkType.STRING_POOL -> pool = StringPool(b, pos)
                ChunkType.XML_RESOURCE_MAP -> resMap = IntArray((size - headerSize) / 4) { b.i32(pos + headerSize + it * 4) }
                ChunkType.XML_START_ELEMENT -> {
                    val p = pool ?: throw BadResourceException("XML element before string pool")
                    val ext = pos + headerSize
                    val ns = p[b.i32(ext)]
                    val name = p[b.i32(ext + 4)] ?: "?"
                    val attrStart = b.u16(ext + 8)
                    val attrSize = b.u16(ext + 10).coerceAtLeast(20)
                    val attrCount = b.u16(ext + 12)
                    val el = XmlElement(ns, name)
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        val aNs = p[b.i32(a)]
                        val nameIdx = b.i32(a + 4)
                        val resId = if (nameIdx in resMap.indices) resMap[nameIdx] else 0
                        val aName = p[nameIdx]?.takeIf { it.isNotEmpty() } ?: frameworkAttrs[resId] ?: if (resId != 0) "attr_0x%08x".format(resId) else "?"
                        val raw = p[b.i32(a + 8)]
                        val vType = b.u8(a + 15)
                        val vData = b.i32(a + 16)
                        el.attributes += XmlAttr(aNs, aName, raw, ResValue(vType, vData), resId)
                    }
                    stack.last().children += el
                    stack.addLast(el)
                }
                ChunkType.XML_END_ELEMENT -> if (stack.size > 1) stack.removeLast()
                ChunkType.XML_CDATA -> {
                    val p = pool
                    val t = p?.get(b.i32(pos + headerSize))
                    if (t != null) stack.last().text = (stack.last().text ?: "") + t
                }
                else -> Unit // namespaces are implied by attribute URIs; unknown chunks are skipped
            }
            pos += size
        }
        return root.children.firstOrNull() ?: throw BadResourceException("Empty XML document")
    }

    /** Pretty-prints [root] as XML text, resolving references with [table] when given. */
    fun toXml(root: XmlElement, table: ResourceTable? = null): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        val prefixes = LinkedHashMap<String, String>()
        fun collect(e: XmlElement) {
            e.namespace?.let { prefixes.getOrPut(it) { prefixFor(it, prefixes.size) } }
            e.attributes.forEach { a -> a.namespace?.let { prefixes.getOrPut(it) { prefixFor(it, prefixes.size) } } }
            e.children.forEach(::collect)
        }
        collect(root)
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun q(ns: String?, n: String) = if (ns.isNullOrEmpty()) n else "${prefixes[ns]}:$n"
        fun write(e: XmlElement, depth: Int) {
            val ind = "    ".repeat(depth)
            sb.append(ind).append('<').append(q(e.namespace, e.name))
            if (depth == 0) prefixes.forEach { (uri, p) -> sb.append("\n").append(ind).append("    xmlns:").append(p).append("=\"").append(esc(uri)).append('"') }
            for (a in e.attributes) {
                sb.append("\n").append(ind).append("    ").append(q(a.namespace, a.name)).append("=\"").append(esc(valueText(a, table))).append('"')
            }
            if (e.children.isEmpty() && e.text == null) { sb.append(" />\n"); return }
            sb.append(">")
            e.text?.let { sb.append(esc(it)) }
            if (e.children.isNotEmpty()) {
                sb.append("\n")
                e.children.forEach { write(it, depth + 1) }
                sb.append(ind)
            }
            sb.append("</").append(q(e.namespace, e.name)).append(">\n")
        }
        write(root, 0)
        return sb.toString()
    }

    private fun prefixFor(uri: String, n: Int) = when (uri) {
        ANDROID_NS -> "android"
        "http://schemas.android.com/apk/res-auto" -> "app"
        "http://schemas.android.com/tools" -> "tools"
        "http://schemas.android.com/aapt" -> "aapt"
        else -> "ns$n"
    }

    fun valueText(a: XmlAttr, table: ResourceTable?): String = when {
        a.value.type == ResValue.STRING || (a.raw != null && a.value.type == ResValue.NULL) -> a.raw ?: ""
        a.isReference && table != null -> table.nameOf(a.value.data)?.let { "@$it" } ?: ResValue.format(a.value.type, a.value.data)
        (a.value.type == ResValue.ATTRIBUTE) && table != null -> table.nameOf(a.value.data)?.let { "?$it" } ?: ResValue.format(a.value.type, a.value.data)
        else -> a.raw ?: ResValue.format(a.value.type, a.value.data)
    }

    /** Convenience: decode compiled XML bytes straight to text. */
    fun decodeToText(bytes: ByteArray, table: ResourceTable? = null): String = toXml(parse(bytes), table)
}
