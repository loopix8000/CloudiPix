package app.cloudipix.gallery.core.model

import androidx.annotation.StringRes
import app.cloudipix.gallery.R
import kotlinx.serialization.Serializable

// 아래 enum의 이름은 설정과 백업에 저장되고, 선언 순서는 설정 화면의 선택지 순서입니다.

enum class SortKey(@StringRes val labelRes: Int) {
    DateTaken(R.string.sort_date_taken),
    DateModified(R.string.sort_modified),
    Name(R.string.sort_name),
    Size(R.string.sort_size),
    Type(R.string.sort_type),
    FavoriteAdded(R.string.sort_favorite_added),
    ;

    companion object {
        /** 즐겨찾기 화면 밖에서 고를 수 있는 정렬 기준입니다. */
        val general: List<SortKey> = entries.filter { it != FavoriteAdded }
    }
}

@Serializable
data class Sorting(val key: SortKey = SortKey.DateTaken, val descending: Boolean = true)

enum class Grouping(@StringRes val labelRes: Int) {
    None(R.string.group_none),
    Day(R.string.group_day),
    Month(R.string.group_month),
    Year(R.string.group_year),
}

enum class ScrollDirection(@StringRes val labelRes: Int) {
    Down(R.string.scroll_down),
    Up(R.string.scroll_up),
}

enum class MediaFilter(@StringRes val labelRes: Int) {
    All(R.string.filter_all),
    Images(R.string.filter_images),
    Videos(R.string.filter_videos),
    ;

    /** 데이터베이스에 저장된 [MediaType] 순번 중 이 필터에 해당하는 값입니다. */
    val types: List<Int> by lazy {
        val included = when (this) {
            All -> MediaType.entries
            Images -> listOf(MediaType.Image, MediaType.Gif)
            Videos -> listOf(MediaType.Video)
        }
        included.map { it.ordinal }
    }
}

/** 미디어와 폴더를 같은 기준으로 정렬하기 위한 공통 속성입니다. */
interface Sortable {
    val name: String
    val size: Long
    val dateTaken: Long
    val dateModified: Long
    val favoriteOrder: Long? get() = null

    val typeRank: Int
}

/** 오름차순 비교 기준입니다. 이름은 대소문자를 구분하지 않고, 종류가 같으면 이름순입니다. */
private fun <T : Sortable> SortKey.ascending(): Comparator<T> = when (this) {
    SortKey.DateTaken -> compareBy { it.dateTaken }
    SortKey.DateModified -> compareBy { it.dateModified }
    SortKey.Size -> compareBy { it.size }
    SortKey.FavoriteAdded -> compareBy { it.favoriteOrder }
    SortKey.Name -> compareBy { it.name.lowercase() }
    SortKey.Type -> compareBy<T> { it.typeRank }.thenBy { it.name.lowercase() }
}

/**
 * 오름차순으로 안정 정렬한 뒤, 내림차순이면 결과를 뒤집어 보여 줍니다.
 * 그래서 내림차순에서는 기준 값이 같은 항목끼리 원래 순서의 반대로 놓입니다.
 */
fun <T : Sortable> List<T>.sortedBy(sorting: Sorting): List<T> {
    val ascending = when (sorting.key) {
        SortKey.Name, SortKey.Type -> sortedByLowercaseName(byType = sorting.key == SortKey.Type)
        else -> sortedWith(sorting.key.ascending())
    }
    return if (sorting.descending) ascending.asReversed() else ascending
}

/**
 * 이름 비교마다 소문자 문자열을 새로 만들지 않도록 항목마다 한 번만 변환해 정렬합니다.
 * 비교 결과와 안정 정렬은 [SortKey.ascending]과 같습니다.
 */
private fun <T : Sortable> List<T>.sortedByLowercaseName(byType: Boolean): List<T> {
    class Keyed(val item: T, val name: String)

    val order: Comparator<Keyed> = if (byType) {
        compareBy<Keyed> { it.item.typeRank }.thenBy { it.name }
    } else {
        compareBy { it.name }
    }
    return map { Keyed(it, it.name.lowercase()) }.sortedWith(order).map { it.item }
}

/** 사용자가 직접 정한 폴더 순서입니다. 로컬 버킷 위치가 있으면 그것을, 없으면 이 폴더의 위치를 씁니다. */
internal fun Folder.manualSortPosition(positions: Map<Long, Int>): Int? =
    localBucketId?.let { positions[it] } ?: positions[bucketId]

/**
 * [sortedBy]로 정렬했을 때 맨 앞에 올 항목을 정렬 없이 찾습니다.
 * 기준 값이 같으면 오름차순은 처음 항목을, 내림차순은 마지막 항목을 고릅니다.
 */
internal fun <T : Sortable> List<T>.firstInSortOrder(sorting: Sorting): T? {
    val order = sorting.key.ascending<T>()
    var pick: T? = null
    for (item in this) {
        val current = pick
        val replace = when {
            current == null -> true
            sorting.descending -> order.compare(item, current) >= 0
            else -> order.compare(item, current) < 0
        }
        if (replace) pick = item
    }
    return pick
}
