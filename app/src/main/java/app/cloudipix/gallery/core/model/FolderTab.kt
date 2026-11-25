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
