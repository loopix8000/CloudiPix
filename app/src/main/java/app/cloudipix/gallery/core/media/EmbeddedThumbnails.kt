package app.cloudipix.gallery.core.media

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.roundToInt

/** 내장 썸네일을 꺼내는 방법에 따라 나눈 사진 형식. */
internal enum class EmbeddedFormat { Jpeg, Heif, Raw }

/**
 * MIME 형식이나 파일 이름으로 내장 썸네일을 꺼낼 방법을 고른다. 내장 썸네일이 없는 형식이면 null.
 * 서버가 MIME 형식을 `application/octet-stream`처럼 알려 줄 수 있어 MIME으로 모르면 확장자를 본다.
 */
internal fun embeddedFormat(mimeType: String?, name: String?): EmbeddedFormat? =
    mimeType?.lowercase()?.let(MIME_FORMATS::get)
        ?: name?.substringAfterLast('.', "")?.lowercase()?.let(EXTENSION_FORMATS::get)

/** 파일의 [offset]부터 [length] 바이트까지 읽는다. 파일이 짧으면 있는 만큼만 돌려준다. */
internal fun interface RangeReader {
    @Throws(IOException::class)
    fun read(offset: Long, length: Int): ByteArray
}

/** 기기 파일을 필요한 위치만 읽는다. */
internal class FileRangeReader(private val channel: FileChannel) : RangeReader {
    override fun read(offset: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, position)
            if (count <= 0) break
            position += count
        }
        return if (buffer.hasRemaining()) buffer.array().copyOf(buffer.position()) else buffer.array()
    }
}

/**
 * [format] 사진에서 내장 썸네일을 꺼내 원본과 같은 방향과 비율로 맞춘다. 내장 썸네일이 없으면 null.
 * 기기 사진과 서버 사진이 같은 방법을 쓰고, 파일을 읽는 방법만 [reader]로 다르게 준다.
 * 썸네일보다 크게 늘리지는 않는다.
 */
internal fun readEmbeddedThumbnail(format: EmbeddedFormat, reader: RangeReader, target: ThumbnailTarget): Bitmap? =
    when (format) {
        EmbeddedFormat.Jpeg -> jpegThumbnail(reader.read(0, JPEG_HEAD_BYTES), target)
        EmbeddedFormat.Raw -> rawThumbnail(reader.read(0, RAW_HEAD_BYTES), target)
        EmbeddedFormat.Heif -> heifThumbnail(reader, target)
    }

/** 스트림을 [limit] 바이트까지 읽는다. 스트림이 더 짧으면 있는 만큼만 돌려준다. */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var count = 0
    while (count < limit) {
        val read = read(buffer, count, limit - count)
        if (read < 0) break
        count += read
    }
    return if (count == limit) buffer else buffer.copyOf(count)
}

/**
 * JPEG 앞부분에서 EXIF 썸네일을 꺼낸다. EXIF(APP1) 세그먼트는 최대 64KB이고 보통 파일 맨 앞에 있다.
 * 앞부분에 EXIF가 다 들어 있지 않으면 null을 돌려준다.
 */
private fun jpegThumbnail(head: ByteArray, target: ThumbnailTarget): Bitmap? {
    val jpeg = JpegHead.parse(head) ?: return null
    val segment = jpeg.exif ?: return null
    // EXIF 세그먼트만 담은 JPEG를 만들어 ExifInterface가 영상 데이터를 찾지 않게 한다.
    val exif = openExif(SOI + segment + EOI) ?: return null
    val width = jpeg.width.takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
    val height = jpeg.height.takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
    return exif.fittedThumbnail(width, height, target)
}

/**
 * RAW 파일 앞부분에서 TIFF 구조의 썸네일을 꺼낸다. 대부분의 카메라는 작은 썸네일을 앞쪽에 둔다.
 * 썸네일이 읽은 범위 밖에 있으면 null을 돌려준다.
 */
