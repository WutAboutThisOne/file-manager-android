package local.files.explorer.fs

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import local.files.explorer.core.Backend
import local.files.explorer.core.FileEntry
import local.files.explorer.core.Paths
import local.files.explorer.core.RootListingParser
import local.files.explorer.root.RootShell
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Files seen through the libsu root shell. Read-only unless [writesEnabled]
 * (the user has to explicitly accept the warning in Settings).
 */
class RootBackend(private val writesEnabled: () -> Boolean) : FsBackend {
    override val kind = Backend.ROOT

    companion object {
        /** Top-level trees we never delete wholesale, even with root writes on. */
        val PROTECTED = setOf("/", "/system", "/data", "/vendor", "/product", "/proc", "/sys", "/dev", "/apex", "/odm", "/storage", "/sdcard", "/mnt")
    }

    private fun requireRoot() {
        if (!RootShell.isGranted) throw IOException("Root access isn't available")
    }

    private fun requireWrites() {
        requireRoot()
        if (!writesEnabled()) throw IOException("Root locations are read-only. Turn on root writes in Settings first.")
    }

    private fun sh(cmd: String): Shell.Result {
        requireRoot()
        return Shell.cmd(cmd).exec()
    }

    override fun list(dir: String): List<FileEntry> {
        val r = sh(RootListingParser.script(dir))
        if (!r.isSuccess && r.out.isEmpty()) throw IOException(r.err.firstOrNull() ?: "Can't read $dir")
        return RootListingParser.parse(dir, r.out)
    }

    override fun stat(path: String): FileEntry? {
        val parent = Paths.parent(path) ?: return FileEntry("/", "/", Backend.ROOT, isDirectory = true, permissions = "drwxr-xr-x", owner = "root", group = "root")
        val name = Paths.name(path)
        val q = Paths.shellQuote(path)
        val r = sh("stat -c '%A\t%U\t%G\t%s\t%Y\t%n' -- $q; echo '${RootListingParser.MARKER}'; [ -L $q ] && [ -d $q ] && echo $q")
        val lines = r.out.map { line ->
            // stat prints the full path as %n; make it look like a name in [parent]
            if (line.contains('\t')) line.substringBeforeLast('\t') + "\t" + name else if (line == path) name else line
        }
        return RootListingParser.parse(parent, lines).firstOrNull()
    }

    override fun findChild(dir: String, name: String): String? {
        val p = Paths.child(dir, name)
        val q = Paths.shellQuote(p)
        return if (sh("[ -e $q ] || [ -L $q ]").isSuccess) p else null
    }

    override fun openInput(path: String): InputStream {
        requireRoot()
        return SuFileInputStream.open(SuFile(path))
    }

    override fun createFile(dir: String, name: String, mime: String?): Pair<String, OutputStream> {
        requireWrites()
        val p = Paths.child(dir, name)
        return p to SuFileOutputStream.open(SuFile(p))
    }

    override fun mkdir(dir: String, name: String): String {
        requireWrites()
        val p = Paths.child(dir, name)
        val r = sh("mkdir -p ${Paths.shellQuote(p)}")
        if (!r.isSuccess) throw IOException(r.err.firstOrNull() ?: "mkdir failed")
        return p
    }

    override fun delete(path: String): Boolean {
        requireWrites()
        val n = Paths.normalize(path)
        if (n in PROTECTED) {
            throw IOException("Refusing to delete $n")
        }
        return sh("rm -rf ${Paths.shellQuote(n)}").isSuccess
    }

    override fun rename(path: String, newName: String): String? {
        requireWrites()
        val dst = Paths.child(Paths.parent(path) ?: return null, newName)
        val r = sh("[ -e ${Paths.shellQuote(dst)} ] || mv -- ${Paths.shellQuote(path)} ${Paths.shellQuote(dst)}")
        return if (r.isSuccess && findChild(Paths.parent(dst)!!, newName) != null && findChild(Paths.parent(path)!!, Paths.name(path)) == null) dst else null
    }

    override fun moveFast(path: String, destDir: String, newName: String): String? {
        requireWrites()
        val dst = Paths.child(destDir, newName)
        val q = Paths.shellQuote(dst)
        val r = sh("[ ! -e $q ] && mv -- ${Paths.shellQuote(path)} $q")
        return if (r.isSuccess) dst else null
    }

    override fun setLastModified(path: String, time: Long) {
        if (!RootShell.isGranted || !writesEnabled()) return
        sh("touch -m -d @${time / 1000} ${Paths.shellQuote(path)}")
    }
}
