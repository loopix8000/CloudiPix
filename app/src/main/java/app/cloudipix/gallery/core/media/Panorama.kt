package app.cloudipix.gallery.core.media

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 구면 파노라마에서 이미지가 덮는 영역입니다. 값은 전체 구면 대비 비율(0~1)이며,
 * 기본값은 구면 전체를 덮는 이미지를 뜻합니다.
 */
data class Panorama(
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
)

/** 사진은 XMP의 Google Photo Sphere 속성으로, 동영상은 MP4의 구면 메타데이터로 파노라마인지 판단합니다. */
fun readPanorama(resolver: ContentResolver, uri: Uri, isVideo: Boolean): Panorama? = try {
    if (isVideo) {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                Panorama().takeIf { isSphericalMp4(channel) }
            }
        }
    } else {
        resolver.openInputStream(uri)?.use { input ->
            val xmp = ExifInterface(input).getAttributeBytes(ExifInterface.TAG_XMP)
            xmp?.let { panoramaFromXmp(String(it, Charsets.UTF_8)) }
        }
    }
} catch (_: Exception) {
    null
}

// ---- XMP (GPano) ----

private const val GPANO = "GPano:"

// XMP는 속성을 `GPano:Name="값"` 형태의 속성으로도, `<GPano:Name>값</GPano:Name>` 형태의 요소로도 기록합니다.
private val GPANO_ATTRIBUTE = Regex("""\bGPano:(\w+)\s*=\s*(["'])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)
private val GPANO_ELEMENT = Regex("""<GPano:(\w+)(?:\s[^>]*)?>([^<]*)<""")

internal fun panoramaFromXmp(xmp: String): Panorama? {
    if (!xmp.contains(GPANO)) return null
    val props = gpanoProperties(xmp)
    if (!props["ProjectionType"].equals("equirectangular", ignoreCase = true)) return null

    fun number(name: String): Float? = props[name]?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
    fun dimension(name: String): Float? = number(name)?.takeIf { it > 0f }

    val fullWidth = dimension("FullPanoWidthPixels")
    val fullHeight = dimension("FullPanoHeightPixels")
    val shownWidth = dimension("CroppedAreaImageWidthPixels")
    val shownHeight = dimension("CroppedAreaImageHeightPixels")
    // 잘린 영역 정보가 불완전하면 구면 전체를 덮는 사진으로 봅니다.
    if (fullWidth == null || fullHeight == null || shownWidth == null || shownHeight == null) return Panorama()

    return Panorama(
        left = (number("CroppedAreaLeftPixels") ?: 0f) / fullWidth,
        top = (number("CroppedAreaTopPixels") ?: 0f) / fullHeight,
        width = shownWidth / fullWidth,
        height = shownHeight / fullHeight,
    )
}

/** 같은 속성이 여러 번 나오면 처음 값을 씁니다. */
private fun gpanoProperties(xmp: String): Map<String, String> {
    val found = HashMap<String, String>()
    for (pattern in listOf(GPANO_ATTRIBUTE, GPANO_ELEMENT)) {
        for (match in pattern.findAll(xmp)) {
            val name = match.groupValues[1]
            val value = match.groupValues.last().trim()
            if (value.isNotEmpty()) found.putIfAbsent(name, value)
        }
    }
    return found
}

// ---- MP4 구면 동영상 ----

/** 구면 메타데이터를 찾을 때 `moov` 상자에서 읽는 최대 크기입니다. */
private const val MOOV_SCAN_LIMIT = 32L * 1024 * 1024
private const val SCAN_CHUNK = 64 * 1024

/**
 * Spherical Video V1(`uuid` 상자 안의 `GSpherical` XML)과 V2(`sv3d` 상자) 표식을 찾습니다.
 * 표식은 `moov` 아래 트랙 정보에만 있으므로 최상위 `moov` 상자 본문만 검사합니다.
 */
private val SPHERICAL_TAGS = listOf("sv3d", "GSpherical").map { it.toByteArray(Charsets.US_ASCII) }

private val MOOV = fourCc("moov")

private fun fourCc(name: String): Int = name.fold(0) { acc, ch -> (acc shl 8) or ch.code }

/** MP4 상자 하나의 종류와 위치입니다. [end]는 파일 크기를 넘지 않도록 잘라 둡니다. */
private class Mp4Box(val type: Int, val bodyStart: Long, val end: Long)

internal fun isSphericalMp4(channel: FileChannel): Boolean {
    val fileSize = channel.size()
    var offset = 0L
    while (true) {
        val box = channel.boxAt(offset, fileSize) ?: return false
        if (box.type == MOOV) {
            val scanEnd = minOf(box.end, box.bodyStart + MOOV_SCAN_LIMIT)
            return channel.containsAny(box.bodyStart, scanEnd - box.bodyStart, SPHERICAL_TAGS)
        }
        offset = box.end
    }
}

/** [offset]에서 시작하는 상자의 헤더를 읽습니다. 헤더가 잘렸거나 크기 값이 올바르지 않으면 `null`입니다. */
private fun FileChannel.boxAt(offset: Long, fileSize: Long): Mp4Box? {
    val available = fileSize - offset
    if (available < 8) return null
    val header = ByteBuffer.allocate(minOf(available, 16L).toInt())
    if (!readFully(header, offset)) return null

    val compactSize = header.getInt(0).toLong() and 0xFFFF_FFFFL
    val (headerLength, length) = when (compactSize) {
        // 크기 1: 타입 뒤의 64비트 필드에 실제 크기가 있습니다.
        1L -> if (header.capacity() < 16) return null else 16L to header.getLong(8)
        // 크기 0: 파일 끝까지 이어지는 마지막 상자입니다.
        0L -> 8L to available
        else -> 8L to compactSize
    }
    if (length < headerLength) return null
    return Mp4Box(header.getInt(4), offset + headerLength, offset + minOf(length, available))
}

/** 파일 끝에 닿기 전까지 버퍼를 끝까지 채웁니다. */
private fun FileChannel.readFully(buffer: ByteBuffer, position: Long): Boolean {
    var at = position
    while (buffer.hasRemaining()) {
        val read = read(buffer, at)
        if (read < 0) return false
        at += read
    }
    return true
}

/** 구간을 조각으로 나눠 읽으며 표식을 찾습니다. 조각 경계에 걸친 표식도 찾도록 앞 조각의 끝을 겹쳐 읽습니다. */
private fun FileChannel.containsAny(start: Long, length: Long, needles: List<ByteArray>): Boolean {
    if (length <= 0) return false
    val overlap = needles.maxOf { it.size } - 1
    val buffer = ByteBuffer.allocate(minOf(length, (SCAN_CHUNK + overlap).toLong()).toInt())
    val end = start + length
    var position = start
    var carried = 0
    while (position < end) {
        buffer.limit(minOf(buffer.capacity().toLong(), carried + (end - position)).toInt())
        while (buffer.hasRemaining()) {
            val read = read(buffer, position)
            if (read < 0) break
            position += read
        }
        val filled = buffer.position()
        if (filled == carried) return false
        val bytes = buffer.array()
        if (needles.any { bytes.indexOf(it, filled) >= 0 }) return true

        carried = minOf(overlap, filled)
        System.arraycopy(bytes, filled - carried, bytes, 0, carried)
        buffer.clear().position(carried)
    }
    return false
}

private fun ByteArray.indexOf(needle: ByteArray, length: Int): Int {
    val first = needle[0]
    for (i in 0..length - needle.size) {
        if (this[i] != first) continue
        var j = 1
        while (j < needle.size && this[i + j] == needle[j]) j++
        if (j == needle.size) return i
    }
    return -1
}
