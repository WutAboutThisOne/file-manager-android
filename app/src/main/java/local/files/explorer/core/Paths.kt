package local.files.explorer.core

data class Crumb(val label: String, val path: String)

/** A named starting point such as "/storage/emulated/0" -> "Internal storage". */
data class PathRoot(val path: String, val label: String)

object Paths {
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return "/"
        val out = ArrayDeque<String>()
        for (seg in raw.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out.addLast(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    fun parent(path: String): String? {
        val n = normalize(path)
        if (n == "/") return null
        val idx = n.lastIndexOf('/')
        return if (idx <= 0) "/" else n.substring(0, idx)
    }

    fun name(path: String): String {
        val n = normalize(path)
        return if (n == "/") "/" else n.substring(n.lastIndexOf('/') + 1)
    }

    fun child(parent: String, name: String): String {
        val p = normalize(parent)
        return if (p == "/") "/$name" else "$p/$name"
    }

    fun isAncestorOrSelf(ancestor: String, path: String): Boolean {
        val a = normalize(ancestor)
        val p = normalize(path)
        return a == p || a == "/" || p.startsWith("$a/")
    }

    /** Shell-quotes a path for use in `sh -c` strings. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Builds breadcrumbs. If the path is inside one of [roots] (longest match
     * wins) the first crumb is that root's label, otherwise it is "/".
     */
    fun breadcrumbs(path: String, roots: List<PathRoot> = emptyList()): List<Crumb> {
        val p = normalize(path)
        val root = roots
            .map { it.copy(path = normalize(it.path)) }
            .filter { isAncestorOrSelf(it.path, p) && it.path != "/" }
            .maxByOrNull { it.path.length }
        val crumbs = mutableListOf<Crumb>()
        val base: String
        if (root != null) {
            crumbs += Crumb(root.label, root.path)
            base = root.path
        } else {
            crumbs += Crumb("/", "/")
            base = "/"
        }
        val rest = if (base == "/") p.removePrefix("/") else p.removePrefix(base).removePrefix("/")
        var acc = base
        for (seg in rest.split('/').filter { it.isNotEmpty() }) {
            acc = child(acc, seg)
            crumbs += Crumb(seg, acc)
        }
        return crumbs
    }
}
