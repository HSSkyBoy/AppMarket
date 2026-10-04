package top.app.market.data.repository

import top.app.market.data.download.PlatformDownloadDataSource
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPhase
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.download.DownloadTaskKey
import top.app.market.domain.model.download.taskKey
import top.app.market.domain.model.install.DeltaFallback
import top.app.market.domain.model.install.InstallUserAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class DesktopDownloadDataSource(
    private val downloader: DesktopPackageDownloader,
    private val scope: CoroutineScope,
) : PlatformDownloadDataSource {
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()
    private val mutableTaskStates = MutableStateFlow<Map<DownloadTaskKey, DownloadState>>(emptyMap())
    override val taskStates: StateFlow<Map<DownloadTaskKey, DownloadState>> = mutableTaskStates.asStateFlow()

    private val mutableInstalledPackages = MutableSharedFlow<String>()
    override val installedPackages: SharedFlow<String> = mutableInstalledPackages.asSharedFlow()
    override val deltaFallbacks: SharedFlow<DeltaFallback> = MutableSharedFlow<DeltaFallback>().asSharedFlow()

    override val pendingUserAction: StateFlow<InstallUserAction?> = MutableStateFlow(null)
    private val activeJobs = ConcurrentHashMap<DownloadTaskKey, Job>()

    override fun start(meta: DownloadMeta, installAfterDownload: Boolean) {
        val packageName = meta.packageName
        val taskKey = meta.taskKey
        val initialState = DownloadState(
            appId = meta.appId,
            packageName = packageName,
            displayName = meta.displayName,
            progress = initialProgress(meta),
            phase = DownloadPhase.QUEUED,
            versionName = meta.versionName,
            versionCode = meta.versionCode,
            source = meta.source,
        )
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext.job
            // 接管：暂停后立刻恢复时旧任务可能还握着暂存文件写句柄，等它完全退出再动文件
            while (true) {
                val previous = activeJobs.putIfAbsent(taskKey, currentJob) ?: break
                if (previous === currentJob) break
                previous.cancel()
                previous.join()
                activeJobs.remove(taskKey, previous)
            }
            try {
                updateIfActive(taskKey, currentJob) {
                    it.copy(phase = DownloadPhase.DOWNLOADING, errorMessage = "")
                }
                var lastProgress = initialState.progress
                val directory = downloader.download(meta) { completed, total ->
                    val progress = total.takeIf { it > 0L }
                        ?.let { ((completed.coerceAtMost(it) * 100L) / it).toInt() }
                    if (progress != lastProgress) {
                        lastProgress = progress
                        updateIfActive(taskKey, currentJob) { state ->
                            state.copy(progress = progress, phase = DownloadPhase.DOWNLOADING)
                        }
                    }
                }
                coroutineContext.ensureActive()
                updateIfActive(taskKey, currentJob) {
                    it.copy(progress = 100, phase = DownloadPhase.DOWNLOADED, errorMessage = "")
                }
                if (activeJobs[taskKey] === currentJob) openDirectory(directory)
            } catch (error: CancellationException) {
                // 终态由协程自己在不可取消上下文里落，cancel() 只发信号——两边都写会让状态被覆盖回下载中
                withContext(NonCancellable) {
                    updateIfActive(taskKey, currentJob) {
                        it.copy(phase = DownloadPhase.PAUSED, errorMessage = "")
                    }
                }
                throw error
            } catch (error: Throwable) {
                updateIfActive(taskKey, currentJob) {
                    it.copy(
                        phase = DownloadPhase.FAILED,
                        errorMessage = error.message ?: "Download failed",
                    )
                }
            } finally {
                activeJobs.remove(taskKey, currentJob)
            }
        }
        publishState(initialState)
        job.start()
    }

    override fun install(packageName: String) = Unit

    override fun cancel(packageName: String) {
        val currentKey = mutableStates.value[packageName]?.taskKey
        val job = currentKey?.let(activeJobs::get)
            ?: activeJobs.entries.firstOrNull { it.key.packageName == packageName }?.value
        job?.cancel()
    }

    override fun cancel(packageName: String, versionCode: Long) {
        activeJobs[DownloadTaskKey(packageName, versionCode)]?.cancel()
    }

    override fun clear(packageName: String) {
        val jobs = activeJobs.entries
            .filter { it.key.packageName == packageName }
            .map { it.value }
        jobs.forEach(Job::cancel)
        mutableStates.update { it - packageName }
        mutableTaskStates.update { states -> states.filterKeys { it.packageName != packageName } }
        // 用户明确删除了任务，暂存的半成品也要一并清掉，否则最长会占盘两周。
        // 等旧任务退出后再判一次有无接管者——否则会删掉用户"取消后立刻重下"新建的暂存目录。
        scope.launch(Dispatchers.IO) {
            jobs.forEach { it.join() }
            if (activeJobs.keys.none { it.packageName == packageName }) downloader.discardStaging(packageName)
        }
    }

    override fun consumePendingUserAction() = Unit

    private fun updateIfActive(
        taskKey: DownloadTaskKey,
        job: Job,
        transform: (DownloadState) -> DownloadState,
    ) {
        if (activeJobs[taskKey] !== job) return
        var updatedState: DownloadState? = null
        mutableTaskStates.update { current ->
            val state = current[taskKey] ?: return@update current
            transform(state).also { updatedState = it }.let { current + (taskKey to it) }
        }
        val updated = updatedState ?: return
        mutableStates.update { current ->
            val packageState = current[taskKey.packageName] ?: return@update current
            if (packageState.taskKey != taskKey) current
            else current + (taskKey.packageName to updated)
        }
    }

    private fun publishState(state: DownloadState) {
        mutableTaskStates.update { it + (state.taskKey to state) }
        mutableStates.update { it + (state.packageName to state) }
    }
}

private fun openDirectory(directory: Path) {
    runCatching {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.OPEN)) desktop.open(directory.toFile())
    }
}
