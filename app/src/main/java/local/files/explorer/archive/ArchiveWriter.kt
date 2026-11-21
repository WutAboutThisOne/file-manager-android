package local.files.explorer.archive

import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.tukaani.xz.LZMA2Options
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Date

enum class CompressLevel(val label: String) { STORE("Store"), FAST("Fast"), NORMAL("Normal"), MAX("Max") }

/** What the Compress dialog produces. */
data class CompressSpec(
    val name: String,
    val format: ArchiveFormat,
    val level: CompressLevel = CompressLevel.NORMAL,
    val password: CharArray? = null,
) {
    init { require(format.canWrite) { "Can't write ${format.label}" } }
    override fun equals(other: Any?) = other is CompressSpec && other.name == name && other.format == format && other.level == level &&
        (other.password?.contentEquals(password ?: CharArray(0)) ?: (password == null))
    override fun hashCode() = name.hashCode() * 31 + format.hashCode()
    override fun toString() = "CompressSpec($name, $format, $level, password=${if (password != null) "set" else "none"})"
}

/** One file or folder to put in an archive. [open] is called once, only for files. */
class SourceItem(val relPath: String, val isDirectory: Boolean, val size: Long, val lastModified: Long, val open: () -> InputStream)

class CancelledException : IOException("Cancelled")

object ArchiveWriter {
    /**
     * Streams [items] into [out]. [onBytes] gets the running byte count and current item path.
     * The output is deleted on failure or cancel.
     */
    fun write(
        out: File,
        spec: CompressSpec,
        items: Sequence<SourceItem>,
        onBytes: (Long, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        try {
            when (spec.format) {
                ArchiveFormat.ZIP -> writeZip(out, spec, items, onBytes, isCancelled)
                ArchiveFormat.SEVEN_Z -> write7z(out, spec, items, onBytes, isCancelled)
                else -> throw IOException("Can't create ${spec.format.label} archives")
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
    }

    private inline fun pump(input: InputStream, sink: (ByteArray, Int) -> Unit, done: Long, item: String, onBytes: (Long, String) -> Unit, isCancelled: () -> Boolean): Long {
        val buf = ByteArray(64 * 1024)
        var total = done
        while (true) {
            if (isCancelled()) throw CancelledException()
            val n = input.read(buf)
            if (n < 0) break
            sink(buf, n)
            total += n
            onBytes(total, item)
        }
        return total
    }

    private fun writeZip(out: File, spec: CompressSpec, items: Sequence<SourceItem>, onBytes: (Long, String) -> Unit, isCancelled: () -> Boolean) {
        val pw = spec.password?.takeIf { it.isNotEmpty() }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 64 * 1024), pw).use { zos ->
            var done = 0L
            for (item in items) {
                if (isCancelled()) throw CancelledException()
                val p = ZipParameters().apply {
                    fileNameInZip = if (item.isDirectory) item.relPath.trimEnd('/') + "/" else item.relPath
                    lastModifiedFileTime = if (item.lastModified > 0) item.lastModified else System.currentTimeMillis()
                    if (spec.level == CompressLevel.STORE || item.isDirectory) {
                        compressionMethod = CompressionMethod.STORE
                        entrySize = if (item.isDirectory) 0 else item.size
                    } else {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = when (spec.level) {
                            CompressLevel.FAST -> CompressionLevel.FASTEST
                            CompressLevel.MAX -> CompressionLevel.ULTRA
                            else -> CompressionLevel.NORMAL
                        }
                    }
                    if (pw != null && !item.isDirectory) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                zos.putNextEntry(p)
                if (!item.isDirectory) item.open().use { input ->
                    done = pump(input, { b, n -> zos.write(b, 0, n) }, done, item.relPath, onBytes, isCancelled)
                }
                zos.closeEntry()
            }
        }
    }

    private fun write7z(out: File, spec: CompressSpec, items: Sequence<SourceItem>, onBytes: (Long, String) -> Unit, isCancelled: () -> Boolean) {
        val pw = spec.password?.takeIf { it.isNotEmpty() }
        val seven = if (pw != null) SevenZOutputFile(out, pw) else SevenZOutputFile(out)
        seven.use { z ->
            val method = when (spec.level) {
                CompressLevel.STORE -> SevenZMethodConfiguration(SevenZMethod.COPY)
                // LZMA2 presets kept modest: preset 9 needs ~670 MB of RAM to encode.
                CompressLevel.FAST -> SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(1))
                CompressLevel.NORMAL -> SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(3))
                CompressLevel.MAX -> SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(5))
            }
            // With a password, SevenZOutputFile prepends AES-256/SHA-256 itself (and encrypts the header).
            z.setContentMethods(listOf(method))
            var done = 0L
            for (item in items) {
                if (isCancelled()) throw CancelledException()
                val e = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry().apply {
                    name = item.relPath.trimEnd('/')
                    isDirectory = item.isDirectory
                    setHasStream(!item.isDirectory)
                    if (item.lastModified > 0) lastModifiedDate = Date(item.lastModified)
                }
                z.putArchiveEntry(e)
                if (!item.isDirectory) item.open().use { input ->
                    done = pump(input, { b, n -> z.write(b, 0, n) }, done, item.relPath, onBytes, isCancelled)
                }
                z.closeArchiveEntry()
            }
            z.finish()
        }
    }
}
