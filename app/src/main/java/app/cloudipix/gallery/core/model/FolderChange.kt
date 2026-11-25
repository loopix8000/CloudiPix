package app.cloudipix.gallery.core.model

internal fun validFolderName(name: String): Boolean =
    name.isNotBlank() && !name.startsWith('.') && !name.endsWith('.') &&
        name.encodeToByteArray().size <= 255 && name.none { it.isISOControl() || it in "/\\:*?\"<>|" }

internal data class RelocatedFolder(val oldId: Long, val newId: Long, val name: String)

internal data class FolderChange(
    val name: String,
    val folders: List<RelocatedFolder>,
    val photos: Map<Long, Long>,
    val favorites: Map<String, String>,
)
