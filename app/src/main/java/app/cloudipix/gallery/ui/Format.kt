package app.cloudipix.gallery.ui

import java.text.NumberFormat
import java.util.Locale

private val SIZE_UNITS = listOf("B", "kB", "MB", "GB", "TB")
private const val SIZE_STEP = 1000.0

/** 바이트 수를 10진 단위(1 kB = 1000 B)로 표시합니다. 바이트 단위는 정수로, 그 이상은 소수 한 자리로 씁니다. */
fun formatSize(bytes: Long): String {
    if (bytes < SIZE_STEP) return "${bytes.coerceAtLeast(0)} B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= SIZE_STEP && unit < SIZE_UNITS.lastIndex) {
        value /= SIZE_STEP
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, SIZE_UNITS[unit])
}

// NumberFormat은 스레드에 안전하지 않으므로 스레드마다 로캘별 인스턴스를 따로 둡니다.
private val integerFormats = object : ThreadLocal<HashMap<Locale, NumberFormat>>() {
    override fun initialValue() = HashMap<Locale, NumberFormat>(2)
}

/** 개수를 현재 로캘의 자릿수 구분 기호로 표시합니다. */
fun formatCount(count: Int): String {
    val locale = Locale.getDefault()
    val format = integerFormats.get()!!.getOrPut(locale) { NumberFormat.getIntegerInstance(locale) }
    return format.format(count.toLong())
}

/** 재생 시간을 `m:ss`로, 한 시간 이상이면 `h:mm:ss`로 표시합니다. */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) {
            append(hours).append(':')
            appendTwoDigits(minutes)
        } else {
            append(minutes)
        }
        append(':')
        appendTwoDigits(seconds)
    }
}

private fun StringBuilder.appendTwoDigits(value: Long) {
    if (value < 10) append('0')
    append(value)
}
