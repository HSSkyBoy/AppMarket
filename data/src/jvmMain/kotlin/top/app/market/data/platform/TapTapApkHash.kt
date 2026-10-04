package top.app.market.data.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

internal suspend fun tapTapApkHash(path: String): String = withContext(Dispatchers.IO) {
    try {
        val file = File(path)
        if (!file.isFile || file.length() < TAIL_SIZE) return@withContext ""
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("META-INF") }
                .sortedBy { it.name }
                .forEach { entry ->
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
        }
        RandomAccessFile(file, "r").use { input ->
            input.seek(input.length() - TAIL_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: java.io.IOException) {
        ""
    } catch (_: SecurityException) {
        ""
    }
}

private const val TAIL_SIZE = 1024L * 1024L
