package app.cloudipix.gallery.core.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Size
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [source]를 [target]에 맞춰 디코딩한다. [fill]이면 칸을 덮는 크기, 아니면 칸 안에 들어가는 크기로 만든다.
 * 원본이 칸보다 작으면 늘리지 않는다.
 */
@Throws(IOException::class)
internal fun decodeThumbnail(source: ImageDecoder.Source, target: Size, fill: Boolean): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val width = info.size.width
        val height = info.size.height
        val ratio = scaleRatio(width, height, target, fill)
        // JPEG와 WebP는 디코더에서 바로 표시 크기로 줄인다. 다른 형식은 기존의 단계별 축소를 사용한다.
        val averaged = info.mimeType == "image/jpeg" || info.mimeType == "image/webp"
        if (averaged && ratio < 1f) {
            decoder.setTargetSize((width * ratio).roundToInt().coerceAtLeast(1), (height * ratio).roundToInt().coerceAtLeast(1))
            return@decodeBitmap
        }
        var sample = 1
        while (sample * 2 * ratio <= 1f &&
            width.toLong() * height / (sample.toLong() * sample) > MAX_FULL_DECODE_PIXELS
        ) {
            sample *= 2
        }
        if (sample > 1) decoder.setTargetSampleSize(sample)
    }.scaleAndRecycle(target, fill)

/**
 * 화면에서 bilinear로 1~2배 축소하면 건너뛰는 픽셀이 생겨 선이 자글자글해지므로 미리 칸 크기로 줄인다.
 * 정확히 절반으로 줄일 때 bilinear가 2×2 평균이 되는 점을 이용한다.
 * 결과와 다른 입력 및 중간 비트맵은 즉시 해제한다.
 */
internal fun Bitmap.scaleAndRecycle(target: Size, fill: Boolean): Bitmap {
    val ratio = scaleRatio(width, height, target, fill)
    val targetWidth = (width * ratio).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * ratio).roundToInt().coerceAtLeast(1)
    if (targetWidth >= width && targetHeight >= height) return this

    var scaled = this
    fun replace(next: Bitmap) {
        if (scaled !== next) scaled.recycle()
        scaled = next
    }
    while (scaled.width >= targetWidth * 2 && scaled.height >= targetHeight * 2) {
        replace(Bitmap.createScaledBitmap(scaled, scaled.width / 2, scaled.height / 2, true))
    }
    if (scaled.width != targetWidth || scaled.height != targetHeight) {
        // 남은 1~2배는 목표의 두 배로 늘린 뒤 절반으로 줄인다.
        if (scaled.width > targetWidth || scaled.height > targetHeight) {
            replace(Bitmap.createScaledBitmap(scaled, targetWidth * 2, targetHeight * 2, true))
        }
        replace(Bitmap.createScaledBitmap(scaled, targetWidth, targetHeight, true))
    }
    return scaled
}

/** 썸네일 캐시 파일로 저장한다. 손실 압축하면 투명한 부분이 뭉개져 투명한 픽셀이 있으면 무손실로 저장한다. */
internal fun Bitmap.compressThumbnail(output: OutputStream): Boolean =
    if (hasTransparentPixels()) compress(Bitmap.CompressFormat.WEBP_LOSSLESS, THUMBNAIL_LOSSLESS_EFFORT, output)
    else compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)

/** RGBA PNG는 모든 픽셀이 불투명해도 투명도가 있는 비트맵으로 디코딩되어 픽셀을 직접 확인한다. */
private fun Bitmap.hasTransparentPixels(): Boolean {
    if (!hasAlpha()) return false
    // 하드웨어 비트맵은 픽셀을 읽을 수 없어 복사본에서 확인한다.
    val pixels = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    try {
        val row = IntArray(pixels.width)
        for (y in 0 until pixels.height) {
            pixels.getPixels(row, 0, pixels.width, 0, y, pixels.width, 1)
            if (row.any { it ushr 24 != 0xFF }) return true
        }
        return false
    } finally {
        if (pixels !== this) pixels.recycle()
    }
}

/** [width]×[height] 비트맵이 늘리지 않고 [target]을 채우는지 본다. 반올림한 치수의 1픽셀 차이는 허용한다. */
internal fun covers(width: Int, height: Int, target: Size, fill: Boolean): Boolean {
    val wide = width + 1 >= target.width
    val tall = height + 1 >= target.height
    return if (fill) wide && tall else wide || tall
}

private fun scaleRatio(width: Int, height: Int, target: Size, fill: Boolean): Float {
    val widthRatio = target.width.toFloat() / width
    val heightRatio = target.height.toFloat() / height
    return if (fill) max(widthRatio, heightRatio) else min(widthRatio, heightRatio)
}

// 디코더가 픽셀을 건너뛰지 않도록 원본 그대로 디코딩하는 상한(ARGB 약 32MB)
private const val MAX_FULL_DECODE_PIXELS = 8_000_000L
private const val THUMBNAIL_QUALITY = 94
// 무손실 WebP의 품질 값은 압축 노력이다. 100은 작은 썸네일에도 오래 걸려 libwebp 기본값을 쓴다.
private const val THUMBNAIL_LOSSLESS_EFFORT = 75
