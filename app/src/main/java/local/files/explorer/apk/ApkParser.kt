package local.files.explorer.apk

import local.files.explorer.core.SplitApk
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** One app component declared in the manifest. */
data class ApkComponent(val name: String, val exported: Boolean? = null, val permission: String? = null, val authorities: String? = null, val isLauncher: Boolean = false)

/** Everything the APK info screen shows, decoded offline from the APK's own bytes. */
data class ApkDetails(
    val fileName: String,
    val packageName: String,
    val label: String?,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compileSdk: Int?,
    val permissions: List<String>,
    val activities: List<ApkComponent>,
    val services: List<ApkComponent>,
    val receivers: List<ApkComponent>,
    val providers: List<ApkComponent>,
    val features: List<String> = emptyList(),
    /** Resource reference of the icon ("@mipmap/ic_launcher"), and the best bitmap file for it if any. */
    val iconRef: String? = null,
    val iconPath: String? = null,
    val iconBytes: ByteArray? = null,
    val debuggable: Boolean = false,
    val nativeAbis: List<String> = emptyList(),
    val dexCount: Int = 0,
    val entryCount: Int = 0,
    val sizeBytes: Long = 0,
    /** For split bundles: the base APK's entry name and the number of split APKs. */
    val bundleBase: String? = null,
    val splitCount: Int = 1,
) {
    val displayName get() = label?.takeIf { it.isNotBlank() } ?: packageName
    val componentCount get() = activities.size + services.size + receivers.size + providers.size
}

/**
 * Reads [ApkDetails] from an APK (or a split bundle's base APK) with the self-written
 * [BinaryXml] and [ResourceTable] decoders. No PackageManager, no network.
 */
object ApkParser {
    private const val MAX_META = 64L * 1024 * 1024 // manifest / arsc / icon size cap

    /** Read access to the entries of one APK. */
    interface Source {
        val names: List<String>
        fun read(name: String): ByteArray?
    }

    fun parse(file: File, displayName: String = file.name): ApkDetails = ZipFile(file).use { z ->
        val names = z.entries().asSequence().map { it.name }.toList()
        if ("AndroidManifest.xml" !in names) {
            val bundle = SplitApk.analyze(names)
            val base = bundle.baseEntry ?: throw IOException("No AndroidManifest.xml or base APK found")
            val inner = z.getInputStream(z.getEntry(base)).use { parseStream(it, displayName) { z.getInputStream(z.getEntry(base)) } }
            return inner.copy(bundleBase = base, splitCount = bundle.apkEntries.size, sizeBytes = file.length(), fileName = displayName)
        }
        val src = object : Source {
            override val names = names
            override fun read(name: String): ByteArray? = z.getEntry(name)?.let { e -> readCapped(z.getInputStream(e), e) }
        }
        parse(src, displayName).copy(sizeBytes = file.length())
    }

    private fun readCapped(input: InputStream, e: ZipEntry): ByteArray = input.use {
        if (e.size > MAX_META) throw IOException("${e.name} is too large")
        readAll(it)
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n; if (total > MAX_META) throw IOException("Entry is too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Parses an APK delivered as a stream (e.g. base.apk inside a bundle) without a temp file:
     * pass 1 grabs the manifest and resources.arsc, pass 2 (if [reopen] given) the icon.
     */
    fun parseStream(input: InputStream, displayName: String, reopen: (() -> InputStream)? = null): ApkDetails {
        val wanted = setOf("AndroidManifest.xml", "resources.arsc")
        val got = HashMap<String, ByteArray>()
        val names = mutableListOf<String>()
        ZipInputStream(input).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                names += e.name
                if (e.name in wanted) got[e.name] = readAll(zin)
            }
        }
        val src = object : Source {
            override val names = names
            override fun read(name: String): ByteArray? = got[name] ?: if (reopen == null) null else ZipInputStream(reopen()).use { zin ->
                while (true) { val e = zin.nextEntry ?: break; if (e.name == name) return@use readAll(zin) }
                null
            }
        }
        return parse(src, displayName)
    }

