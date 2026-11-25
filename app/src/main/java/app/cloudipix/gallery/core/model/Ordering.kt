package app.cloudipix.gallery.core.model

import androidx.annotation.StringRes
import app.cloudipix.gallery.R
import kotlinx.serialization.Serializable

enum class SortKey(@StringRes val labelRes: Int) {
    DateTaken(R.string.sort_date_taken),
    DateModified(R.string.sort_modified),
    Name(R.string.sort_name),
    Size(R.string.sort_size),
    Type(R.string.sort_type),
    FavoriteAdded(R.string.sort_favorite_added),
    ;

    companion object {
        val general = entries.filterNot { it == FavoriteAdded }
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

    val types: List<Int>
        get() = when (this) {
            All -> MediaType.entries.map(MediaType::ordinal)
            Images -> listOf(MediaType.Image.ordinal, MediaType.Gif.ordinal)
            Videos -> listOf(MediaType.Video.ordinal)
        }
}

interface Sortable {
    val name: String
    val size: Long
    val dateTaken: Long
    val dateModified: Long
    val favoriteOrder: Long? get() = null

    val typeRank: Int
}

private fun <T : Sortable> SortKey.comparator(): Comparator<T> = when (this) {
    SortKey.DateTaken -> compareBy { it.dateTaken }
    SortKey.DateModified -> compareBy { it.dateModified }
    SortKey.Name -> compareBy { it.name.lowercase() }
    SortKey.Size -> compareBy { it.size }
    SortKey.Type -> compareBy({ it.typeRank }, { it.name.lowercase() })
    SortKey.FavoriteAdded -> compareBy { it.favoriteOrder }
}

fun <T : Sortable> List<T>.sortedBy(sorting: Sorting): List<T> {
    val ordered = sortedWith(sorting.key.comparator<T>())
    return if (sorting.descending) ordered.asReversed() else ordered
}

internal fun <T : Sortable> List<T>.firstInSortOrder(sorting: Sorting): T? {
    val order = sorting.key.comparator<T>()
    // 내림차순은 오름차순 목록을 뒤집으므로 값이 같으면 마지막 항목을 고른다.
    return if (sorting.descending) {
        asReversed().maxWithOrNull(order)
    } else {
        minWithOrNull(order)
    }
}
