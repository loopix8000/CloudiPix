package app.cloudipix.gallery.core.security

import android.content.Context
import app.cloudipix.gallery.core.model.RECENT_BUCKET_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LockSettings(val app: Boolean, val folders: Set<Long>, val hideRecentsPreview: Boolean)

@Singleton
class GalleryLocks @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("gallery_locks", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(
        LockSettings(
            app = preferences.getBoolean("app", false),
            folders = preferences.getStringSet("folders", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet(),
            hideRecentsPreview = preferences.getBoolean(HIDE_RECENTS_PREVIEW, false),
        ),
    )
    val settings = current.asStateFlow()

    fun setAppLock(locked: Boolean) {
        preferences.edit().putBoolean("app", locked).apply()
        current.value = current.value.copy(app = locked)
    }

    fun setHideRecentsPreview(hidden: Boolean) {
        preferences.edit().putBoolean(HIDE_RECENTS_PREVIEW, hidden).apply()
        current.value = current.value.copy(hideRecentsPreview = hidden)
    }

    fun setFolderLock(bucketId: Long, locked: Boolean) {
        require(bucketId != RECENT_BUCKET_ID)
        val folders = current.value.folders.let { if (locked) it + bucketId else it - bucketId }
        preferences.edit().putStringSet("folders", folders.mapTo(mutableSetOf(), Long::toString)).apply()
        current.value = current.value.copy(folders = folders)
    }
}

private const val HIDE_RECENTS_PREVIEW = "hide_recents_preview"
