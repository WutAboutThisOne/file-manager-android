package local.files.explorer.archive

import local.files.explorer.core.Backend
import local.files.explorer.core.ConflictPolicy
import local.files.explorer.core.FileEntry
import local.files.explorer.core.PackageKind
import local.files.explorer.core.PackageSniffer
import local.files.explorer.fs.ArchiveBackend
import local.files.explorer.fs.FileOperationEngine
import local.files.explorer.fs.LocalBackend
import local.files.explorer.ops.Compressor
import local.files.explorer.ops.OpKind
import local.files.explorer.ops.OpRequest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTest {
    @get:Rule val tmp = TemporaryFolder()
    private val local = LocalBackend()

    private fun tree(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    /** A source folder with nested, empty and unicode-named items. */
    private fun sample(): File {
        val src = tmp.newFolder("src")
        File(src, "docs/notes.txt").apply { parentFile.mkdirs(); writeText("hello notes") }
        File(src, "docs/deep/a b.txt").apply { parentFile.mkdirs(); writeText("spaces") }
        File(src, "Ünïcødé 日本語 😀.txt").writeText("unicode body ✓")
        File(src, "empty").mkdirs()
        File(src, "big.bin").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        return src
    }

    private fun sources(src: File) = src.listFiles()!!.sortedBy { it.name }.map { local.stat(it.path)!! }

    private fun compress(src: File, spec: CompressSpec, out: File) {
        val items = Compressor { local }.collect(sources(src)).map { it.first }
        ArchiveWriter.write(out, spec, items.asSequence())
    }

    private fun readAll(file: File, format: ArchiveFormat, pw: String? = null): Map<String, String> =
        ArchiveReaders.open(file, format, pw?.toCharArray()).use { r ->
            r.items().filter { !it.isDirectory }.associate { it.path to r.open(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } }
        }

    private fun expected(src: File) = tree(src).mapKeys { it.key }

    // ---- path traversal -------------------------------------------------------

    @Test fun entryNamesAreSanitised() {
        assertEquals("a/b.txt", EntryNames.safe("a/b.txt"))
        assertEquals("a/b.txt", EntryNames.safe("./a//b.txt"))
        assertEquals("dir/x", EntryNames.safe("dir\\x"))
        assertEquals("dir", EntryNames.safe("dir/"))
        assertNull(EntryNames.safe("../evil.txt"))
        assertNull(EntryNames.safe("a/../../evil.txt"))
        assertNull(EntryNames.safe("a/..\\..\\evil.txt"))
        assertNull(EntryNames.safe("/etc/passwd"))
        assertNull(EntryNames.safe("\\windows\\x"))
        assertNull(EntryNames.safe("C:\\boot.ini"))
        assertNull(EntryNames.safe("c:/x"))
        assertNull(EntryNames.safe(""))
        assertNull(EntryNames.safe("./"))
        assertNull(EntryNames.safe("a\u0000b"))
        assertEquals("日本/ü.txt", EntryNames.safe("日本/ü.txt"))
    }

    @Test fun treeSynthesisesFoldersAndDropsUnsafeEntries() {
        val t = ArchiveTree(listOf(
            ArchiveItem("a/b/c.txt", false, 3),
            ArchiveItem("../../evil.sh", false, 5),
            ArchiveItem("/abs.txt", false, 1),
            ArchiveItem("top.txt", false, 2, compressedSize = 1),
            ArchiveItem("a/", true, 0),
        ))
        assertEquals(2, t.skippedUnsafe)
        assertEquals(listOf("a", "top.txt"), t.list("").map { it.name }.sorted())
        assertEquals(listOf("b"), t.list("a").map { it.name })
        assertTrue(t.isDirectory("a/b"))
        assertEquals(5L, t.totalSize)
        assertEquals(2, t.fileCount)
        assertNull(t.get("evil.sh"))
    }

    @Test fun zipSlipEntriesAreNeverWrittenOutsideTheDestination() {
        val z = File(tmp.root, "slip.zip")
        ZipOutputStream(z.outputStream()).use { zos ->
            for (n in listOf("../../escaped.txt", "ok/../../../escaped2.txt", "/abs-escaped.txt", "good/inside.txt", "..\\win-escaped.txt")) {
                zos.putNextEntry(ZipEntry(n)); zos.write("x".toByteArray()); zos.closeEntry()
            }
        }
        val archive = ArchiveBackend()
        val root = archive.register(z, ArchiveFormat.ZIP)
        assertEquals(listOf("good"), archive.list(root).map { it.name })
        val outer = tmp.newFolder("outer"); val dest = File(outer, "dest").apply { mkdirs() }
        val engine = FileOperationEngine({ if (it == Backend.ARCHIVE) archive else local })
        val r = engine.transfer(archive.list(root), Backend.LOCAL, dest.path, move = false, ConflictPolicy.KEEP_BOTH)
        assertEquals(1, r.done)
        assertEquals(mapOf("dest/good/inside.txt" to "x"), tree(outer))
        assertEquals(4, archive.session(root)!!.tree().skippedUnsafe)
    }

    @Test fun tarSymlinksAndDevicesAreSkipped() {
        val f = File(tmp.root, "links.tar")
        TarArchiveOutputStream(f.outputStream()).use { t ->
            t.putArchiveEntry(TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/data/data/secret" }); t.closeArchiveEntry()
            val body = "real".toByteArray()
            t.putArchiveEntry(TarArchiveEntry("real.txt").apply { size = body.size.toLong() }); t.write(body); t.closeArchiveEntry()
        }
        assertEquals(mapOf("real.txt" to "real"), readAll(f, ArchiveFormat.TAR))
    }

    // ---- zip bombs -------------------------------------------------------------

    @Test fun guardStopsEntriesThatExpandPastTheRatioCap() {
        val z = File(tmp.root, "bomb.zip")
        ZipOutputStream(z.outputStream()).use { zos ->
            // Zeros compress ~1000:1. Declared size is honest here; the ratio cap catches it.
            zos.putNextEntry(ZipEntry("zeros.bin")); val buf = ByteArray(1 shl 20); repeat(8) { zos.write(buf) }; zos.closeEntry()
        }
        val limits = BombLimits(maxRatio = 50, ratioFloorBytes = 1L shl 20)
        val archive = ArchiveBackend { limits }
        val root = archive.register(z, ArchiveFormat.ZIP)
        assertTrue("open-time check flags it", archive.session(root)!!.suspicious)
        try {
            archive.openInput("$root/zeros.bin").use { it.readBytes() }
            fail("expected ArchiveBombException")
        } catch (e: ArchiveBombException) {
            assertTrue(e.message!!, e.message!!.contains("zip bomb"))
        }
        // Through the engine the failure is reported and the partial file removed.
        val dest = tmp.newFolder("bombout")
        val r = FileOperationEngine({ if (it == Backend.ARCHIVE) archive else local }).transfer(archive.list(root), Backend.LOCAL, dest.path, false, ConflictPolicy.KEEP_BOTH)
        assertEquals(1, r.failed.size)
        assertTrue(r.failed[0], r.failed[0].contains("zip bomb"))
        assertFalse(File(dest, "zeros.bin").exists())
    }

    @Test fun guardStopsEntriesLargerThanTheirHeaderSays() {
        val s = GuardedInputStream(ByteArray(5000).inputStream(), "liar.bin", declaredSize = 100, archiveBytes = 10_000, limits = BombLimits(declaredSlack = 10))
        try { s.readBytes(); fail() } catch (e: ArchiveBombException) { assertTrue(e.message!!.contains("larger than the archive says")) }
        // Normal entries pass.
        assertEquals(100, GuardedInputStream(ByteArray(100).inputStream(), "ok", 100, 50).readBytes().size)
    }

    @Test fun guardRejectsEntriesShorterThanDeclared() {
        val s = GuardedInputStream(ByteArray(50).inputStream(), "cut.bin", declaredSize = 100, archiveBytes = 1000)
        try { s.readBytes(); fail() } catch (e: CorruptArchiveException) {
            assertTrue(e.message!!, e.message!!.contains("shorter than the archive says") && e.message!!.contains("50 of 100"))
        }
        // Unknown declared size (plain .gz streams) is not checked at the end.
        assertEquals(50, GuardedInputStream(ByteArray(50).inputStream(), "x", -1, 1000).readBytes().size)
    }

    @Test fun noFixedPerEntryCapForHonestEntries() {
        // 48 MB entry from a 1 MB archive: honest size, 48x ratio, well under the 256 MB floor; no fixed cap applies.
        val n = 48L shl 20
        val big = object : java.io.InputStream() {
            var left = n
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val k = minOf(len.toLong(), left).toInt(); left -= k; java.util.Arrays.fill(b, off, off + k, 0); return k
            }
        }
        val g = GuardedInputStream(big, "honest.img", declaredSize = n, archiveBytes = 1L shl 20)
        val buf = ByteArray(1 shl 16); while (g.read(buf) >= 0) Unit
        assertEquals(n, g.count)
    }

    @Test fun extractionRefusedWhenDestinationIsTooSmall() {
        val src = sample(); val z = File(tmp.root, "fits.zip")
        compress(src, CompressSpec("fits.zip", ArchiveFormat.ZIP), z)
        val archive = ArchiveBackend()
        val root = archive.register(z, ArchiveFormat.ZIP)
        val dest = tmp.newFolder("tiny")
        val engine = FileOperationEngine({ if (it == Backend.ARCHIVE) archive else local })
        val r = engine.transfer(archive.list(root), Backend.LOCAL, dest.path, false, ConflictPolicy.KEEP_BOTH,
            freeSpace = { _, _ -> 10L }, spaceVerb = "extract")
        assertEquals(1, r.failed.size)
        assertTrue(r.failed[0], r.failed[0].startsWith("Not enough space to extract: needs ") && r.failed[0].endsWith("10 B free."))
        assertTrue("nothing written", dest.listFiles().orEmpty().isEmpty())
        // Enough room: goes through.
        val ok = engine.transfer(archive.list(root), Backend.LOCAL, dest.path, false, ConflictPolicy.KEEP_BOTH,
            freeSpace = { _, _ -> Long.MAX_VALUE }, spaceVerb = "extract")
        assertTrue(ok.failed.toString(), ok.failed.isEmpty())
    }

    @Test fun ordinaryArchivesAreNotSuspicious() {
        val src = sample(); val z = File(tmp.root, "ok.zip")
        compress(src, CompressSpec("ok.zip", ArchiveFormat.ZIP), z)
        val archive = ArchiveBackend()
        assertFalse(archive.session(archive.register(z, ArchiveFormat.ZIP))!!.suspicious)
    }

    // ---- round trips -------------------------------------------------------------

    @Test fun zipRoundTripKeepsFoldersUnicodeAndBytes() {
        val src = sample()
        for (level in CompressLevel.entries) {
            val z = File(tmp.root, "out-$level.zip")
            compress(src, CompressSpec("out", ArchiveFormat.ZIP, level), z)
            assertEquals(level.name, expected(src), readAll(z, ArchiveFormat.ZIP))
            val names = ArchiveReaders.open(z, ArchiveFormat.ZIP).use { r -> r.items().map { it.path } }
            assertTrue(names.contains("empty/"))
            assertTrue(names.contains("Ünïcødé 日本語 😀.txt"))
        }
        // Max compresses the repetitive file better than Store.
        assertTrue(File(tmp.root, "out-MAX.zip").length() < File(tmp.root, "out-STORE.zip").length())
        // Readable by a third-party reader too (java.util.zip), names in UTF-8.
        java.util.zip.ZipFile(File(tmp.root, "out-NORMAL.zip")).use { assertNotNull(it.getEntry("Ünïcødé 日本語 😀.txt")) }
    }

    @Test fun sevenZipRoundTrip() {
        val src = sample()
        for (level in CompressLevel.entries) {
            val f = File(tmp.root, "out-$level.7z")
            compress(src, CompressSpec("out", ArchiveFormat.SEVEN_Z, level), f)
            assertEquals(level.name, expected(src), readAll(f, ArchiveFormat.SEVEN_Z))
        }
        val dirs = ArchiveReaders.open(File(tmp.root, "out-NORMAL.7z"), ArchiveFormat.SEVEN_Z).use { r -> r.items().filter { it.isDirectory }.map { it.path } }
        assertTrue(dirs.toString(), "empty" in dirs)
    }

    @Test fun extractThroughTheEngineMatchesTheSource() {
        val src = sample()
        for (fmt in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z)) {
            val f = File(tmp.root, "e.${if (fmt == ArchiveFormat.ZIP) "zip" else "7z"}")
            compress(src, CompressSpec("e", fmt), f)
            val archive = ArchiveBackend()
            val root = archive.register(f, fmt)
            val dest = tmp.newFolder("x-$fmt")
            val progress = mutableListOf<local.files.explorer.fs.OpProgress>()
            val r = FileOperationEngine({ if (it == Backend.ARCHIVE) archive else local }).transfer(
                archive.list(root), Backend.LOCAL, dest.path, false, ConflictPolicy.KEEP_BOTH, { progress += it },
            )
            assertEquals(r.failed.toString(), 4, r.done)
            assertEquals(expected(src), tree(dest))
            assertTrue(File(dest, "empty").isDirectory)
            assertEquals(progress.last().bytesTotal, progress.last().bytesDone)
            // Second extraction into the same folder: conflicts resolved by the existing policy.
            val again = FileOperationEngine({ if (it == Backend.ARCHIVE) archive else local }).transfer(
                archive.list(root).filter { it.name == "big.bin" }, Backend.LOCAL, dest.path, false, ConflictPolicy.KEEP_BOTH,
            )
            assertEquals(1, again.done)
            assertTrue(File(dest, "big (1).bin").exists())
        }
    }

    // ---- passwords ---------------------------------------------------------------

    @Test fun zipAesPasswordFlow() {
        val src = sample(); val z = File(tmp.root, "secret.zip")
        compress(src, CompressSpec("secret", ArchiveFormat.ZIP, password = "hunter2".toCharArray()), z)
        // Really AES-256.
        ZipFile(z).fileHeaders.filter { !it.isDirectory }.forEach {
            assertTrue(it.isEncrypted); assertEquals(EncryptionMethod.AES, it.encryptionMethod)
            assertEquals(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256, it.aesExtraDataRecord.aesKeyStrength)
        }
        val archive = ArchiveBackend()
        val root = archive.register(z, ArchiveFormat.ZIP)
        // Listing works without the password (zip names aren't encrypted) …
        assertEquals(4, archive.list(root).size)
        assertTrue(archive.session(root)!!.tree().anyEncrypted)
        // … but reading needs it.
        try { archive.openInput("$root/docs/notes.txt").use { it.readBytes() }; fail() } catch (_: PasswordRequiredException) {}
        try { archive.session(root)!!.unlock("wrong".toCharArray()); fail() } catch (_: WrongPasswordException) {}
        assertNull(archive.session(root)!!.password)
        archive.session(root)!!.unlock("hunter2".toCharArray())
        assertEquals("hello notes", archive.openInput("$root/docs/notes.txt").use { it.readBytes().toString(Charsets.UTF_8) })
        assertEquals(expected(src), readAll(z, ArchiveFormat.ZIP, "hunter2"))
    }

    @Test fun sevenZipAesPasswordFlow() {
        val src = sample(); val f = File(tmp.root, "secret.7z")
        compress(src, CompressSpec("secret", ArchiveFormat.SEVEN_Z, password = "pässwörd".toCharArray()), f)
        val archive = ArchiveBackend()
        val root = archive.register(f, ArchiveFormat.SEVEN_Z)
        // Either the header is encrypted (listing needs the password) or the content is: both must ask.
        val locked = try {
            archive.list(root)
            try { archive.openInput("$root/docs/notes.txt").use { it.readBytes() }; false } catch (_: PasswordRequiredException) { true }
        } catch (_: PasswordRequiredException) { true }
        assertTrue("7z should require the password", locked)
        try { archive.session(root)!!.unlock("nope".toCharArray()); fail() } catch (_: WrongPasswordException) {}
        archive.session(root)!!.unlock("pässwörd".toCharArray())
        assertEquals(4, archive.list(root).size)
        assertEquals("unicode body ✓", archive.openInput("$root/Ünïcødé 日本語 😀.txt").use { it.readBytes().toString(Charsets.UTF_8) })
        assertEquals(expected(src), readAll(f, ArchiveFormat.SEVEN_Z, "pässwörd"))
        // Content really is encrypted: the plaintext doesn't appear in the file.
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("hello notes"))
    }

    // ---- tar family & single-file compressors -----------------------------------

    private fun tar(out: OutputStream, entries: Map<String, String>) = TarArchiveOutputStream(out, "UTF-8").use { t ->
        t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        t.putArchiveEntry(TarArchiveEntry("dir/")); t.closeArchiveEntry()
        for ((n, body) in entries) {
            val b = body.toByteArray()
            t.putArchiveEntry(TarArchiveEntry(n).apply { size = b.size.toLong() }); t.write(b); t.closeArchiveEntry()
        }
    }

    @Test fun tarFamilyListsAndReads() {
        val content = mapOf("dir/a.txt" to "AAA", "dir/sub/ü 日本.txt" to "unicode", "readme.md" to "# hi", "x/" + "long".repeat(40) + ".txt" to "long name")
        val files = mapOf(
            ArchiveFormat.TAR to File(tmp.root, "t.tar").also { tar(it.outputStream(), content) },
            ArchiveFormat.TAR_GZ to File(tmp.root, "t.tar.gz").also { tar(GzipCompressorOutputStream(it.outputStream()), content) },
            ArchiveFormat.TAR_XZ to File(tmp.root, "t.tar.xz").also { tar(XZCompressorOutputStream(it.outputStream()), content) },
            ArchiveFormat.TAR_BZ2 to File(tmp.root, "t.tar.bz2").also { tar(BZip2CompressorOutputStream(it.outputStream()), content) },
        )
        for ((fmt, f) in files) {
            assertEquals(fmt, ArchiveFormat.of(f.name))
            assertEquals(fmt.name, content, readAll(f, fmt))
            val archive = ArchiveBackend()
            val root = archive.register(f, fmt)
            assertEquals(listOf("dir", "readme.md", "x"), archive.list(root).map { it.name }.sorted())
            assertEquals(listOf("a.txt", "sub"), archive.list("$root/dir").map { it.name }.sorted())
        }
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.of("backup.TGZ"))
    }

    @Test fun singleFileCompressors() {
        val body = "line\n".repeat(1000)
        val gz = File(tmp.root, "log.txt.gz").apply { GzipCompressorOutputStream(outputStream()).use { it.write(body.toByteArray()) } }
        val xz = File(tmp.root, "data.json.xz").apply { XZCompressorOutputStream(outputStream()).use { it.write(body.toByteArray()) } }
        val bz = File(tmp.root, "dump.sql.bz2").apply { BZip2CompressorOutputStream(outputStream()).use { it.write(body.toByteArray()) } }
        assertEquals(mapOf("log.txt" to body), readAll(gz, ArchiveFormat.GZ))
        assertEquals(mapOf("data.json" to body), readAll(xz, ArchiveFormat.XZ))
        assertEquals(mapOf("dump.sql" to body), readAll(bz, ArchiveFormat.BZ2))
        // gzip's trailer gives the real size for display.
        assertEquals(body.length.toLong(), ArchiveReaders.open(gz, ArchiveFormat.GZ).use { it.items()[0].size })
    }

    @Test fun magicSniffing() {
        fun header(f: File) = f.inputStream().use { i -> ByteArray(600).let { b -> val n = i.read(b); b.copyOf(n) } }
        val src = sample()
        val z = File(tmp.root, "a.zip").also { compress(src, CompressSpec("a", ArchiveFormat.ZIP), it) }
        val s = File(tmp.root, "a.7z").also { compress(src, CompressSpec("a", ArchiveFormat.SEVEN_Z), it) }
        val t = File(tmp.root, "a.tar").also { tar(it.outputStream(), mapOf("a" to "b")) }
        val g = File(tmp.root, "a.gz").apply { GzipCompressorOutputStream(outputStream()).use { it.write(1) } }
        val x = File(tmp.root, "a.xz").apply { XZCompressorOutputStream(outputStream()).use { it.write(1) } }
        val b = File(tmp.root, "a.bz2").apply { BZip2CompressorOutputStream(outputStream()).use { it.write(1) } }
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.sniff(header(z)))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.sniff(header(s)))
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.sniff(header(t)))
        assertEquals(ArchiveFormat.GZ, ArchiveFormat.sniff(header(g)))
        assertEquals(ArchiveFormat.XZ, ArchiveFormat.sniff(header(x)))
        assertEquals(ArchiveFormat.BZ2, ArchiveFormat.sniff(header(b)))
        assertNull(ArchiveFormat.sniff("%PDF-1.7".toByteArray()))
        assertNull(ArchiveFormat.of("photo.jpg")); assertNull(ArchiveFormat.of("app.apk")); assertNull(ArchiveFormat.of("bundle.apks"))
    }

    // ---- backend & compressor -------------------------------------------------

    @Test fun archiveBackendIsReadOnlyAndHandlesBangsInNames() {
        val src = sample()
        val dir = tmp.newFolder("we!rd")
        val z = File(dir, "a!b.zip").also { compress(src, CompressSpec("a", ArchiveFormat.ZIP), it) }
        val archive = ArchiveBackend()
        val root = archive.register(z, ArchiveFormat.ZIP)
        assertEquals(z.absolutePath + "!", root)
        assertEquals("docs/deep", archive.split("$root/docs/deep")!!.second)
        assertEquals("$root/docs", archive.findChild(root, "docs"))
        val notes = archive.stat("$root/docs/notes.txt")!!
        assertEquals(Backend.ARCHIVE, notes.backend); assertEquals(11L, notes.size); assertNotNull(notes.compressedSize)
        assertEquals(2, archive.stat("$root/docs")!!.childCount)
        for (op in listOf<() -> Unit>({ archive.mkdir(root, "x") }, { archive.delete("$root/docs") }, { archive.createFile(root, "y") }, { archive.rename("$root/docs", "z") })) {
            try { op(); fail() } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("read-only")) }
        }
        // Re-registering keeps the same session (and its password).
        assertEquals(root, archive.register(z, ArchiveFormat.ZIP))
    }

    @Test fun compressorWritesUniqueNameAndCleansUpOnCancel() {
        val src = sample()
        val dest = tmp.newFolder("dest")
        File(dest, "My files.zip").writeText("already here")
        val req = OpRequest(OpKind.COMPRESS, sources(src), Backend.LOCAL, dest.path, "dest", compress = CompressSpec("My files", ArchiveFormat.ZIP))
        val progress = mutableListOf<local.files.explorer.fs.OpProgress>()
        val r = Compressor { local }.run(tmp.newFolder("cache"), req, { progress += it }, { false })
        assertEquals(r.failed.toString(), 4, r.done)
        val out = File(dest, "My files (1).zip")
        assertEquals(listOf(out.path), r.createdPaths)
        assertEquals("already here", File(dest, "My files.zip").readText())
        assertEquals(expected(src), readAll(out, ArchiveFormat.ZIP))
        assertEquals(progress.last().bytesTotal, progress.last().bytesDone)

        var calls = 0
        val cancelled = Compressor { local }.run(tmp.newFolder("cache2"), req.copy(compress = CompressSpec("Cancelled", ArchiveFormat.SEVEN_Z)), {}, { ++calls > 3 })
        assertTrue(cancelled.cancelled)
        assertEquals(listOf("My files (1).zip", "My files.zip"), dest.list()!!.sorted()) // no partial or hidden temp left
    }

    @Test fun compressedArchiveOfAFolderKeepsItsName() {
        val src = sample()
        val docs = local.stat(File(src, "docs").path)!!
        val out = File(tmp.root, "docs.7z")
        ArchiveWriter.write(out, CompressSpec("docs", ArchiveFormat.SEVEN_Z), Compressor { local }.collect(listOf(docs)).map { it.first }.asSequence())
        assertEquals(mapOf("docs/notes.txt" to "hello notes", "docs/deep/a b.txt" to "spaces"), readAll(out, ArchiveFormat.SEVEN_Z))
        assertEquals("docs.7z", Compressor.fileName(CompressSpec("docs", ArchiveFormat.SEVEN_Z)))
        assertEquals("docs.zip", Compressor.fileName(CompressSpec("docs.zip", ArchiveFormat.ZIP)))
    }

    // ---- incoming routing (pure part) --------------------------------------------

    @Test fun incomingZipsAreRoutedByContent() {
        assertEquals(PackageKind.APK, PackageSniffer.classifyIncoming(listOf("AndroidManifest.xml", "classes.dex"), "x.zip"))
        assertEquals(PackageKind.BUNDLE, PackageSniffer.classifyIncoming(listOf("base.apk", "split_config.arm64_v8a.apk"), "game.bin"))
        assertEquals(PackageKind.BUNDLE, PackageSniffer.classifyIncoming(listOf("com.game.apk", "config.en.apk", "manifest.json"), null))
        assertEquals(PackageKind.BUNDLE, PackageSniffer.classifyIncoming(listOf("Game.apk"), "Game.xapk"))
        assertEquals(PackageKind.ARCHIVE, PackageSniffer.classifyIncoming(listOf("a.jpg", "b.jpg"), "photos.zip"))
        // A zip that merely contains an APK opens as an archive; the APK inside can be installed from there.
        assertEquals(PackageKind.ARCHIVE, PackageSniffer.classifyIncoming(listOf("MyApp.apk", "README.txt"), "release.zip"))
        assertEquals(PackageKind.ARCHIVE, PackageSniffer.classifyIncoming(emptyList(), "empty.zip"))
    }
}