    fun parse(src: Source, displayName: String): ApkDetails {
        val manifestBytes = src.read("AndroidManifest.xml") ?: throw IOException("No AndroidManifest.xml in this APK")
        val table = runCatching { src.read("resources.arsc")?.let(ResourceTable::parse) }.getOrNull()
        val m = BinaryXml.parse(manifestBytes)
        if (m.name != "manifest") throw BadResourceException("AndroidManifest.xml has no <manifest> root")
        val app = m.all("application").firstOrNull()
        val sdk = m.all("uses-sdk").firstOrNull()

        fun str(a: XmlAttr?): String? = when {
            a == null -> null
            a.isReference && table != null -> table.resolveString(a.value.data) ?: BinaryXml.valueText(a, table)
            else -> BinaryXml.valueText(a, table)
        }
        fun int(a: XmlAttr?): Int? = a?.intValue ?: a?.let { str(it)?.toIntOrNull() }
        fun bool(a: XmlAttr?): Boolean? = a?.let { if (it.value.type == ResValue.BOOLEAN) it.value.data != 0 else str(it)?.toBooleanStrictOrNull() }
        val pkg = m.attr("package", null)?.raw ?: throw BadResourceException("Manifest has no package name")
        fun cls(n: String?): String = when {
            n == null -> "?"
            n.startsWith(".") -> pkg + n
            '.' !in n -> "$pkg.$n"
            else -> n
        }
        fun comps(tag: String): List<ApkComponent> = (app?.children.orEmpty().filter { it.name == tag || (tag == "activity" && it.name == "activity-alias") }).map { e ->
            val launcher = e.all("intent-filter").any { f ->
                f.all("action").any { it.attr("name")?.raw == "android.intent.action.MAIN" } &&
                    f.all("category").any { it.attr("name")?.raw == "android.intent.category.LAUNCHER" }
            }
            ApkComponent(
                name = cls(e.attr("name")?.raw),
                exported = bool(e.attr("exported")),
                permission = e.attr("permission")?.raw,
                authorities = e.attr("authorities")?.raw,
                isLauncher = launcher,
            )
        }

        val perms = (m.all("uses-permission") + m.all("uses-permission-sdk-23")).mapNotNull { it.attr("name")?.raw }.distinct()
        val iconAttr = app?.attr("icon") ?: app?.attr("roundIcon")
        var iconPath: String? = null
        var iconBytes: ByteArray? = null
        if (iconAttr != null && iconAttr.isReference && table != null) {
            iconPath = bestBitmap(iconAttr.value.data, table, src)
            iconBytes = iconPath?.let { runCatching { src.read(it) }.getOrNull() }
        }
        val versionCodeLow = int(m.attr("versionCode"))?.toLong()?.and(0xFFFFFFFFL) ?: 0L
        val versionCodeMajor = int(m.attr("versionCodeMajor"))?.toLong() ?: 0L
        return ApkDetails(
            fileName = displayName,
            packageName = pkg,
            label = str(app?.attr("label")),
            versionName = str(m.attr("versionName")),
            versionCode = (versionCodeMajor shl 32) or versionCodeLow,
            minSdk = int(sdk?.attr("minSdkVersion")) ?: 1,
            targetSdk = int(sdk?.attr("targetSdkVersion")) ?: int(sdk?.attr("minSdkVersion")),
            compileSdk = int(m.attr("compileSdkVersion")) ?: int(m.attr("platformBuildVersionCode", null)),
            permissions = perms,
            activities = comps("activity"),
            services = comps("service"),
            receivers = comps("receiver"),
            providers = comps("provider"),
            features = m.all("uses-feature").mapNotNull { it.attr("name")?.raw },
            iconRef = iconAttr?.let { BinaryXml.valueText(it, table) },
            iconPath = iconPath,
            iconBytes = iconBytes,
            debuggable = bool(app?.attr("debuggable")) == true,
            nativeAbis = src.names.filter { it.startsWith("lib/") && it.count { c -> c == '/' } >= 2 }.map { it.split('/')[1] }.distinct().sorted(),
            dexCount = src.names.count { it.matches(Regex("classes\\d*\\.dex")) },
            entryCount = src.names.size,
        )
    }

    /** The highest-density PNG/WebP/JPEG for an icon; looks through adaptive-icon XML to its foreground. */
    internal fun bestBitmap(id: Int, table: ResourceTable, src: Source, depth: Int = 0): String? {
        if (depth > 4) return null
        val files = table.resolveFiles(id)
        files.firstOrNull { isBitmap(it.second) && it.second in src.names }?.let { return it.second }
        for ((_, path) in files.filter { it.second.endsWith(".xml") }) {
            val xml = runCatching { src.read(path)?.let(BinaryXml::parse) }.getOrNull() ?: continue
            val fg = (xml.all("foreground") + xml.all("monochrome") + xml.all("background"))
                .mapNotNull { it.attr("drawable") }.filter { it.isReference }
            for (a in fg) bestBitmap(a.value.data, table, src, depth + 1)?.let { return it }
            // <bitmap android:src=...> / <inset android:drawable=...>
            (xml.attr("src") ?: xml.attr("drawable"))?.takeIf { it.isReference }?.let { a -> bestBitmap(a.value.data, table, src, depth + 1)?.let { return it } }
        }
        return null
    }

    private fun isBitmap(p: String) = p.endsWith(".png", true) || p.endsWith(".webp", true) || p.endsWith(".jpg", true) || p.endsWith(".jpeg", true)
}
