package app.cloudipix.gallery.core.model

import kotlinx.serialization.Serializable

@Serializable
data class FolderTab(
    val id: String,
    val name: String,
    val folderIds: Set<Long> = emptySet(),
) {
    companion object {
        val Home = FolderTab("home", "")
    }
}

internal fun Set<Long>.containsFolder(bucketId: Long, localBucketId: Long?): Boolean =
    bucketId in this || localBucketId?.let(this::contains) == true
