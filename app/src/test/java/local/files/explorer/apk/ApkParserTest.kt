package local.files.explorer.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkParserTest {
    private fun fixture(name: String): File {
        val url = javaClass.classLoader!!.getResource("apk/$name") ?: error("missing fixture $name")
        return File(url.toURI())
    }

    private fun entry(apk: File, name: String): ByteArray = ZipFile(apk).use { z -> z.getInputStream(z.getEntry(name)).readBytes() }

    @Test fun `decodes a real release manifest from the 0_3_0 fixture`() {
        val d = ApkParser.parse(fixture("folio-0.3.0.apk"))
        assertEquals("local.files.explorer", d.packageName)
        assertEquals("Folio Files", d.label) // the fixture predates the rename
        assertEquals("0.3.0", d.versionName)
        assertEquals(4L, d.versionCode)
        assertEquals(26, d.minSdk)
        assertEquals(34, d.targetSdk)
        assertEquals(34, d.compileSdk)
        assertTrue(d.permissions.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertTrue(d.permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertFalse("release must stay offline", d.permissions.contains("android.permission.INTERNET"))
        val acts = d.activities.map { it.name }
        assertTrue(acts.contains("local.files.explorer.MainActivity"))
        assertTrue(acts.contains("local.files.explorer.InstallActivity"))
        assertTrue(d.activities.first { it.name.endsWith(".MainActivity") }.isLauncher)
        assertTrue(d.services.any { it.name.endsWith("FileOperationService") })
        assertTrue(d.receivers.any { it.name.endsWith("InstallResultReceiver") })
        val provider = d.providers.first { it.name == "androidx.core.content.FileProvider" }
        assertEquals("local.files.explorer.files", provider.authorities)
        assertEquals(false, provider.exported)
        // Icon is an adaptive icon (XML); the reference still resolves through resources.arsc.
        assertNotNull(d.iconRef)
        assertTrue(d.iconRef!!.startsWith("@mipmap/") || d.iconRef!!.startsWith("@drawable/"))
        assertTrue(d.dexCount >= 1)
    }

    @Test fun `manifest decodes to readable XML text`() {
        val apk = fixture("folio-0.3.0.apk")
        val table = ResourceTable.parse(entry(apk, "resources.arsc"))
        val xml = BinaryXml.decodeToText(entry(apk, "AndroidManifest.xml"), table)
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<manifest"))
        assertTrue(xml.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\""))
        assertTrue(xml.contains("package=\"local.files.explorer\""))
        assertTrue(xml.contains("android:minSdkVersion=\"26\""))
        assertTrue(xml.contains("android:name=\"local.files.explorer.MainActivity\""))
        assertTrue(xml.contains("android:label=\"@string/"))
        // Well-formed: a standard XML parser accepts it.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
    }

    @Test fun `fixture app resolves label icon and components`() {
        val d = ApkParser.parse(fixture("fixture.apk"))
        assertEquals("com.example.fixture", d.packageName)
        assertEquals("Fixture Viewer", d.label) // default locale wins over values-fr
        assertEquals("1.2.3", d.versionName)
        assertEquals(42L, d.versionCode)
        assertEquals(24, d.minSdk)
        assertEquals(33, d.targetSdk)
        assertEquals(listOf("android.permission.CAMERA", "android.permission.VIBRATE"), d.permissions)
        assertEquals(listOf("android.hardware.camera"), d.features)
        assertEquals(listOf("com.example.fixture.MainActivity", "com.example.fixture.SettingsActivity"), d.activities.map { it.name })
        assertTrue(d.activities[0].isLauncher)
        assertEquals(true, d.activities[0].exported)
        assertEquals(listOf("com.example.fixture.SyncService"), d.services.map { it.name })
        assertEquals(listOf("com.example.fixture.BootReceiver"), d.receivers.map { it.name })
        assertEquals("com.example.fixture.files", d.providers.single().authorities)
        assertEquals("@mipmap/ic_app", d.iconRef)
        assertEquals("res/mipmap-xxhdpi-v4/ic_app.png", d.iconPath) // highest density chosen
        val icon = d.iconBytes!!
        assertEquals(0x89.toByte(), icon[0]); assertEquals('P'.code.toByte(), icon[1])
    }

    @Test fun `resource table names ids and resolves locales`() {
        val t = ResourceTable.parse(entry(fixture("fixture.apk"), "resources.arsc"))
        assertEquals(listOf("com.example.fixture"), t.packageNames)
        val dump = t.dump()
        assertTrue(dump, dump.contains("@string/app_name = \"Fixture Viewer\""))
        assertTrue(dump, dump.contains("@color/brand = #ff3366"))
        val id = Regex("0x([0-9a-f]{8}) @string/app_name").find(dump)!!.groupValues[1].toLong(16).toInt()
        assertEquals("string/app_name", t.nameOf(id))
        assertEquals("Visionneuse", t.resolveString(id, "fr"))
        assertEquals("Fixture Viewer", t.resolveString(id))
        assertNull(t.nameOf(0x7f7f0000))
    }

    @Test fun `compiled res xml decodes`() {
        val apk = fixture("fixture.apk")
        val name = ZipFile(apk).use { z -> z.entries().asSequence().map { it.name }.first { it.startsWith("res/xml") } }
        val bytes = entry(apk, name)
        assertTrue(BinaryXml.isBinaryXml(bytes))
        val xml = BinaryXml.decodeToText(bytes)
        assertTrue(xml, xml.contains("<files-path"))
        assertTrue(xml, xml.contains("path=\"shared/\""))
        assertFalse(BinaryXml.isBinaryXml("<xml/>".toByteArray()))
    }

    @Test fun `split bundle uses the base apk`() {
        val bundle = File.createTempFile("bundle", ".apks").apply { deleteOnExit() }
        ZipOutputStream(bundle.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("base.apk")); z.write(fixture("fixture.apk").readBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("split_config.xxhdpi.apk")); z.write(fixture("fixture.apk").readBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("toc.pb")); z.write(byteArrayOf(1)); z.closeEntry()
        }
        val d = ApkParser.parse(bundle, "Fixture.apks")
        assertEquals("com.example.fixture", d.packageName)
        assertEquals("base.apk", d.bundleBase)
        assertEquals(2, d.splitCount)
        assertEquals("Fixture.apks", d.fileName)
        assertNotNull(d.iconBytes)
    }

    @Test fun `garbage is rejected with a clear error`() {
        val e = runCatching { BinaryXml.parse(ByteArray(64) { 7 }) }.exceptionOrNull()
        assertTrue(e is BadResourceException)
        val t = runCatching { ResourceTable.parse(byteArrayOf(2, 0, 12, 0, 0x7f, 0x7f, 0x7f, 0x7f, 0, 0, 0, 0)) }
        assertTrue(t.isSuccess || t.exceptionOrNull() is BadResourceException)
        // A truncated manifest fails cleanly instead of crashing.
        val real = entry(fixture("fixture.apk"), "AndroidManifest.xml")
        val cut = runCatching { BinaryXml.parse(real.copyOf(real.size / 2)) }.exceptionOrNull()
        assertTrue("$cut", cut == null || cut is BadResourceException)
    }
}
