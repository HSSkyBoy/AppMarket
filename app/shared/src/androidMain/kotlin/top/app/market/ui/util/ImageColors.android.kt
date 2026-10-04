package top.app.market.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.Image
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap

internal actual fun ImageRequest.Builder.allowColorSampling(): ImageRequest.Builder =
    allowHardware(false)

internal actual fun sampleImagePixels(image: Image, size: Int): IntArray = runCatching {
    val source = image.toBitmap(image.width, image.height)
    val sample = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(sample).drawBitmap(
        source,
        null,
        Rect(0, 0, size, size),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    )
    IntArray(size * size).also { sample.getPixels(it, 0, size, 0, 0, size, size) }
}.getOrDefault(IntArray(0))
