package app.cloudipix.gallery.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HeaderKind { Today, Yesterday, Day, Month, Year }

sealed interface GridItem {
    data class Header(val date: LocalDate, val kind: HeaderKind, val count: Int) : GridItem
    data class Item(val medium: Medium) : GridItem
}

fun List<Medium>.grouped(grouping: Grouping, sorting: Sorting): List<GridItem> {
    if (grouping == Grouping.None) return map(GridItem::Item)

    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val stamp = { m: Medium -> if (sorting.key == SortKey.DateModified) m.dateModified else m.dateTaken }

    val groups = groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(stamp(it)), zone).truncate(grouping) }
    return buildList(size + groups.size) {
        for ((date, items) in groups) {
            add(GridItem.Header(date, date.kind(today, grouping), items.size))
            items.forEach { add(GridItem.Item(it)) }
        }
    }
}

private fun LocalDate.truncate(grouping: Grouping): LocalDate = when (grouping) {
    Grouping.Year -> withDayOfYear(1)
    Grouping.Month -> withDayOfMonth(1)
    Grouping.Day, Grouping.None -> this
}

private fun LocalDate.kind(today: LocalDate, grouping: Grouping): HeaderKind = when {
    grouping == Grouping.Year -> HeaderKind.Year
    grouping == Grouping.Month -> HeaderKind.Month
    this == today -> HeaderKind.Today
    this == today.minusDays(1) -> HeaderKind.Yesterday
    else -> HeaderKind.Day
}
