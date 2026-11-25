package app.cloudipix.gallery.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 날짜 머리글을 어떤 문구로 보여 줄지 나타냅니다. */
enum class HeaderKind { Today, Yesterday, Day, Month, Year }

/** 미디어 격자에 놓이는 한 칸입니다. 머리글은 한 줄 전체를 차지합니다. */
sealed interface GridItem {
    /** [date]는 묶음의 시작일입니다. 월 묶음은 1일, 연 묶음은 1월 1일입니다. */
    data class Header(val date: LocalDate, val kind: HeaderKind, val count: Int) : GridItem
    data class Item(val medium: Medium) : GridItem
}

/**
 * 정렬된 목록을 날짜 묶음으로 나누고 묶음마다 머리글을 앞에 붙입니다.
 *
 * 수정일로 정렬하면 수정일을, 그 밖의 정렬에서는 촬영일을 기준으로 묶습니다. 이름이나 크기로
 * 정렬해 같은 날짜가 떨어져 있어도 한 묶음으로 모으고, 묶음은 처음 나타난 순서를 따릅니다.
 * 머리글의 날짜는 격자 키로 쓰이므로 목록 안에서 겹치지 않아야 합니다.
 */
fun List<Medium>.grouped(grouping: Grouping, sorting: Sorting): List<GridItem> {
    if (grouping == Grouping.None) return map { GridItem.Item(it) }

    val zone = ZoneId.systemDefault()
    val byModified = sorting.key == SortKey.DateModified
    val buckets = LinkedHashMap<LocalDate, MutableList<Medium>>()
    for (medium in this) {
        val millis = if (byModified) medium.dateModified else medium.dateTaken
        val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        buckets.getOrPut(grouping.bucketStart(day)) { ArrayList() }.add(medium)
    }

    val today = LocalDate.now(zone)
    val result = ArrayList<GridItem>(size + buckets.size)
    for ((start, members) in buckets) {
        result.add(GridItem.Header(start, grouping.headerKind(start, today), members.size))
        members.mapTo(result) { GridItem.Item(it) }
    }
    return result
}

private fun Grouping.bucketStart(day: LocalDate): LocalDate = when (this) {
    Grouping.Year -> LocalDate.of(day.year, 1, 1)
    Grouping.Month -> LocalDate.of(day.year, day.month, 1)
    Grouping.Day, Grouping.None -> day
}

private fun Grouping.headerKind(start: LocalDate, today: LocalDate): HeaderKind = when (this) {
    Grouping.Year -> HeaderKind.Year
    Grouping.Month -> HeaderKind.Month
    Grouping.Day, Grouping.None -> when (start) {
        today -> HeaderKind.Today
        today.minusDays(1) -> HeaderKind.Yesterday
        else -> HeaderKind.Day
    }
}
