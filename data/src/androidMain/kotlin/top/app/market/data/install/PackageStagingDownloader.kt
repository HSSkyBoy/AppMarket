package top.app.market.data.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import top.app.market.data.install.network.ArtifactSourceReader
import top.app.market.data.install.storage.ArtifactStagingStore
import top.app.market.domain.model.install.InstallFailureCode
import top.app.market.domain.model.install.InstallRequest
import top.app.market.domain.model.install.InstallSource
import java.io.File

/** 把请求的全部远端 artifact 断点续传暂存到本地，返回与 artifacts 对齐的暂存文件（本地源为 null）。 */
internal class PackageStagingDownloader(
    private val context: Context,
    private val sourceReader: ArtifactSourceReader,
    private val staging: ArtifactStagingStore,
) {
    suspend fun stage(
        request: InstallRequest,
        onProgress: suspend (completed: Long, total: Long) -> Unit,
    ): List<File?> {
        val remoteBytes = request.artifacts.filter { it.source !is InstallSource.Local }.sumOf { it.size }
        staging.ensureSpaceFor(request.id, remoteBytes)
        // 分母按 artifact 维护而非一次算死：增量降级为全量时对应项会从补丁大小切到整包大小
        val totals = LongArray(request.artifacts.size) { request.artifacts[it].transferSize }
        val transferred = LongArray(request.artifacts.size)
        var lastProgressMarker = -1L
        val staged = request.artifacts.mapIndexed { index, artifact ->
            if (artifact.source is InstallSource.Local) return@mapIndexed null
            val target = staging.artifactFile(request.id, index, artifact.name)
            sourceReader.stage(artifact, target, staging.patchFile(request.id, index)) { bytes, artifactTotal ->
                transferred[index] = bytes
                totals[index] = artifactTotal
                val completed = transferred.sum()
                val total = if (totals.all { it > 0L }) totals.sum() else -1L
                val marker = if (total > 0L) {
                    (completed.coerceAtMost(total) * 100L) / total
                } else {
                    completed / PROGRESS_STEP_BYTES
                }
                if (marker != lastProgressMarker) {
                    lastProgressMarker = marker
                    onProgress(completed, total)
                }
            }
            target
        }
        verifyBaseApk(request, staged)
        return staged
    }

    private fun verifyBaseApk(request: InstallRequest, staged: List<File?>) {
        val baseIndex = request.artifacts.indexOfFirst { it.name.equals("base.apk", ignoreCase = true) }
        if (baseIndex < 0) return
        val file = staged.getOrNull(baseIndex)?.takeIf(File::isFile) ?: return
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.path, 0)
        } ?: throw InstallPipelineException(
            InstallFailureCode.INTEGRITY,
            "Downloaded base APK cannot be parsed",
        )
        val actualVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        if (info.packageName != request.packageName) {
            throw InstallPipelineException(
                InstallFailureCode.INTEGRITY,
                "Downloaded package mismatch: expected ${request.packageName}, got ${info.packageName}",
            )
        }
        if (request.versionCode > 0L && actualVersion != request.versionCode) {
            throw InstallPipelineException(
                InstallFailureCode.INTEGRITY,
                "Downloaded version mismatch: expected ${request.versionCode}, got $actualVersion",
            )
        }
    }

    fun clear(taskId: String) = staging.clear(taskId)

    private companion object {
        const val PROGRESS_STEP_BYTES = 1024L * 1024L
    }
}
