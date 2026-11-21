package local.files.explorer.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    /** SI units, like Android's own Settings > Storage. */
    fun size(bytes: Long): String {
        if (bytes < 1000) return "$bytes B"
        val units = arrayOf("kB", "MB", "GB", "TB", "PB")
        var v = bytes.toDouble()
        var i = -1
        while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i])
        else String.format(Locale.US, "%.1f %s", v, units[i]).replace(".0 ", " ")
    }

    fun date(ms: Long, locale: Locale = Locale.getDefault()): String =
        if (ms <= 0) "—" else SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(ms))

    fun items(n: Int): String = if (n == 1) "1 item" else "$n items"
}
