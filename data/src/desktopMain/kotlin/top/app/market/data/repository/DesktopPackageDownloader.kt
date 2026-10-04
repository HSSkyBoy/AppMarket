package top.app.market.data.repository

import top.app.market.data.platform.debugLog
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.ensureActive
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** 暂存数据与远端不一致（Range 被拒 / 校验失败），需要清空重下。 */
private class StaleStagingException(message: String) : Exception(message)

internal class DesktopPackageDownloader(
    private val http: HttpClient,
    private val root: Path = Path.of(System.getProperty("user.home"), "Downloads", "AppMarket"),
    private val requireHttps: Boolean = true,
) {
    /** 丢弃 [packageName] 的全部暂存数据（任务被删除时调用，不必等 14 天的过期清理）。 */
    fun discardStaging(packageName: String) {
        runCatching {
            val stagingRoot = root.resolve(STAGING_ROOT)
            if (!Files.isDirectory(stagingRoot)) return
            val prefix = "${sanitizeFileName(packageName)}-"
            Files.list(stagingRoot).use { entries ->
                entries.filter { it.fileName.toString().startsWith(prefix) }.forEach(::deleteStaging)
            }
        }
    }

    suspend fun download(
        meta: DownloadMeta,
        onProgress: (completed: Long, total: Long) -> Unit,
    ): Path {
        val parts = resolvedParts(meta)
        val names = desktopDownloadFileNames(meta, parts)
        val total = totalSize(meta, parts)
        Files.createDirectories(root)
        val stagingDirectory = stagingDirectory(root, meta)
        pruneStaleStaging(root.resolve(STAGING_ROOT), keep = stagingDirectory)

        // 第一阶段：断点续传到暂存目录；取消保留 .part 文件，重新下载同版本时接着传
        val transferred = LongArray(parts.size)
        parts.forEachIndexed { index, part ->
            stagePart(part, meta.requestHeaders, stagingFile(stagingDirectory, index, names[index])) { bytes ->
                transferred[index] = bytes
                onProgress(transferred.sum(), total)
            }
        }
        coroutineContext.ensureActive()

        // 第二阶段：全部完整后一次性落位到最终目录
        val multiple = parts.size > 1
        val directory = if (multiple) {
            createUniqueDirectory(root, sanitizeFileName("${meta.packageName}-${meta.versionCode}"))
        } else {
            root
        }
        val moved = mutableListOf<Path>()
        try {
            parts.forEachIndexed { index, _ ->
                val target = if (multiple) directory.resolve(names[index]) else uniqueFile(directory, names[index])
                moveAtomically(stagingFile(stagingDirectory, index, names[index]), target)
                moved.add(target)
            }
        } catch (error: Throwable) {
            moved.forEach { runCatching { Files.deleteIfExists(it) } }
            if (multiple) runCatching { Files.deleteIfExists(directory) }
            throw error
        }
        deleteStaging(stagingDirectory)
        return directory
    }

    private suspend fun stagePart(
        part: DownloadPart,
        headers: Map<String, String>,
        staging: Path,
        onBytes: (Long) -> Unit,
    ) {
        require(!requireHttps || part.url.startsWith("https://", ignoreCase = true)) { "Only HTTPS package URLs are allowed" }
        Files.createDirectories(staging.parent)
        try {
            stagePartAttempt(part, headers, staging, onBytes)
        } catch (stale: StaleStagingException) {
            // 暂存与远端不一致：清空重下一次；仍不一致按校验失败上抛
            debugLog("DesktopDownload") { "discarding staged ${part.name}: ${stale.message}, restarting" }
            Files.deleteIfExists(staging)
            onBytes(0L)
            try {
                stagePartAttempt(part, headers, staging, onBytes)
            } catch (again: StaleStagingException) {
                Files.deleteIfExists(staging)
                error("Downloaded package failed verification: ${part.name} (${again.message})")
            }
        }
    }

    private suspend fun stagePartAttempt(
        part: DownloadPart,
        headers: Map<String, String>,
        staging: Path,
        onBytes: (Long) -> Unit,
    ) {
        val existing = if (Files.exists(staging)) Files.size(staging) else 0L
        if (part.size in 1..<existing) throw StaleStagingException("staged $existing bytes exceed expected ${part.size}")
        val digest = messageDigest(part.hash)
        val signature = ByteArray(APK_SIGNATURE_SIZE)
        var signatureSize = 0
        var transferred = 0L
        fun consume(buffer: ByteArray, read: Int) {
            if (signatureSize < signature.size) {
                val count = minOf(read, signature.size - signatureSize)
                buffer.copyInto(signature, signatureSize, 0, count)
                signatureSize += count
            }
            digest?.update(buffer, 0, read)
            transferred += read
            onBytes(transferred)
        }
        // 已有字节重放：摘要、APK 签名头、进度
        if (existing > 0L) {
            Files.newInputStream(staging).buffered(IO_BUFFER_SIZE).use { input ->
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    consume(buffer, read)
                }
            }
        }
        if (part.size <= 0L || transferred < part.size) {
            val resumeFrom = transferred
            http.prepareGet(part.url) {
                headers.forEach { (name, value) ->
                    if (!name.equals(HttpHeaders.Host, ignoreCase = true) &&
                        !name.equals(HttpHeaders.ContentLength, ignoreCase = true) &&
                        !name.equals(HttpHeaders.AcceptEncoding, ignoreCase = true) &&
                        !name.equals(HttpHeaders.Range, ignoreCase = true)
                    ) {
                        header(name, value)
                    }
                }
                header(HttpHeaders.AcceptEncoding, "identity")
                if (resumeFrom > 0L) header(HttpHeaders.Range, "bytes=$resumeFrom-")
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MILLIS
                }
            }.execute { response ->
                if (resumeFrom > 0L && response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                    // 服务器已无更多字节：大小未知视为已完整，否则按过期数据重来
                    if (part.size > 0L) throw StaleStagingException("range not satisfiable at $resumeFrom")
                    return@execute
                }
                check(response.status.isSuccess()) {
                    "Package download failed: HTTP ${response.status.value}"
                }
                check(!requireHttps || response.request.url.protocol == URLProtocol.HTTPS) {
                    "Package redirect left HTTPS"
                }
                // 服务器忽略 Range 返回全量：丢弃暂存重下
                if (resumeFrom > 0L && response.status != HttpStatusCode.PartialContent) {
                    throw StaleStagingException("server ignored Range at $resumeFrom (HTTP ${response.status.value})")
                }
                val options = if (resumeFrom > 0L) {
                    arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                } else {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                }
                Files.newOutputStream(staging, *options).buffered(IO_BUFFER_SIZE).use { output ->
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        consume(buffer, read)
                    }
                }
            }
        }
        if (part.size > 0L && transferred != part.size) {
            error("Incomplete package ${part.name}: $transferred/${part.size}")
        }
        if (signatureSize < signature.size ||
            signature[0] != 'P'.code.toByte() ||
            signature[1] != 'K'.code.toByte()
        ) {
            throw StaleStagingException("payload is not an APK")
        }
        if (digest != null && !digestMatches(digest, part.hash)) throw StaleStagingException("checksum mismatch")
    }
}

