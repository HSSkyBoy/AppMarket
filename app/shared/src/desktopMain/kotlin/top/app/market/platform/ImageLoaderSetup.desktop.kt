package top.app.market.platform

import coil3.PlatformContext
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import java.io.File

// coil3 在 JVM 上默认缓存到系统临时目录，会被系统清理策略随时抹掉；固定到应用目录并给出上限
internal actual fun imageDiskCache(context: PlatformContext): DiskCache? = DiskCache.Builder()
    .directory(File(System.getProperty("user.home"), ".app-market/image_cache").toOkioPath())
    .maxSizeBytes(256L * 1024 * 1024)
    .build()
