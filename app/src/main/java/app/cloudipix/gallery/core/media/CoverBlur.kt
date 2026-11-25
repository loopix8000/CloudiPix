package app.cloudipix.gallery.core.media

import android.graphics.Bitmap
import android.graphics.Color
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt

private const val COVER_SAMPLE_EDGE = 144f

internal object CoverBlur : Transformation() {
    override val cacheKey: String = "album-cover-blur-depth-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val scale = (COVER_SAMPLE_EDGE / maxOf(input.width, input.height)).coerceAtMost(1f)
        val width = (input.width * scale).roundToInt().coerceAtLeast(1)
        val height = (input.height * scale).roundToInt().coerceAtLeast(1)
        val readable = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else input
        val sampled = Bitmap.createScaledBitmap(readable, width, height, true)
        val pixels = IntArray(width * height)
        sampled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (sampled !== readable) sampled.recycle()
        if (readable !== input) readable.recycle()

        // 알파를 색에 미리 곱해 투명한 이미지의 가장자리에 어두운 띠가 생기지 않게 한다.
        for (index in pixels.indices) {
            val color = pixels[index]
            val alpha = Color.alpha(color)
            pixels[index] = Color.argb(
                alpha,
                (Color.red(color) * alpha + 127) / 255,
                (Color.green(color) * alpha + 127) / 255,
                (Color.blue(color) * alpha + 127) / 255,
            )
        }

        val edge = maxOf(width, height)
        val scratch = IntArray(pixels.size)
        soften(pixels, scratch, width, height, (edge * 0.077f).roundToInt().coerceAtLeast(1))
        val diffuse = pixels.copyOf()
        soften(diffuse, scratch, width, height, (edge * 0.125f).roundToInt().coerceAtLeast(1))

        // 커버 전체에 기본 블러를 유지하고, 중심에서 비껴난 지점부터 바깥으로 갈수록 더 흐려지게 한다.
        for (y in 0 until height) {
            val dy = ((y + 0.5f) / height - 0.38f) / 0.62f
            for (x in 0 until width) {
                val dx = ((x + 0.5f) / width - 0.43f) / 0.62f
                val distance = ((dx * dx + dy * dy - 0.06f) / 0.94f).coerceIn(0f, 1f)
                val blend = distance * distance * (3f - 2f * distance)
                val index = y * width + x
                pixels[index] = blendColors(pixels[index], diffuse[index], blend)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun soften(pixels: IntArray, scratch: IntArray, width: Int, height: Int, radius: Int) {
        // 이동 평균을 세 번 겹쳐 가우시안에 가까운 부드러운 색 전이를 만든다.
        repeat(3) {
            blurRows(pixels, scratch, width, height, radius)
            blurRows(scratch, pixels, height, width, radius)
        }
    }

    private fun blurRows(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val count = radius * 2 + 1
        val rounding = count / 2
        for (y in 0 until height) {
            val start = y * width
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val pixel = input[start + offset.coerceIn(0, width - 1)]
                alpha += Color.alpha(pixel)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
            }
            for (x in 0 until width) {
                // 전치해서 저장하므로 같은 함수로 세로 방향도 처리할 수 있다.
                output[x * height + y] = Color.argb(
                    (alpha + rounding) / count,
                    (red + rounding) / count,
                    (green + rounding) / count,
                    (blue + rounding) / count,
                )
                val outgoing = input[start + (x - radius).coerceAtLeast(0)]
                val incoming = input[start + (x + radius + 1).coerceAtMost(width - 1)]
                alpha += Color.alpha(incoming) - Color.alpha(outgoing)
                red += Color.red(incoming) - Color.red(outgoing)
                green += Color.green(incoming) - Color.green(outgoing)
                blue += Color.blue(incoming) - Color.blue(outgoing)
            }
        }
    }

    private fun blendColors(soft: Int, diffuse: Int, amount: Float): Int {
        val alpha = mix(Color.alpha(soft), Color.alpha(diffuse), amount)
        if (alpha == 0f) return Color.TRANSPARENT
        val unpremultiply = 255f / alpha
        return Color.argb(
            alpha.roundToInt(),
            (mix(Color.red(soft), Color.red(diffuse), amount) * unpremultiply).roundToInt().coerceAtMost(255),
            (mix(Color.green(soft), Color.green(diffuse), amount) * unpremultiply).roundToInt().coerceAtMost(255),
            (mix(Color.blue(soft), Color.blue(diffuse), amount) * unpremultiply).roundToInt().coerceAtMost(255),
        )
    }

    private fun mix(from: Int, to: Int, amount: Float): Float = from + (to - from) * amount
}
