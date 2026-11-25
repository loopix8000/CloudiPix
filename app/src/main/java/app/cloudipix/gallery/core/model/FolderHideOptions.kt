package app.cloudipix.gallery.core.model

internal fun String.hasHiddenFolderSegment(): Boolean = splitToSequence('/').any { it.startsWith('.') }

internal data class FolderHideLocation(val bucketId: Long, val name: String, val path: String)

internal data class FolderHideOption(
    val path: String,
    val folders: Map<Long, String>,
    val source: String? = null,
    val localRoot: String? = null,
) {
    val title: String get() = path.substringAfterLast('/').ifEmpty { source ?: "/" }
    val parentPath: String get() = path.substringBeforeLast('/', "")
    val context: String? get() = listOfNotNull(
        source?.takeIf { path.isNotEmpty() },
        parentPath.takeIf { it.isNotEmpty() }?.withLeadingSlash(),
    ).joinToString(" · ").ifEmpty { null }
}

internal fun ancestorHideOptions(
    folders: List<FolderHideLocation>,
    selected: Set<Long>,
    root: String,
    includeRoot: Boolean = false,
    source: String? = null,
    displayPath: (String) -> String = { it },
): List<FolderHideOption> {
    val parents = folders.asSequence()
        .filter { it.bucketId in selected && it.path != root && it.path.startsWith("$root/") }
        .flatMap { folder ->
            generateSequence(folder.path) { path ->
                if (path == root) null else path.substringBeforeLast('/')
            }.drop(1).takeWhile { includeRoot || it != root }
        }
        .distinct()
    return parents.map { parent ->
        val matches = folders.asSequence()
            .filter { it.path == parent || it.path.startsWith("$parent/") }
            .associate { it.bucketId to it.name }
        val relativePath = parent.removePrefix(root).trimStart('/')
        FolderHideOption(displayPath(relativePath), matches, source)
    }.toList()
}

internal fun List<FolderHideOption>.toggleHideScope(checked: Set<Int>, index: Int): Set<Int> {
    if (index in checked) return checked - index
    val scope = this[index].folders.keys
    // 이미 선택한 범위가 새 범위를 포함하거나 그 안에 있으면 새 선택으로 바꾼다.
    return checked.filterTo(mutableSetOf(index)) { other ->
        val prior = this[other].folders.keys
        !scope.containsAll(prior) && !prior.containsAll(scope)
    }
}