internal fun desktopDownloadFileNames(
    meta: DownloadMeta,
    parts: List<DownloadPart> = resolvedParts(meta),
): List<String> {
    if (parts.size == 1) {
        val version = meta.versionName.ifBlank { meta.versionCode.toString() }
        return listOf(sanitizeFileName("${meta.displayName}-$version").ensureApkSuffix())
    }
    return parts.mapIndexed { index, part -> sessionFileName(index, part) }
}

internal fun initialProgress(meta: DownloadMeta): Int? =
    if (totalSize(meta, resolvedParts(meta)) > 0L) 0 else null

private fun resolvedParts(meta: DownloadMeta): List<DownloadPart> =
    meta.parts.takeIf { it.isNotEmpty() }
        ?: listOf(DownloadPart(name = "base", type = "base", url = meta.url, size = meta.size))

private fun totalSize(meta: DownloadMeta, parts: List<DownloadPart>): Long =
    parts.map(DownloadPart::size)
        .takeIf { sizes -> sizes.all { it > 0L } }
        ?.sum()
        ?: meta.size.takeIf { it > 0L }
        ?: -1L

private fun sessionFileName(index: Int, part: DownloadPart): String {
    if (index == 0 && (part.name.equals("base", true) || part.type.equals("base", true))) return "base.apk"
    val label = listOf(part.name, part.type)
        .filter { it.isNotBlank() && !it.equals("base", true) }
        .distinct()
        .joinToString("_")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "split_$index" }
    return "${index}_$label".ensureApkSuffix()
}

