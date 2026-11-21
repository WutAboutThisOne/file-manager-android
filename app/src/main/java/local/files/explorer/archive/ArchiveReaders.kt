package local.files.explorer.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.PasswordRequiredException as CcPasswordRequired
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Read access to one archive. Streams entries; never loads whole entries into memory. */
interface ArchiveReader : Closeable {
    val format: ArchiveFormat
    fun items(): List<ArchiveItem>
    fun open(item: ArchiveItem): InputStream
    /** Throws [WrongPasswordException] if the password doesn't open the first encrypted entry. */
    fun verifyPassword() {}
}

object ArchiveReaders {
    fun open(file: File, format: ArchiveFormat, password: CharArray? = null): ArchiveReader = when (format) {
        ArchiveFormat.ZIP -> ZipReader(file, password)
        ArchiveFormat.SEVEN_Z -> SevenZReader(file, password)
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_BZ2 -> TarReader(file, format)
        ArchiveFormat.GZ, ArchiveFormat.XZ, ArchiveFormat.BZ2 -> SingleReader(file, format)
    }

    internal fun decompress(input: InputStream, format: ArchiveFormat): InputStream = when (format) {
        ArchiveFormat.TAR_GZ, ArchiveFormat.GZ -> GzipCompressorInputStream(input, true)
        ArchiveFormat.TAR_XZ, ArchiveFormat.XZ -> XZCompressorInputStream(input)
        ArchiveFormat.TAR_BZ2, ArchiveFormat.BZ2 -> BZip2CompressorInputStream(input, true)
        else -> input
    }
}

private class ZipReader(private val file: File, private val password: CharArray?) : ArchiveReader {
    override val format = ArchiveFormat.ZIP
    private val zip = ZipFile(file, password).apply {
        // Names without the UTF-8 flag are usually CP437 (Windows) — but phones and macOS write UTF-8 anyway.
        charset = Charsets.UTF_8
    }
    private val headers: List<FileHeader> by lazy {
        try { zip.fileHeaders } catch (e: ZipException) { throw CorruptArchiveException("This zip is damaged: ${e.message}") }
    }
    private val byName by lazy { headers.associateBy { it.fileName } }

    override fun items() = headers.map { h ->
        ArchiveItem(
            path = h.fileName, isDirectory = h.isDirectory || h.fileName.endsWith("/"),
            size = h.uncompressedSize, compressedSize = h.compressedSize,
            lastModified = runCatching { net.lingala.zip4j.util.Zip4jUtil.dosToExtendedEpochTme(h.lastModifiedTime) }.getOrDefault(0L),
            encrypted = h.isEncrypted, key = h.fileName,
        )
    }

    override fun open(item: ArchiveItem): InputStream {
        val h = byName[item.key] ?: throw IOException("Entry not found: ${item.path}")
        if (h.isEncrypted && (password == null || password.isEmpty())) throw PasswordRequiredException()
        return try {
            Zip4jStream(zip.getInputStream(h))
        } catch (e: ZipException) {
            throw mapZip(e, h)
        }
    }

    override fun verifyPassword() {
        val h = headers.firstOrNull { it.isEncrypted && !it.isDirectory } ?: return
        open(items().first { it.key == h.fileName }).use { s -> val b = ByteArray(8192); while (s.read(b) >= 0) Unit }
    }

    override fun close() = zip.close()
}

internal fun mapZip(e: ZipException, h: FileHeader? = null): IOException = when (e.type) {
    ZipException.Type.WRONG_PASSWORD -> WrongPasswordException()
    ZipException.Type.CHECKSUM_MISMATCH -> if (h?.isEncrypted == true) WrongPasswordException() else CorruptArchiveException("\u201c${h?.fileName}\u201d is damaged (checksum mismatch).")
    else -> if (e.message?.contains("password", true) == true) {
        if (e.message?.contains("empty", true) == true || e.message?.contains("null", true) == true) PasswordRequiredException() else WrongPasswordException()
    } else CorruptArchiveException(e.message ?: "This zip is damaged.")
}

/** zip4j reports bad passwords / CRC at read or close time; translate those. */
private class Zip4jStream(input: InputStream) : FilterInputStream(input) {
    private fun <T> wrap(f: () -> T): T = try { f() } catch (e: ZipException) { throw mapZip(e) }
    override fun read(): Int = wrap { super.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = wrap { super.read(b, off, len) }
    override fun close() = wrap { super.close() }
}

private class SevenZReader(private val file: File, private val password: CharArray?) : ArchiveReader {
    override val format = ArchiveFormat.SEVEN_Z
    private val seven: SevenZFile = try {
        SevenZFile.builder().setFile(file).apply { if (password != null) setPassword(password) }.get()
    } catch (e: CcPasswordRequired) {
        throw PasswordRequiredException()
    } catch (e: IOException) {
        // Encrypted headers + wrong password show up as garbage while parsing headers.
        throw if (password != null) WrongPasswordException() else CorruptArchiveException("This 7z archive is damaged or unsupported: ${e.message}")
    } catch (e: RuntimeException) {
        throw if (password != null) WrongPasswordException() else CorruptArchiveException("This 7z archive is damaged or unsupported.")
    }
    private val entries: List<SevenZArchiveEntry> = seven.entries.toList()

