package top.app.market.install

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

internal class NotificationIconLoader(private val context: Context) {
    // 图标要随通知进 extras，自定义 key 不走系统的 reduceImageSizes 瘦身，须自己限尺寸与总量
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun installedIcon(packageName: String): Bitmap? {
        val key = "pkg:$packageName"
        cache.get(key)?.let { return it }
        return runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap().roundedSquare()
        }.getOrNull()?.also { cache.put(key, it) }
    }

    suspend fun remoteIcon(url: String): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(ICON_SIZE_PX)
            .allowHardware(false) // 硬件位图无法写入通知 extras
            .build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
        val bitmap = ((result as? SuccessResult)?.image?.toBitmap() ?: return null).roundedSquare()
        cache.put(url, bitmap)
        return bitmap
    }

    // 超级岛胶囊 picInfo 不自动裁角，加载时裁成圆角方形（非方形先居中裁方）并缩到通知图标尺寸
    private fun Bitmap.roundedSquare(radiusFraction: Float = 0.25f): Bitmap {
        val sourceSize = minOf(width, height)
        val source = if (width == height) {
            this
        } else {
            Bitmap.createBitmap(this, (width - sourceSize) / 2, (height - sourceSize) / 2, sourceSize, sourceSize)
        }
        val size = minOf(sourceSize, ICON_SIZE_PX)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val radius = size * radiusFraction
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (size != sourceSize) {
                val scale = size.toFloat() / sourceSize
                setLocalMatrix(Matrix().apply { setScale(scale, scale) })
            }
        }
        Canvas(output).drawRoundRect(
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader },
        )
        return output
    }

    private companion object {
        const val ICON_SIZE_PX = 128
        const val CACHE_BYTES = 4 * 1024 * 1024
    }
}
