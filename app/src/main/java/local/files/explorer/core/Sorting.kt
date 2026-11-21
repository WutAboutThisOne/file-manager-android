package local.files.explorer.core

enum class SortField { NAME, SIZE, DATE, TYPE }

data class SortOrder(
    val field: SortField = SortField.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
)

/** Case-insensitive "natural" comparison so file2 sorts before file10. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                // equal numerically: fewer leading zeros first
                val z = (i - si) - (j - sj)
                if (z != 0) return z
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        val rem = (a.length - i) - (b.length - j)
        if (rem != 0) return rem
        return a.compareTo(b) // stable tiebreak: uppercase before lowercase
    }
}

object Sorter {
    fun comparator(order: SortOrder): Comparator<FileEntry> {
        val byName = Comparator<FileEntry> { x, y -> NaturalOrder.compare(x.name, y.name) }
        val primary: Comparator<FileEntry> = when (order.field) {
            SortField.NAME -> byName
            SortField.SIZE -> compareBy<FileEntry> { if (it.isDirectory) (it.childCount ?: 0).toLong() else it.size }.then(byName)
            SortField.DATE -> compareBy<FileEntry> { it.lastModified }.then(byName)
            SortField.TYPE -> compareBy<FileEntry> { it.extension.lowercase() }.then(byName)
        }
        val directed = if (order.ascending) primary else primary.reversed()
        return if (order.foldersFirst) {
            compareBy<FileEntry> { !it.isDirectory }.then(directed)
        } else directed
    }

    fun sort(entries: List<FileEntry>, order: SortOrder): List<FileEntry> =
        entries.sortedWith(comparator(order))
}