private fun rawThumbnail(head: ByteArray, target: ThumbnailTarget): Bitmap? {
    val exif = openExif(head) ?: return null
    val width = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
    val height = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
    return exif.fittedThumbnail(width, height, target)
}

private fun openExif(bytes: ByteArray): ExifInterface? = try {
    ExifInterface(ByteArrayInputStream(bytes))
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    null
}

/** EXIF 썸네일을 원본 비율로 자르고 방향을 맞춘 뒤 [target]에 맞춘다. 원본 크기를 모르면 자르지 않는다. */
private fun ExifInterface.fittedThumbnail(originalWidth: Int, originalHeight: Int, target: ThumbnailTarget): Bitmap? {
    val raw = try {
        thumbnailBitmap
    } catch (_: RuntimeException) {
        null
    } ?: return null
    if (maxOf(raw.width, raw.height) < MIN_EMBEDDED_EDGE) {
        raw.recycle()
        return null
    }
    return raw.cropToAspect(originalWidth, originalHeight)
        .oriented(rotationDegrees, isFlipped)
        .scaleAndRecycle(target.size, target.fill)
}

/**
 * 원본과 비율이 다른 썸네일을 원본 비율로 가운데를 잘라 낸다.
 * 16:9 사진의 썸네일을 4:3 안에 검은 띠를 넣어 저장하는 카메라가 있어 띠를 없앤다.
 */
private fun Bitmap.cropToAspect(originalWidth: Int, originalHeight: Int): Bitmap {
    if (originalWidth <= 0 || originalHeight <= 0) return this
    val original = originalWidth.toFloat() / originalHeight
    val thumbnail = width.toFloat() / height
    if (abs(thumbnail / original - 1f) < ASPECT_TOLERANCE) return this
    // 가로세로가 서로 반대이면 띠가 아니라 다르게 저장된 썸네일이므로 자르지 않는다.
    if ((thumbnail > 1f) != (original > 1f) && thumbnail != 1f && original != 1f) return this
    val cropped = if (thumbnail > original) {
        val croppedWidth = (height * original).roundToInt().coerceIn(1, width)
        Bitmap.createBitmap(this, (width - croppedWidth) / 2, 0, croppedWidth, height)
    } else {
        val croppedHeight = (width / original).roundToInt().coerceIn(1, height)
        Bitmap.createBitmap(this, 0, (height - croppedHeight) / 2, width, croppedHeight)
    }
    if (cropped !== this) recycle()
    return cropped
}

/** EXIF 방향값에 맞춰 돌리고 뒤집는다. 썸네일은 원본과 같은 방향으로 저장되어 있다. */
private fun Bitmap.oriented(degrees: Int, flipped: Boolean): Bitmap {
    if (degrees == 0 && !flipped) return this
    val matrix = Matrix().apply {
        setRotate(degrees.toFloat())
        if (flipped) postScale(-1f, 1f)
    }
    val turned = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (turned !== this) recycle()
    return turned
}