private fun sanitizeFileName(value: String): String =
    value.ifBlank { "package" }
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .trim('_', '.')
        .take(MAX_FILE_NAME_LENGTH)
        .trim('_', '.')
        .ifBlank { "package" }

private fun String.ensureApkSuffix(): String =
    if (endsWith(".apk", ignoreCase = true)) this else "$this.apk"

private fun stagingDirectory(root: Path, meta: DownloadMeta): Path {
    val directory = root.resolve(STAGING_ROOT)
        .resolve(sanitizeFileName("${meta.packageName}-${meta.versionCode}"))
    Files.createDirectories(directory)
    return directory
}

private fun stagingFile(directory: Path, index: Int, name: String): Path =
    directory.resolve("${index}_$name.part")

/** 清掉长期没动过的残留暂存目录，避免中断的下载无限占盘。 */
private fun pruneStaleStaging(stagingRoot: Path, keep: Path) {
    runCatching {
        if (!Files.isDirectory(stagingRoot)) return
        Files.list(stagingRoot).use { entries ->
            entries.filter { it != keep }.forEach { directory ->
                if (System.currentTimeMillis() - lastTouched(directory) > STAGING_RETENTION_MILLIS) {
                    deleteStaging(directory)
                }
            }
        }
    }
}

private fun lastTouched(directory: Path): Long = runCatching {
    var latest = Files.getLastModifiedTime(directory).toMillis()
    Files.list(directory).use { entries ->
        entries.forEach { entry ->
            latest = maxOf(latest, runCatching { Files.getLastModifiedTime(entry).toMillis() }.getOrDefault(0L))
        }
    }
    latest
}.getOrDefault(Long.MAX_VALUE)

@OptIn(ExperimentalPathApi::class)
private fun deleteStaging(directory: Path) {
    runCatching { directory.deleteRecursively() }
}

private fun createUniqueDirectory(parent: Path, requestedName: String): Path {
    var suffix = 0
    while (true) {
        val name = if (suffix == 0) requestedName else "$requestedName-$suffix"
        val candidate = parent.resolve(name)
        try {
            return Files.createDirectory(candidate)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            suffix += 1
        }
    }
}

private fun uniqueFile(directory: Path, requestedName: String): Path {
    val base = requestedName.removeSuffix(".apk")
    var candidate = directory.resolve(requestedName)
    var suffix = 1
    while (Files.exists(candidate)) {
        candidate = directory.resolve("$base-$suffix.apk")
        suffix += 1
    }
    return candidate
}

private fun moveAtomically(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}

private fun messageDigest(checksum: String): MessageDigest? = when (checksum.trim().length) {
    32 -> MessageDigest.getInstance("MD5")
    40 -> MessageDigest.getInstance("SHA-1")
    64 -> MessageDigest.getInstance("SHA-256")
    else -> null
}

private fun digestMatches(digest: MessageDigest, checksum: String): Boolean =
    digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } ==
            checksum.trim().lowercase()

private const val APK_SIGNATURE_SIZE = 2
private const val IO_BUFFER_SIZE = 128 * 1024
private const val DOWNLOAD_SOCKET_TIMEOUT_MILLIS = 60_000L
private const val MAX_FILE_NAME_LENGTH = 120
private const val STAGING_ROOT = ".staging"
private const val STAGING_RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000
