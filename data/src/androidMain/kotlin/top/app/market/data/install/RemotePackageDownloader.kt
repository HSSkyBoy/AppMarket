package top.app.market.data.install

import top.app.market.data.install.network.ArtifactSourceReader
import top.app.market.data.install.storage.MediaStorePackageStore
import top.app.market.domain.model.install.InstallEvent
import top.app.market.domain.model.install.InstallRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class RemotePackageDownloader(
    private val stagingDownloader: PackageStagingDownloader,
    private val sourceReader: ArtifactSourceReader,
    private val packageStore: MediaStorePackageStore,
) {
    fun download(request: InstallRequest): Flow<InstallEvent> = channelFlow {
        send(InstallEvent.Preparing)
        // 第一阶段：断点续传暂存；取消保留已下载数据，恢复后继续传
        val staged = stagingDownloader.stage(request) { completed, total ->
            send(InstallEvent.Progress(completed, total))
        }
        // 第二阶段：暂存完整后落位到公共下载目录；取消只回滚 MediaStore 条目，暂存保留
        var pending: MediaStorePackageStore.PendingSavedPackage? = null
        var published = false
        try {
            pending = packageStore.createPending(request)
            request.artifacts.forEachIndexed { index, artifact ->
                pending.openOutput(index).use { output ->
                    sourceReader.copyTo(artifact, staged[index], listOf(output))
                    output.flush()
                }
            }
            val saved = pending.publish()
            published = true
            stagingDownloader.clear(request.id)
            send(InstallEvent.Downloaded(saved.id))
        } finally {
            withContext(NonCancellable) {
                if (!published) runCatching { pending?.rollback() }
            }
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        val failure = error.toInstallFailure()
        emit(InstallEvent.Failed(failure.code, failure.message ?: "Download failed"))
    }.flowOn(Dispatchers.IO)
}