    /**
     * Commons Compress doesn't expose per-entry coders when reading, and archives written with a password
     * by many tools (including this app) keep names in the clear. So probe: reading the first stream without
     * a password fails with PasswordRequiredException iff the content is encrypted.
     */
    private val encryptedContent: Boolean by lazy {
        val first = entries.firstOrNull { it.hasStream() } ?: return@lazy false
        try {
            SevenZFile.builder().setFile(file).get().use { probe ->
                val e = probe.entries.first { it.name == first.name && it.hasStream() }
                probe.getInputStream(e).use { it.read() }
            }
            false
        } catch (_: CcPasswordRequired) { true } catch (_: IOException) { false } catch (_: RuntimeException) { false }
    }

    override fun items() = entries.mapIndexed { i, e ->
        ArchiveItem(
            path = e.name ?: "", isDirectory = e.isDirectory, size = if (e.hasStream()) e.size else 0,
            compressedSize = -1, lastModified = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0,
            encrypted = e.hasStream() && encryptedContent, key = i.toString(),
        )
    }

    override fun open(item: ArchiveItem): InputStream {
        val e = entries[item.key.toInt()]
        if (!e.hasStream()) return java.io.ByteArrayInputStream(ByteArray(0))
        if (password == null && encryptedContent) throw PasswordRequiredException()
        val s = try { seven.getInputStream(e) } catch (x: CcPasswordRequired) { throw PasswordRequiredException() }
        return SevenZStream(s, encryptedContent)
    }

    /** 7z has no password check value: decode the smallest entry (CRC-checked) and treat garbage as a wrong password. */
    override fun verifyPassword() {
        if (!encryptedContent) return
        val i = entries.indices.filter { entries[it].hasStream() }.minByOrNull { entries[it].size } ?: return
        open(items()[i]).use { s ->
            val b = ByteArray(64 * 1024); var total = 0L
            while (total < 64L * 1024 * 1024) { val n = s.read(b); if (n < 0) break; total += n }
        }
    }

    override fun close() = seven.close()
}

/** A wrong 7z password surfaces as corrupt LZMA data or a CRC error mid-stream. */
private class SevenZStream(input: InputStream, private val encrypted: Boolean) : FilterInputStream(input) {
    private fun <T> wrap(f: () -> T): T = try { f() } catch (e: ArchiveException) { throw e } catch (e: CcPasswordRequired) {
        throw PasswordRequiredException()
    } catch (e: IOException) {
        throw if (encrypted) WrongPasswordException() else CorruptArchiveException(e.message ?: "Damaged archive")
    } catch (e: RuntimeException) {
        throw if (encrypted) WrongPasswordException() else CorruptArchiveException(e.message ?: "Damaged archive")
    }
    override fun read(): Int = wrap { super.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = wrap { super.read(b, off, len) }
}

/** tar / tar.gz / tar.xz / tar.bz2: no index, so listing and opening both stream from the start. */
private class TarReader(private val file: File, override val format: ArchiveFormat) : ArchiveReader {
    private fun stream(): TarArchiveInputStream =
        TarArchiveInputStream(ArchiveReaders.decompress(BufferedInputStream(FileInputStream(file), 64 * 1024), format), Charsets.UTF_8.name())

    private val cached: List<ArchiveItem> by lazy {
        val out = ArrayList<ArchiveItem>()
        try {
            stream().use { t ->
                while (true) {
                    val e: TarArchiveEntry = t.nextEntry ?: break
                    // Links and device nodes are never extracted.
                    if (e.isSymbolicLink || e.isLink || e.isCharacterDevice || e.isBlockDevice || e.isFIFO) continue
                    out += ArchiveItem(e.name, e.isDirectory, if (e.isDirectory) 0 else e.size, -1, e.modTime?.time ?: 0, key = e.name)
                }
            }
        } catch (e: IOException) {
            if (out.isEmpty()) throw CorruptArchiveException("This archive is damaged or isn't a ${format.label} file.")
        }
        out
    }

    override fun items() = cached

    override fun open(item: ArchiveItem): InputStream {
        val t = stream()
        while (true) {
            val e = t.nextEntry ?: run { t.close(); throw IOException("Entry not found: ${item.path}") }
            if (e.name == item.key && !e.isDirectory) return t // TarArchiveInputStream reads only this entry's bytes
        }
    }

    override fun close() {}
}

/** .gz / .xz / .bz2 of a single file: one entry named after the archive without its extension. */
private class SingleReader(private val file: File, override val format: ArchiveFormat) : ArchiveReader {
    private val inner = file.name.replace(Regex("\\.(gz|xz|bz2)$", RegexOption.IGNORE_CASE), "").ifEmpty { "content" }
    override fun items() = listOf(ArchiveItem(inner, false, size = declaredSize(), compressedSize = file.length(), lastModified = file.lastModified()))

    /** gzip stores the size (mod 4 GiB) in its last four bytes; good enough for display when the file is small. */
    private fun declaredSize(): Long {
        if (format != ArchiveFormat.GZ || file.length() < 18 || file.length() > 0xFFFFFFFFL) return -1
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { r ->
                r.seek(file.length() - 4)
                val b = ByteArray(4); r.readFully(b)
                (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
            }
        }.getOrDefault(-1L).let { if (it < file.length() / 2) -1 else it } // concatenated members / wrap-around: unknown
    }
    override fun open(item: ArchiveItem): InputStream =
        try { ArchiveReaders.decompress(BufferedInputStream(FileInputStream(file), 64 * 1024), format) }
        catch (e: IOException) { throw CorruptArchiveException("This ${format.label} file is damaged.") }
    override fun close() {}
}
