package top.app.market.data.install.network

import top.app.market.data.install.DeltaFallbackBus
import top.app.market.data.install.InstallPipelineException
import top.app.market.data.install.patch.DeltaPatchEngine
import top.app.market.data.install.storage.MediaStorePackageStore
import top.app.market.data.platform.debugLog
import top.app.market.domain.model.install.InstallArtifact
import top.app.market.domain.model.install.InstallFailureCode
import top.app.market.domain.model.install.InstallSource
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 暂存数据与远端不一致（Range 被拒 / 校验失败），需要清空重下。 */
private class StaleStagingException : Exception()

internal class ArtifactSourceReader(
    private val http: InstallHttpClient,
    private val packageStore: MediaStorePackageStore,
    private val deltaFallbacks: DeltaFallbackBus,
) {
    /**
     * 确保 [artifact] 完整落在 [target]（增量补丁暂存在 [patchTarget]）；已有字节走 Range 续传。
     * [onProgress] 同时回调已传输字节与当前分母——增量降级为全量时分母会从补丁大小切到整包大小。
     */
    suspend fun stage(
        artifact: InstallArtifact,
        target: File,
        patchTarget: File,
        onProgress: suspend (transferred: Long, total: Long) -> Unit,
    ) {
        when (val source = artifact.source) {
            is InstallSource.Remote -> stageRemote(source, target, artifact.size, artifact.checksum) { bytes ->
                onProgress(bytes, artifact.size)
            }

            is InstallSource.Delta -> stageDelta(source, artifact, target, patchTarget, onProgress)
            is InstallSource.Local -> Unit
        }
    }

    /** 将已暂存的 [staged]（本地源则直接读源）流拷贝到 [outputs]。 */
    suspend fun copyTo(
        artifact: InstallArtifact,
        staged: File?,
        outputs: List<OutputStream>,
        onBytes: suspend (Long) -> Unit = {},
    ) {
        when (val source = artifact.source) {
            is InstallSource.Local -> copyLocal(source, outputs, onBytes)
            else -> copyFile(
                staged ?: throw InstallPipelineException(
                    InstallFailureCode.STORAGE,
                    "Staged artifact ${artifact.name} is missing",
                ),
                outputs,
                onBytes,
            )
        }
    }

    private suspend fun stageRemote(
        source: InstallSource.Remote,
        target: File,
        expectedSize: Long,
        checksum: String,
        onBytes: suspend (Long) -> Unit,
    ) {
        if (!source.url.startsWith("https://", ignoreCase = true)) {
            throw InstallPipelineException(InstallFailureCode.NETWORK, "Only HTTPS package URLs are allowed")
        }
        try {
            stageRemoteAttempt(source, target, expectedSize, checksum, onBytes)
        } catch (_: StaleStagingException) {
            // 暂存与远端不一致：清空重下一次；仍不一致按完整性错误上抛
            target.delete()
            onBytes(0L)
            try {
                stageRemoteAttempt(source, target, expectedSize, checksum, onBytes)
            } catch (_: StaleStagingException) {
                target.delete()
                throw InstallPipelineException(
                    InstallFailureCode.INTEGRITY,
                    "Checksum mismatch for ${target.name}",
                )
            }
        }
    }

    /** 全新下载用滚动摘要；续传改为完成后整文件校验一次，避免恢复前先重放已下载的几百 MB。 */
    private suspend fun stageRemoteAttempt(
        source: InstallSource.Remote,
        target: File,
        expectedSize: Long,
        checksum: String,
        onBytes: suspend (Long) -> Unit,
    ) {
        if (expectedSize in 1..<target.length()) throw StaleStagingException()
        val existing = target.takeIf { it.isFile }?.length() ?: 0L
        val verifiesChecksum = checksum.isNotBlank()
        val rollingDigest = checksum.takeIf { verifiesChecksum && existing == 0L }?.let(::messageDigest)
        var transferred = existing
        if (existing > 0L) onBytes(existing)
        if (expectedSize <= 0L || transferred < expectedSize) {
            transferred = fetchRemote(source, target, transferred, expectedSize, rollingDigest, onBytes)
        }
        if (expectedSize > 0L && transferred != expectedSize) {
            throw InstallPipelineException(
                InstallFailureCode.INTEGRITY,
                "Incomplete artifact ${target.name}: $transferred/$expectedSize",
            )
        }
        if (!verifiesChecksum) return
        val digest = rollingDigest ?: messageDigest(checksum).also { streamThroughDigest(target, it) }
        if (!digestMatches(digest, checksum)) throw StaleStagingException()
    }

    private suspend fun fetchRemote(
        source: InstallSource.Remote,
        target: File,
        offset: Long,
        expectedSize: Long,
        digest: MessageDigest?,
        onBytes: suspend (Long) -> Unit,
    ): Long = try {
        http.client.prepareGet(source.url) {
            source.headers.forEach { (name, value) ->
                if (!name.equals(HttpHeaders.Host, ignoreCase = true) &&
                    !name.equals(HttpHeaders.ContentLength, ignoreCase = true) &&
                    !name.equals(HttpHeaders.Range, ignoreCase = true)
                ) {
                    header(name, value)
                }
            }
            header(HttpHeaders.AcceptEncoding, "identity")
            if (offset > 0L) header(HttpHeaders.Range, "bytes=$offset-")
        }.execute { response ->
            if (offset > 0L && response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                // 服务器已无更多字节：大小未知视为已完整，否则按过期数据重来
                if (expectedSize <= 0L) return@execute offset
                throw StaleStagingException()
            }
            if (!response.status.isSuccess()) {
                throw InstallPipelineException(
                    InstallFailureCode.NETWORK,
                    "Package download failed: HTTP ${response.status.value}",
                )
            }
            if (response.request.url.protocol != URLProtocol.HTTPS) {
                throw InstallPipelineException(InstallFailureCode.NETWORK, "Package redirect left HTTPS")
            }
            // 服务器忽略 Range 返回全量：丢弃暂存重下
            if (offset > 0L && response.status != HttpStatusCode.PartialContent) throw StaleStagingException()
            target.parentFile?.mkdirs()
            var transferred = offset
            FileOutputStream(target, offset > 0L).buffered(IO_BUFFER_SIZE).use { output ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest?.update(buffer, 0, read)
                    transferred += read
                    onBytes(transferred)
                }
            }
            transferred
        }
    } catch (error: InstallPipelineException) {
        throw error
    } catch (error: StaleStagingException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw InstallPipelineException(
            InstallFailureCode.NETWORK,
            error.message ?: "Package download failed",
            error,
        )
    }

    /**
     * 增量失败一律降级全量：缺 so、base APK 被 ROM 重打包、协议版本变更在真机上都是常态。
     * 网络/存储类失败不降级——全量同样会失败，且会丢掉已续传的补丁数据。
     */
    private suspend fun stageDelta(
        source: InstallSource.Delta,
        artifact: InstallArtifact,
        target: File,
        patchTarget: File,
        onProgress: suspend (transferred: Long, total: Long) -> Unit,
    ) {
        val baseApk = File(source.baseApkPath)
        if (baseApk.isFile && baseApk.canRead()) {
            // 合成产物已完整（进程重启后恢复）：进度直接记满
            if (isStagedComplete(target, artifact.size, artifact.checksum)) {
                patchTarget.delete()
                onProgress(source.patchSize.coerceAtLeast(0L), source.patchSize)
                return
            }
            val failure = try {
                synthesizeDelta(source, artifact, target, patchTarget, onProgress)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: InstallPipelineException) {
                if (error.code != InstallFailureCode.INTEGRITY) throw error
                error
            }
            debugLog("DeltaPatch") { "Falling back to full download for ${artifact.name}: ${failure.message}" }
            deltaFallbacks.emit(artifact.name, failure.message.orEmpty())
        } else {
            debugLog("DeltaPatch") { "Base APK unreadable for ${artifact.name}, downloading full package" }
            deltaFallbacks.emit(artifact.name, "base APK unreadable")
        }
        // 只丢补丁：target 装的是上次降级留下的整包续传进度（合成走 .delta.tmp，不会是半成品）
        patchTarget.delete()
        onProgress(0L, artifact.size)
        stageRemote(source.full, target, artifact.size, artifact.checksum) { bytes ->
            onProgress(bytes, artifact.size)
        }
    }

    private suspend fun synthesizeDelta(
        source: InstallSource.Delta,
        artifact: InstallArtifact,
        target: File,
        patchTarget: File,
        onProgress: suspend (transferred: Long, total: Long) -> Unit,
    ) {
        stageRemote(source.patch, patchTarget, source.patchSize, source.patchChecksum) { bytes ->
            onProgress(bytes, source.patchSize)
        }
        val synthesized = File(target.parentFile, "${target.name}.delta.tmp")
        try {
            DeltaPatchEngine.synthesize(source, patchTarget, synthesized)
            verifySynthesized(synthesized, artifact)
            target.delete()
            if (!synthesized.renameTo(target)) {
                throw InstallPipelineException(InstallFailureCode.STORAGE, "Unable to finalize synthesized APK")
            }
        } finally {
            synthesized.delete()
        }
        patchTarget.delete()
    }

    private suspend fun isStagedComplete(target: File, size: Long, checksum: String): Boolean {
        if (size <= 0L || !target.isFile || target.length() != size) return false
        val digest = checksum.takeIf { it.isNotBlank() }?.let(::messageDigest) ?: return true
        streamThroughDigest(target, digest)
        return digestMatches(digest, checksum)
    }

    private suspend fun verifySynthesized(file: File, artifact: InstallArtifact) {
        if (artifact.size > 0L && file.length() != artifact.size) {
            throw InstallPipelineException(
                InstallFailureCode.INTEGRITY,
                "Incomplete artifact ${artifact.name}: ${file.length()}/${artifact.size}",
            )
        }
        val digest = artifact.checksum.takeIf { it.isNotBlank() }?.let(::messageDigest) ?: return
        streamThroughDigest(file, digest)
        if (!digestMatches(digest, artifact.checksum)) {
            throw InstallPipelineException(
                InstallFailureCode.INTEGRITY,
                "Checksum mismatch for ${artifact.name}",
            )
        }
    }

    private suspend fun streamThroughDigest(file: File, digest: MessageDigest) {
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (error: IOException) {
            throw InstallPipelineException(
                InstallFailureCode.STORAGE,
                error.message ?: "Unable to read staged artifact",
                error,
            )
        }
    }

    private suspend fun copyLocal(
        source: InstallSource.Local,
        outputs: List<OutputStream>,
        onBytes: suspend (Long) -> Unit,
    ) = try {
        packageStore.openInput(source.uri).use { input ->
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                outputs.forEach { it.write(buffer, 0, read) }
                onBytes(read.toLong())
            }
        }
    } catch (error: IOException) {
        throw InstallPipelineException(
            InstallFailureCode.STORAGE,
            error.message ?: "Unable to read saved package",
            error,
        )
    }

    private suspend fun copyFile(
        file: File,
        outputs: List<OutputStream>,
        onBytes: suspend (Long) -> Unit,
    ) = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                outputs.forEach { it.write(buffer, 0, read) }
                onBytes(read.toLong())
            }
        }
    } catch (error: IOException) {
        throw InstallPipelineException(
            InstallFailureCode.STORAGE,
            error.message ?: "Unable to stream staged artifact",
            error,
        )
    }

    private fun digestMatches(digest: MessageDigest, checksum: String): Boolean =
        digest.digest().joinToString("") { byte -> "%02x".format(byte) } == checksum.trim().lowercase()

    private fun messageDigest(checksum: String): MessageDigest = MessageDigest.getInstance(
        when (checksum.trim().length) {
            64 -> "SHA-256"
            40 -> "SHA-1"
            else -> "MD5"
        }
    )

    private companion object {
        const val IO_BUFFER_SIZE = 128 * 1024
    }
}
