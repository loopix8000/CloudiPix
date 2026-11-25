package app.cloudipix.gallery.core.media

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import app.cloudipix.gallery.core.model.LocalStoragePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

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

private val COLUMNS = arrayOf(
    MediaStore.Files.FileColumns.WIDTH,
    MediaStore.Files.FileColumns.HEIGHT,
    MediaStore.Files.FileColumns.RELATIVE_PATH,
    MediaStore.Files.FileColumns.MIME_TYPE,
    MediaStore.MediaColumns.VOLUME_NAME,
)

@Singleton
class MediaDetailsLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun load(uri: Uri, name: String): MediaDetails = withContext(Dispatchers.IO) {
        var details = MediaDetails()

        context.contentResolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val width = c.getInt(0)
                val height = c.getInt(1)
                details = details.copy(
                    dimensions = if (width > 0 && height > 0) "$width × $height" else null,
                    localPath = c.getString(2)?.let { relativePath ->
                        val folder = relativePath.trim('/')
                        val path = if (folder.isEmpty()) name else "$folder/$name"
                        LocalStoragePath.relative(path, c.getString(4))
                    },
                    editable = c.getString(3) in EDITABLE_MIME_TYPES,
                )
            }
        }

        readExif(uri, details)
    }

    suspend fun fromOriginal(uri: Uri, initial: MediaDetails, mime: String): MediaDetails = withContext(Dispatchers.IO) {
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, dimensions) }
        readExif(uri, initial.copy(
            dimensions = if (dimensions.outWidth > 0) "${dimensions.outWidth} × ${dimensions.outHeight}" else null,
            editable = (dimensions.outMimeType ?: mime) in EDITABLE_MIME_TYPES,
        ))
    }

    private fun readExif(uri: Uri, initial: MediaDetails): MediaDetails {
        var details = initial
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                details = details.copy(
                    taken = exif.captureTime(),
                    camera = listOfNotNull(
                        exif.getAttribute(ExifInterface.TAG_MAKE),
                        exif.getAttribute(ExifInterface.TAG_MODEL),
                    ).joinToString(" ").ifBlank { null },
                    exposure = exif.exposureSummary(),
                    lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifEmpty { null },
                    focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                        .takeIf { it > 0 }?.let { "${it.roundToInt()} mm" },
                    location = exif.latLong?.let { (lat, lon) -> String.format(Locale.US, "%.5f, %.5f", lat, lon) },
                    description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.trim().orEmpty(),
                    author = exif.getAttribute(ExifInterface.TAG_ARTIST)?.trim().orEmpty(),
                )
            }
        }
        return details
    }
}

private fun ExifInterface.exposureSummary(): String? {
    val aperture = getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "ƒ/$it" }
    val shutter = getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
        ?.toFloatOrNull()
        ?.let { if (it >= 1f) "${it}s" else "1/${(1 / it).toInt()}" }
    val iso = getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" }
    return listOfNotNull(aperture, shutter, iso).joinToString(" · ").ifBlank { null }
}
