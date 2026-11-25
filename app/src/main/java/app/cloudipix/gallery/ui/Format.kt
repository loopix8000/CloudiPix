package app.cloudipix.gallery.ui

import java.util.Locale
import java.text.NumberFormat
import kotlin.math.log10
import kotlin.math.pow

private val UNITS = arrayOf("B", "kB", "MB", "GB", "TB")

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val group = (log10(bytes.toDouble()) / 3).toInt().coerceAtMost(UNITS.lastIndex)
    val value = bytes / 1000.0.pow(group)
    return if (group == 0) "$bytes B"
    else String.format(Locale.getDefault(), "%.1f %s", value, UNITS[group])
}

private val countFormat = ThreadLocal<Pair<Locale, NumberFormat>>()

fun formatCount(count: Int): String {
    val locale = Locale.getDefault()
    val cached = countFormat.get()
    val formatter = if (cached?.first == locale) cached.second else {
        NumberFormat.getIntegerInstance(locale).also { countFormat.set(locale to it) }
    }
    return formatter.format(count.toLong())
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes >= 60) String.format(Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}
