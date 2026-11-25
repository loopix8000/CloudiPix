package app.cloudipix.gallery.core.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.cloudipix.gallery.core.model.LocalStoragePath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 정보 시트에 보여 줄 파일 정보와 EXIF 값입니다. 문자열 값은 화면에 그대로 표시할 형태입니다. */
data class MediaDetails(
    val dimensions: String? = null,
    val camera: String? = null,
    val exposure: String? = null,
    val path: String? = null,
    val localPath: LocalStoragePath? = null,
    val lens: String? = null,
    val focalLength: String? = null,
    val location: String? = null,
    val description: String = "",
    val author: String = "",
    val editable: Boolean = false,
    val taken: Long? = null,
)

private val STORE_COLUMNS = arrayOf(
    MediaStore.Files.FileColumns.WIDTH,
    MediaStore.Files.FileColumns.HEIGHT,
    MediaStore.Files.FileColumns.RELATIVE_PATH,
    MediaStore.Files.FileColumns.MIME_TYPE,
    MediaStore.MediaColumns.VOLUME_NAME,
)

private fun dimensionsLabel(width: Int, height: Int): String = "$width × $height"

@Singleton
class MediaDetailsLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** MediaStore 항목의 정보입니다. 크기·경로·편집 가능 여부는 MediaStore에서, 나머지는 EXIF에서 읽습니다. */
    suspend fun load(uri: Uri, name: String): MediaDetails = withContext(Dispatchers.IO) {
        val fromStore = context.contentResolver.query(uri, STORE_COLUMNS, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val width = cursor.getInt(0)
            val height = cursor.getInt(1)
            MediaDetails(
                dimensions = if (width > 0 && height > 0) dimensionsLabel(width, height) else null,
                localPath = cursor.getString(2)?.let { folder -> storagePath(folder, name, cursor.getString(4)) },
                editable = cursor.getString(3) in EDITABLE_MIME_TYPES,
            )
        }
        withExif(uri, fromStore ?: MediaDetails())
    }

    /**
     * MediaStore 밖의 원본(원격 파일의 로컬 사본 등)에서 정보를 읽습니다.
     * 크기와 형식은 이미지 헤더만 읽어 확인하고, 형식을 알 수 없으면 [mime]을 씁니다.
     */
    suspend fun fromOriginal(uri: Uri, initial: MediaDetails, mime: String): MediaDetails = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sized = initial.copy(
            dimensions = if (bounds.outWidth > 0) dimensionsLabel(bounds.outWidth, bounds.outHeight) else null,
            editable = (bounds.outMimeType ?: mime) in EDITABLE_MIME_TYPES,
        )
        withExif(uri, sized)
    }

    /** EXIF 값을 덧붙입니다. EXIF를 읽지 못하면 받은 값을 그대로 돌려줍니다. */
    private fun withExif(uri: Uri, base: MediaDetails): MediaDetails = try {
        context.contentResolver.openInputStream(uri)?.use { input -> base.withExif(ExifInterface(input)) } ?: base
    } catch (_: Exception) {
        base
    }
}

private fun storagePath(relativeFolder: String, name: String, volume: String?): LocalStoragePath {
    val folder = relativeFolder.trim('/')
    return LocalStoragePath.relative(if (folder.isEmpty()) name else "$folder/$name", volume)
}

private fun MediaDetails.withExif(exif: ExifInterface): MediaDetails {
    fun text(tag: String): String? = exif.getAttribute(tag)
    val make = text(ExifInterface.TAG_MAKE)
    val model = text(ExifInterface.TAG_MODEL)
    return copy(
        taken = exif.captureTime(),
        camera = listOfNotNull(make, model).joinToString(" ").takeIf { it.isNotBlank() },
        exposure = exif.exposureSummary(),
        lens = text(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
        focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            .takeIf { it > 0 }
            ?.let { "${it.roundToInt()} mm" },
        location = exif.latLong?.let { (latitude, longitude) -> String.format(Locale.US, "%.5f, %.5f", latitude, longitude) },
        description = text(ExifInterface.TAG_IMAGE_DESCRIPTION)?.trim().orEmpty(),
        author = text(ExifInterface.TAG_ARTIST)?.trim().orEmpty(),
    )
}

/** 조리개·셔터 속도·ISO를 `ƒ/1.8 · 1/120 · ISO 50` 형태로 묶습니다. */
private fun ExifInterface.exposureSummary(): String? {
    val parts = listOfNotNull(
        getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "ƒ/$it" },
        getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toFloatOrNull()?.let(::shutterLabel),
        getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
    )
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

/**
 * 1초 이상은 `2.0s`처럼, 1초 미만은 `1/60`처럼 씁니다. 분모는 반올림합니다.
 * EXIF 노출 시간은 1/30 같은 분수를 소수로 바꾼 값이라 역수를 버림하면 1/29처럼 하나 작게 나올 수 있습니다.
 * 0 이하나 숫자가 아닌 값은 표시하지 않습니다.
 */
private fun shutterLabel(seconds: Float): String? = when {
    !seconds.isFinite() || seconds <= 0f -> null
    seconds >= 1f -> "${seconds}s"
    else -> "1/${(1f / seconds).roundToInt()}"
}
