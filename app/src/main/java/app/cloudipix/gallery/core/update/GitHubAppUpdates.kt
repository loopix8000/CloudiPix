package app.cloudipix.gallery.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import app.cloudipix.gallery.BuildConfig
import app.cloudipix.gallery.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class UpdateFileProvider : FileProvider()

internal data class AppUpdate(val versionName: String, val downloadUrl: HttpUrl, val size: Long)

internal class AppUpdateException(val messageRes: Int) : IOException()

internal object GitHubAppUpdates {
    private val client = OkHttpClient.Builder().followSslRedirects(false).build()
    private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    suspend fun check(installedVersion: String): AppUpdate? {
        val repository = BuildConfig.UPDATE_REPOSITORY
        if (repository.isEmpty()) throw AppUpdateException(R.string.update_source_missing)

        val latestUrl = "https://api.github.com/repos/$repository/releases/latest".toHttpUrl()
        return request(latestUrl, "application/vnd.github+json") { response ->
            if (response.code == 404) throw AppUpdateException(R.string.update_release_missing)
            if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
            val release = JSONObject(response.body?.string() ?: throw IOException("Missing release response"))
            val tag = release.getString("tag_name")
            val versionName = tag.removePrefix("v")
            if (!isNewer(versionName, installedVersion)) return@request null

            val assets = release.getJSONArray("assets")
            val apks = (0 until assets.length()).map(assets::getJSONObject).filter { asset ->
                asset.optString("state") == "uploaded" && asset.optString("name").endsWith(".apk", ignoreCase = true)
            }
            val apk = apks.firstOrNull { it.optString("name") == "CloudiPix.apk" } ?: apks.singleOrNull()
            val filename = apk?.optString("name").orEmpty()
            val size = apk?.optLong("size") ?: 0L
            if (filename.isEmpty() || size <= 0) throw AppUpdateException(R.string.update_apk_missing)
            val downloadUrl = "https://github.com/$repository/releases/download/".toHttpUrl().newBuilder()
                .addPathSegment(tag)
                .addPathSegment(filename)
                .build()
            AppUpdate(versionName, downloadUrl, size)
        }
    }

    suspend fun download(context: Context, update: AppUpdate, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "apk-updates")
            directory.mkdirs()
            val partial = File.createTempFile("partial-", ".apk", directory)
            try {
                partial.outputStream().buffered().use { output ->
                    request(update.downloadUrl, "application/octet-stream") { response ->
                        if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
                        val input = response.body?.byteStream() ?: throw IOException("Missing APK response")
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var percent = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            downloaded += count
                            if (downloaded > update.size) throw IOException("Unexpected APK size")
                            output.write(buffer, 0, count)
                            val next = (downloaded.toDouble() / update.size * 100).toInt()
                            if (next != percent) {
                                percent = next
                                onProgress(percent)
                            }
                        }
                        if (downloaded != update.size) throw IOException("Incomplete APK")
                    }
                }
                ensureActive()
                verifyApk(context, update, partial)
                ensureActive()
                val destination = File(directory, "CloudiPix.apk")
                if (!partial.renameTo(destination)) throw IOException("Could not save APK")
                destination
            } finally {
                partial.delete()
            }
        }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        if (!versionPattern.matches(candidate) || !versionPattern.matches(current)) {
            throw AppUpdateException(R.string.update_version_invalid)
        }
        val installed = current.split('.').map { it.toBigInteger() }
        val available = candidate.split('.').map { it.toBigInteger() }
        val firstDifference = available.zip(installed).firstOrNull { (left, right) -> left != right }
        return firstDifference?.let { (left, right) -> left > right } == true
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, update: AppUpdate, file: File) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags)
        val installedSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val candidateSigners = candidate?.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        if (candidate == null || candidate.packageName != installed.packageName ||
            candidate.versionName != update.versionName || candidate.longVersionCode <= installed.longVersionCode ||
            installedSigners.isEmpty() || candidateSigners != installedSigners
        ) {
            throw AppUpdateException(R.string.update_apk_incompatible)
        }
    }

    private suspend fun <T> request(url: HttpUrl, accept: String, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("User-Agent", "CloudiPix")
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (continuation.isActive) continuation.resumeWith(runCatching { read(it) })
                    }
                }
            })
        }
}
