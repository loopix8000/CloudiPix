package app.cloudipix.gallery.feature.security

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

internal class GalleryUnlockSession : ViewModel() {
    // 같은 갤러리에서 연 창끼리만 공유한다. 프로세스가 종료되면 잠금 해제 상태도 사라진다.
    companion object {
        private val sessions = mutableMapOf<String, GalleryUnlockSession>()

        fun attachWindow(key: String, fallback: GalleryUnlockSession): GalleryUnlockSession =
            sessions.getOrPut(key) { fallback }.also { it.attachedWindows++ }
    }

    private var attachedWindows = 0
    private var startedWindows = 0

    fun windowStarted() { startedWindows++ }

    fun windowStopped(changingConfiguration: Boolean) {
        startedWindows--
        if (startedWindows == 0 && !changingConfiguration) relock()
    }

    fun detachWindow(key: String, changingConfiguration: Boolean) {
        attachedWindows--
        if (attachedWindows == 0 && !changingConfiguration) sessions.remove(key)
    }

    var appUnlocked by mutableStateOf(false)
        private set
    private var unlockedFolders by mutableStateOf(emptySet<Long?>())

    fun hasFolderAccess(bucketId: Long?): Boolean = bucketId in unlockedFolders

    fun grantAppAccess() {
        appUnlocked = true
    }

    fun grantFolderAccess(bucketId: Long?) {
        unlockedFolders += bucketId
    }

    fun revokeFolderAccess(bucketId: Long) {
        unlockedFolders = unlockedFolders - bucketId - null
    }

    fun relock() {
        appUnlocked = false
        unlockedFolders = emptySet()
    }
}
