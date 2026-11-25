package app.cloudipix.gallery.core.model

/**
 * 경로 목록을 POSIX 셸에 그대로 붙여 넣을 수 있는 한 줄로 만듭니다.
 * 각 경로는 작은따옴표로 감싸고, 경로 안의 작은따옴표는 따옴표를 닫고 이스케이프한 뒤 다시 엽니다.
 */
fun shellQuoted(paths: List<String>): String = buildString {
    for ((index, path) in paths.withIndex()) {
        if (index > 0) append(' ')
        appendSingleQuoted(path)
    }
}

private fun StringBuilder.appendSingleQuoted(text: String) {
    append('\'')
    for (ch in text) {
        if (ch == '\'') append("'\\''") else append(ch)
    }
    append('\'')
}
