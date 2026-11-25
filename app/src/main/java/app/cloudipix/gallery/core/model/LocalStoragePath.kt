package app.cloudipix.gallery.core.model

import android.provider.MediaStore

@ConsistentCopyVisibility
data class LocalStoragePath private constructor(val path: String, val volumeName: String?) {
    val isRemovable: Boolean get() = volumeName != null && volumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY

    fun label(internalStorage: String, removableStorage: String): String = when {
        path.isEmpty() -> if (isRemovable) removableStorage else internalStorage
        isRemovable -> "$removableStorage · ${path.withLeadingSlash()}"
        else -> path.withLeadingSlash()
    }

    companion object {
        fun relative(path: String, volumeName: String?) = LocalStoragePath(
            path.trim('/'), volumeName?.takeUnless { it.isEmpty() || it == MediaStore.VOLUME_EXTERNAL },
        )

        fun absolute(path: String): LocalStoragePath {
            val clean = path.trimEnd('/')
            primaryRoot.find(clean)?.let { root ->
                return relative(clean.substring(root.range.last + 1), MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            removableRoot.find(clean)?.let { root ->
                return relative(clean.substring(root.range.last + 1), root.groupValues[1])
            }
            return LocalStoragePath(clean, null)
        }
    }
}

internal fun String.withLeadingSlash(): String = if (startsWith('/')) this else "/$this"

private val primaryRoot = Regex("^/(?:storage/emulated/[0-9]+|storage/self/primary|sdcard)(?=/|$)")
private val removableRoot = Regex(
    "^/(?:storage|mnt/media_rw)/([0-9a-f]{4}-[0-9a-f]{4}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})(?=/|$)",
    RegexOption.IGNORE_CASE,
)
