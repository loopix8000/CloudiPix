package app.cloudipix.gallery.feature.security

import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.security.GalleryLocks

private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

class GalleryAccess internal constructor(
    private val activity: ComponentActivity,
    private val locks: GalleryLocks,
    private val session: GalleryUnlockSession,
) {
    val settings = locks.settings
    val appUnlocked: Boolean get() = session.appUnlocked
    private var pending: CancellationSignal? = null

    fun hasFolderAccess(bucketId: Long?): Boolean = session.hasFolderAccess(bucketId)

    fun canAuthenticate(): Boolean = activity.getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun unlockApp() = authenticate(R.string.unlock_app) {}

    fun unlockFolder(bucketId: Long?) = authenticate(R.string.unlock_folder) {
        session.grantFolderAccess(bucketId)
    }

    fun changeAppLock(enabled: Boolean) = authenticate(R.string.change_app_lock) {
        locks.setAppLock(enabled)
    }

    fun setHideRecentsPreview(hidden: Boolean) = locks.setHideRecentsPreview(hidden)

    fun changeFolderLock(bucketId: Long, enabled: Boolean) = authenticate(R.string.change_folder_lock) {
        locks.setFolderLock(bucketId, enabled)
        session.revokeFolderAccess(bucketId)
    }

    fun openDeviceSecurity() {
        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    fun relock() {
        session.relock()
        dispose()
    }

    fun dispose() {
        val request = pending
        pending = null
        request?.cancel()
    }

    private fun authenticate(@StringRes title: Int, onVerified: () -> Unit) {
        if (pending != null || activity.isFinishing) return
        if (!canAuthenticate()) {
            Toast.makeText(activity, R.string.device_lock_required, Toast.LENGTH_LONG).show()
            return
        }
        val request = CancellationSignal()
        pending = request
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (pending !== request) return
                pending = null
                if (activity.isFinishing || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                session.grantAppAccess()
                onVerified()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (pending === request) pending = null
            }
        }
        BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(title))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
            .authenticate(request, activity.mainExecutor, callback)
    }
}
