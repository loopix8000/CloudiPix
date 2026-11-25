package app.cloudipix.gallery.core.media

import androidx.exifinterface.media.ExifInterface
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/**
 * 사용자가 바꾸려는 메타데이터입니다. `null`인 항목은 그대로 둡니다.
 * 설명과 작성자를 빈 문자열로 바꾸면 해당 태그를 지웁니다.
 */
data class MetadataEdit(
    val taken: Long? = null,
    val description: String? = null,
    val author: String? = null,
    val removeLocation: Boolean = false,
)

/** ExifInterface가 메타데이터를 다시 쓸 수 있는 형식입니다. */
val EDITABLE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

/** EXIF 날짜 문자열과 UTC 오프셋 문자열입니다. 예: `2024:03:01 09:30:00`, `+09:00` */
internal data class ExifTimestamp(val dateTime: String, val offset: String)

private val EXIF_DATE_TIME = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)

internal fun exifTimestamp(epochMillis: Long, zone: ZoneId): ExifTimestamp {
    val zoned = Instant.ofEpochMilli(epochMillis).atZone(zone)
    return ExifTimestamp(EXIF_DATE_TIME.format(zoned), zoned.offset.toExifOffset())
}

/** EXIF 오프셋은 UTC에서도 `Z`가 아니라 `+00:00`으로 씁니다. */
private fun ZoneOffset.toExifOffset(): String {
    val seconds = totalSeconds
    val minutes = abs(seconds) / 60
    return String.format(Locale.ROOT, "%s%02d:%02d", if (seconds < 0) "-" else "+", minutes / 60, minutes % 60)
}

/**
 * EXIF 날짜 문자열을 epoch 밀리초로 바꿉니다. 오프셋이 없거나 읽을 수 없으면 [fallbackZone]의 현지 시각으로 봅니다.
 * 날짜를 읽을 수 없으면 `null`입니다.
 */
internal fun parseExifTimestamp(dateTime: String, offset: String?, fallbackZone: ZoneId): Long? {
    val local = try {
        LocalDateTime.parse(dateTime.trim(), EXIF_DATE_TIME)
    } catch (_: DateTimeParseException) {
        return null
    }
    val zone = offset?.trim()?.takeIf { it.isNotEmpty() }?.let {
        try {
            ZoneOffset.of(it)
        } catch (_: DateTimeException) {
            null
        }
    } ?: fallbackZone
    return local.atZone(zone).toInstant().toEpochMilli()
}

/** 원본 촬영 시각입니다. 촬영 시각 태그가 없으면 `null`입니다. */
internal fun ExifInterface.captureTime(): Long? {
    val dateTime = getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
    return parseExifTimestamp(dateTime, getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), ZoneId.systemDefault())
}

/** 촬영 시각을 바꾸면 원본·디지털화·파일 시각을 모두 같은 값으로 맞춥니다. */
private val TIMESTAMP_TAGS = mapOf(
    ExifInterface.TAG_DATETIME_ORIGINAL to ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED to ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_DATETIME to ExifInterface.TAG_OFFSET_TIME,
)

/** 촬영 위치나 이동 정보를 담는 GPS 태그입니다. 위치를 지울 때 모두 제거합니다. */
private val LOCATION_TAGS = arrayOf(
    ExifInterface.TAG_GPS_VERSION_ID,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_SATELLITES,
    ExifInterface.TAG_GPS_STATUS,
    ExifInterface.TAG_GPS_MEASURE_MODE,
    ExifInterface.TAG_GPS_DOP,
    ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_SPEED,
    ExifInterface.TAG_GPS_TRACK_REF,
    ExifInterface.TAG_GPS_TRACK,
    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    ExifInterface.TAG_GPS_IMG_DIRECTION,
    ExifInterface.TAG_GPS_MAP_DATUM,
    ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LATITUDE,
    ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LONGITUDE,
    ExifInterface.TAG_GPS_DEST_BEARING_REF,
    ExifInterface.TAG_GPS_DEST_BEARING,
    ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
    ExifInterface.TAG_GPS_DEST_DISTANCE,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_GPS_AREA_INFORMATION,
    ExifInterface.TAG_GPS_DIFFERENTIAL,
)

/** 변경 내용을 태그에 반영합니다. 파일에 저장하려면 이어서 `saveAttributes()`를 호출해야 합니다. */
fun ExifInterface.write(edit: MetadataEdit, zone: ZoneId = ZoneId.systemDefault()) {
    if (edit.taken != null) {
        val stamp = exifTimestamp(edit.taken, zone)
        for ((dateTag, offsetTag) in TIMESTAMP_TAGS) {
            setAttribute(dateTag, stamp.dateTime)
            setAttribute(offsetTag, stamp.offset)
        }
    }
    if (edit.description != null) setTextOrRemove(ExifInterface.TAG_IMAGE_DESCRIPTION, edit.description)
    if (edit.author != null) setTextOrRemove(ExifInterface.TAG_ARTIST, edit.author)
    if (edit.removeLocation) {
        for (tag in LOCATION_TAGS) setAttribute(tag, null)
    }
}

private fun ExifInterface.setTextOrRemove(tag: String, text: String) {
    setAttribute(tag, text.trim().takeIf { it.isNotEmpty() })
}
