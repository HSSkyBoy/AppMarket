package top.app.market.ui.util

import androidx.compose.ui.graphics.Color
import coil3.Image
import coil3.request.ImageRequest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal expect fun sampleImagePixels(image: Image, size: Int = 24): IntArray

internal expect fun ImageRequest.Builder.allowColorSampling(): ImageRequest.Builder

// 图片顶部 rows 行（24×24 采样网格）的平均亮度（BT.601，0..1）；dominantImageColor 会归一化亮度，不适用于明暗判断
internal fun topRegionLuminance(image: Image, rows: Int = 4): Float? {
    val size = 24
    val pixels = sampleImagePixels(image, size)
    if (pixels.size < size * rows) return null

    var sum = 0f
    var count = 0
    for (index in 0 until size * rows) {
        val argb = pixels[index]
        val alpha = (argb ushr 24) and 0xff
        if (alpha < 128) continue
        val red = (argb ushr 16) and 0xff
        val green = (argb ushr 8) and 0xff
        val blue = argb and 0xff
        sum += (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
        count++
    }
    return if (count == 0) null else sum / count
}

internal fun dominantImageColor(image: Image): Color? {
    val pixels = sampleImagePixels(image)
    if (pixels.isEmpty()) return null

    val buckets = mutableMapOf<Int, ColorBucket>()

    pixels.forEach { argb ->
        val alpha = (argb ushr 24) and 0xff
        val red = (argb ushr 16) and 0xff
        val green = (argb ushr 8) and 0xff
        val blue = argb and 0xff

        val maxChannel = max(red, max(green, blue))
        val minChannel = min(red, min(green, blue))

        if (alpha < 128 || maxChannel < 24 || minChannel > 240) return@forEach

        val key = ((red and 0xF0) shl 4) or (green and 0xF0) or (blue shr 4)

        buckets.getOrPut(key) { ColorBucket() }.add(red, green, blue)
    }

    val bucket = buckets.values.maxByOrNull { it.count } ?: return null

    return with(bucket) {
        normalizeLightness(Color(red / count, green / count, blue / count))
    }
}

private class ColorBucket {
    var count = 0
    var red = 0
    var green = 0
    var blue = 0

    fun add(r: Int, g: Int, b: Int) {
        count++
        red += r
        green += g
        blue += b
    }
}

private fun normalizeLightness(color: Color): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxChannel = max(r, max(g, b))
    val minChannel = min(r, min(g, b))
    val delta = maxChannel - minChannel
    val lightness = (maxChannel + minChannel) / 2f
    val saturation = if (delta == 0f) 0f else delta / (1f - abs(2f * lightness - 1f))
    val hue = when {
        delta == 0f -> 0f
        maxChannel == r -> 60f * (((g - b) / delta) % 6f)
        maxChannel == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val chroma = (1f - abs(2f * 0.45f - 1f)) * saturation
    val x = chroma * (1f - abs((hue / 60f) % 2f - 1f))
    val (r1, g1, b1) = when (hue) {
        in 0f..<60f -> Triple(chroma, x, 0f)
        in 60f..<120f -> Triple(x, chroma, 0f)
        in 120f..<180f -> Triple(0f, chroma, x)
        in 180f..<240f -> Triple(0f, x, chroma)
        in 240f..<300f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val match = 0.45f - chroma / 2f
    return Color(r1 + match, g1 + match, b1 + match)
}