/** JPEG 앞부분의 세그먼트를 훑어 EXIF 세그먼트와 원본 크기를 찾는다. */
private class JpegHead(val exif: ByteArray?, val width: Int, val height: Int) {
    companion object {
        fun parse(head: ByteArray): JpegHead? {
            if (head.size < 4 || head.u8(0) != 0xFF || head.u8(1) != 0xD8) return null
            var exif: ByteArray? = null
            var width = 0
            var height = 0
            var offset = 2
            while (offset + 4 <= head.size) {
                if (head.u8(offset) != 0xFF) break
                val marker = head.u8(offset + 1)
                when {
                    // 채움 바이트
                    marker == 0xFF -> { offset++; continue }
                    // 길이가 없는 표지
                    marker == 0x01 || marker in 0xD0..0xD8 -> { offset += 2; continue }
                    // 영상 데이터가 시작되면 더 볼 세그먼트가 없다.
                    marker == 0xDA || marker == 0xD9 -> break
                }
                val length = head.u16(offset + 2)
                if (length < 2) break
                val end = offset + 2 + length
                if (marker.isStartOfFrame() && offset + 9 <= head.size) {
                    height = head.u16(offset + 5)
                    width = head.u16(offset + 7)
                }
                if (marker == 0xE1 && exif == null && end <= head.size && head.hasExifHeader(offset + 4)) {
                    exif = head.copyOfRange(offset, end)
                }
                if (end > head.size || (exif != null && width > 0)) break
                offset = end
            }
            return JpegHead(exif, width, height)
        }

        private fun Int.isStartOfFrame() = this in 0xC0..0xCF && this != 0xC4 && this != 0xC8 && this != 0xCC

        private fun ByteArray.hasExifHeader(at: Int): Boolean =
            at + EXIF_HEADER.size <= size && EXIF_HEADER.indices.all { this[at + it] == EXIF_HEADER[it] }

        private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF

        private fun ByteArray.u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
    }
}

private val MIME_FORMATS = mapOf(
    "image/jpeg" to EmbeddedFormat.Jpeg,
    "image/jpg" to EmbeddedFormat.Jpeg,
    "image/pjpeg" to EmbeddedFormat.Jpeg,
    "image/heic" to EmbeddedFormat.Heif,
    "image/heif" to EmbeddedFormat.Heif,
    "image/avif" to EmbeddedFormat.Heif,
    "image/x-adobe-dng" to EmbeddedFormat.Raw,
    "image/dng" to EmbeddedFormat.Raw,
    "image/x-canon-cr2" to EmbeddedFormat.Raw,
    "image/x-nikon-nef" to EmbeddedFormat.Raw,
    "image/x-nikon-nrw" to EmbeddedFormat.Raw,
    "image/x-sony-arw" to EmbeddedFormat.Raw,
    "image/x-panasonic-rw2" to EmbeddedFormat.Raw,
    "image/x-olympus-orf" to EmbeddedFormat.Raw,
    "image/x-pentax-pef" to EmbeddedFormat.Raw,
    "image/x-samsung-srw" to EmbeddedFormat.Raw,
    "image/x-fuji-raf" to EmbeddedFormat.Raw,
)
private val EXTENSION_FORMATS = mapOf(
    "jpg" to EmbeddedFormat.Jpeg,
    "jpeg" to EmbeddedFormat.Jpeg,
    "jpe" to EmbeddedFormat.Jpeg,
    "heic" to EmbeddedFormat.Heif,
    "heif" to EmbeddedFormat.Heif,
    "hif" to EmbeddedFormat.Heif,
    "avif" to EmbeddedFormat.Heif,
    "dng" to EmbeddedFormat.Raw,
    "cr2" to EmbeddedFormat.Raw,
    "nef" to EmbeddedFormat.Raw,
    "nrw" to EmbeddedFormat.Raw,
    "arw" to EmbeddedFormat.Raw,
    "rw2" to EmbeddedFormat.Raw,
    "orf" to EmbeddedFormat.Raw,
    "pef" to EmbeddedFormat.Raw,
    "srw" to EmbeddedFormat.Raw,
    "raf" to EmbeddedFormat.Raw,
)

// EXIF(APP1)은 최대 64KB이고 보통 맨 앞에 있어 이 안에 들어온다.
private const val JPEG_HEAD_BYTES = 128 * 1024
// RAW의 작은 썸네일은 보통 첫 IFD 근처에 있다. 큰 미리보기까지 찾으려고 더 읽지는 않는다.
private const val RAW_HEAD_BYTES = 1024 * 1024
private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
private val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
// 이보다 작은 썸네일은 칸에 늘려도 알아보기 어렵다.
internal const val MIN_EMBEDDED_EDGE = 64
private const val ASPECT_TOLERANCE = 0.03f
