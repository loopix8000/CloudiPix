package app.cloudipix.gallery.core.model

import androidx.annotation.StringRes
import app.cloudipix.gallery.R
import kotlinx.serialization.Serializable

/** 하단 동작 바와 더보기 메뉴에 놓을 수 있는 동작입니다. 이름은 설정과 백업에 저장됩니다. */
enum class BarAction(@StringRes val labelRes: Int) {
    Share(R.string.action_share),
    Favorite(R.string.action_favorite),
    Edit(R.string.action_edit),
    SetAs(R.string.action_set_as),
    Copy(R.string.action_copy_to),
    Move(R.string.action_move_to),
    Rename(R.string.action_rename),
    CopyPath(R.string.action_copy_path),
    Metadata(R.string.action_metadata),
    Delete(R.string.action_delete),
    ;

    companion object {
        private val byName = entries.associateBy { it.name }

        fun named(name: String): BarAction? = byName[name]
    }
}

/**
 * 동작 바 배치입니다. [bar]는 바에 직접 놓인 동작, [menu]는 더보기 메뉴의 동작이며,
 * [hidden]은 사용자가 숨긴 동작입니다. 숨긴 동작도 순서를 기억하도록 [bar]나 [menu]에 남겨 둡니다.
 */
@Serializable
data class BarLayout(
    val bar: List<BarAction>,
    val menu: List<BarAction>,
    val hidden: Set<BarAction> = emptySet(),
) {

    val all: List<BarAction> get() = bar + menu

    /** `바|메뉴|숨김` 형태로 저장합니다. 각 구역은 동작 이름을 쉼표로 잇습니다. */
    fun encode(): String = buildString {
        appendNames(bar)
        append('|')
        appendNames(menu)
        append('|')
        appendNames(hidden)
    }

    fun shown(action: BarAction, on: Boolean): BarLayout =
        copy(hidden = if (on) hidden - action else hidden + action)

    /** 숨기지 않았고 지금 쓸 수 있는 동작만 남깁니다. 결과에는 숨김 목록이 없습니다. */
    fun only(available: (BarAction) -> Boolean): BarLayout {
        fun visible(action: BarAction) = action !in hidden && available(action)
        return BarLayout(bar.filter(::visible), menu.filter(::visible))
    }

    /**
     * 바에 [slots]칸만 있을 때의 배치입니다. 넘치는 바 동작은 순서대로 메뉴 앞에 붙입니다.
     * [reserveOverflowSlot]이면 메뉴가 필요할 때 마지막 칸을 더보기 버튼에 씁니다.
     */
    fun fitting(slots: Int, reserveOverflowSlot: Boolean = true): BarLayout {
        val room = maxOf(slots, 1)
        val overflowButton = reserveOverflowSlot && (menu.isNotEmpty() || bar.size > room)
        val barRoom = if (overflowButton) room - 1 else room
        if (bar.size <= barRoom) return this
        return BarLayout(bar.subList(0, barRoom).toList(), bar.subList(barRoom, bar.size) + menu, hidden)
    }

    companion object {
        val Selection = BarLayout(
            bar = listOf(BarAction.Share, BarAction.Favorite, BarAction.Copy, BarAction.Delete),
            menu = listOf(BarAction.Move, BarAction.Rename, BarAction.CopyPath),
            hidden = setOf(BarAction.CopyPath),
        )
        val Viewer = BarLayout(
            bar = listOf(BarAction.Share, BarAction.Favorite, BarAction.Edit, BarAction.SetAs, BarAction.Delete),
            menu = listOf(BarAction.Copy, BarAction.Move, BarAction.Rename, BarAction.Metadata),
        )

        /**
         * 저장된 배치를 읽습니다. 저장 값이 없으면 [fallback]을 씁니다.
         *
         * - [fallback]에 없는 동작과 알 수 없는 이름은 버리고, 중복은 처음 위치만 남깁니다.
         * - 저장 값에 빠진 동작은 [newBarActions]에 있으면 바 끝에, 아니면 메뉴 끝에 [fallback] 순서대로 붙입니다.
         * - 빠진 동작이 [fallback]에서 숨김이었다면 숨김으로 둡니다.
         */
        fun decode(raw: String?, fallback: BarLayout, newBarActions: Set<BarAction> = emptySet()): BarLayout {
            if (raw == null) return fallback
            val supported = fallback.all.toSet()
            val sections = raw.split('|')
            fun section(index: Int): List<BarAction> {
                val text = sections.getOrNull(index) ?: return emptyList()
                return text.split(',').mapNotNullTo(LinkedHashSet()) { name ->
                    BarAction.named(name)?.takeIf { it in supported }
                }.toList()
            }

            val bar = section(0).toMutableList()
            val menu = section(1).filterTo(ArrayList()) { it !in bar }
            val hidden = section(2).toMutableSet()
            val placed = (bar + menu).toSet()
            for (action in fallback.all) {
                if (action in placed) continue
                if (action in newBarActions) bar += action else menu += action
                if (action in fallback.hidden) hidden += action
            }
            return BarLayout(bar, menu, hidden)
        }

        private fun StringBuilder.appendNames(actions: Collection<BarAction>) {
            actions.forEachIndexed { index, action ->
                if (index > 0) append(',')
                append(action.name)
            }
        }
    }
}
