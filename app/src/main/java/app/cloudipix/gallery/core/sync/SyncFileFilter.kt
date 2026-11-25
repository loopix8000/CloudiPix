package app.cloudipix.gallery.core.sync

import android.webkit.MimeTypeMap
import app.cloudipix.gallery.core.remote.PHOTO_EXTENSIONS
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
internal data class SyncFileFilter(
    val mediaOnly: Boolean = false,
    val includeExtensions: Set<String> = emptySet(),
    val excludeExtensions: Set<String> = emptySet(),
) {
    val isUnrestricted: Boolean get() = !mediaOnly && includeExtensions.isEmpty() && excludeExtensions.isEmpty()

    fun allows(name: String, localMime: String?, remoteMime: String?): Boolean {
        if (isUnrestricted) return true
        val lower = name.lowercase(Locale.ROOT)
        if (excludeExtensions.any { lower.endsWith(".$it") }) return false
        if (includeExtensions.isNotEmpty() && includeExtensions.none { lower.endsWith(".$it") }) return false
        if (!mediaOnly || localMime.isPhotoOrVideo() || remoteMime.isPhotoOrVideo()) return true
        val extension = lower.substringAfterLast('.', "")
        return extension in PHOTO_EXTENSIONS || extension in commonVideoExtensions || extension == "gif" ||
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).isPhotoOrVideo()
    }
}

@Serializable
internal data class SyncFolderFileFilter(val serverId: String, val path: String, val filter: SyncFileFilter)

@Serializable
internal data class SyncFileFilterSettings(
    val global: SyncFileFilter = SyncFileFilter(),
    val folders: List<SyncFolderFileFilter> = emptyList(),
) {
    fun overridesFor(serverId: String): Map<String, SyncFileFilter> = folders.asSequence()
        .filter { it.serverId == serverId }.associate { it.path to it.filter }

    fun withFolderFilter(serverId: String, path: String, filter: SyncFileFilter?): SyncFileFilterSettings {
        val existing = folders.firstOrNull { it.serverId == serverId && it.path == path }
        if (existing?.filter == filter) return this
        val remaining = folders.filterNot { it.serverId == serverId && it.path == path }
        return copy(folders = if (filter == null) remaining else remaining + SyncFolderFileFilter(serverId, path, filter))
    }
}

internal fun Map<String, SyncFileFilter>.filterForFolder(path: String, fallback: SyncFileFilter): SyncFileFilter =
    generateSequence(path.trimEnd('/')) { current ->
        current.takeIf { it.isNotEmpty() }?.substringBeforeLast('/', "")
    }.firstNotNullOfOrNull { this[it] } ?: fallback

internal fun parseSyncExtensions(text: String): Set<String>? {
    val extensions = text.split(',', ';', ' ', '\n', '\r', '\t').filter(String::isNotBlank).map {
        it.removePrefix("*.").removePrefix(".").lowercase(Locale.ROOT)
    }
    return extensions.toSet().takeIf { values -> values.all(extensionPattern::matches) }
}

private fun String?.isPhotoOrVideo(): Boolean = this != null &&
    (startsWith("image/", ignoreCase = true) || startsWith("video/", ignoreCase = true))

private val extensionPattern = Regex("[a-z0-9]+(?:\\.[a-z0-9]+)*")
private val commonVideoExtensions = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm", "avi", "mpeg", "mpg", "m2ts", "mts", "wmv", "flv", "ogv")
