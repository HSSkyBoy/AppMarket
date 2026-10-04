package top.app.market.ui.util

import coil3.Image
import coil3.request.ImageRequest
import coil3.toBitmap

internal actual fun ImageRequest.Builder.allowColorSampling(): ImageRequest.Builder = this

internal actual fun sampleImagePixels(image: Image, size: Int): IntArray = runCatching {
    val bitmap = image.toBitmap(image.width, image.height)
    IntArray(size * size) { index ->
        val sampleX = index % size
        val sampleY = index / size
        val sourceX = ((sampleX + 0.5f) * bitmap.width / size).toInt().coerceAtMost(bitmap.width - 1)
        val sourceY = ((sampleY + 0.5f) * bitmap.height / size).toInt().coerceAtMost(bitmap.height - 1)
        bitmap.getColor(sourceX, sourceY)
    }
}.getOrDefault(IntArray(0))
