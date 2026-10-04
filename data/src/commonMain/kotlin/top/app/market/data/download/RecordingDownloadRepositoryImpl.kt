package top.app.market.data.download

import top.app.market.data.platform.debugLog
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.update.UpdateHistoryEntry
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.PackageRepository
import top.app.market.domain.repository.UpdateHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 更新日志入库前的截断长度，控制单键 JSON 体积。 */
private const val MaxChangeLogLength = 500

/**
 * [DownloadRepository] 装饰器：在下载真正入队前暂存一条 pending 历史（旧版本名从设备实时读取），
 * 安装成功（[DownloadRepository.installedPackages] 发射）后写入 [UpdateHistoryRepository]。
 * cancel 不撤销 pending —— 底层取消是暂停语义，任务可从通知栏恢复安装；未安装的 pending 永不入历史。
 * clear 为真正移除，随之撤销 pending。
 */
internal class RecordingDownloadRepositoryImpl(
    private val delegate: PlatformDownloadDataSource,
    private val packages: PackageRepository,
    private val history: UpdateHistoryRepository,
    private val scope: CoroutineScope,
) : DownloadRepository by delegate {

    init {
        scope.launch {
            delegate.installedPackages.collect { packageName ->
                runCatching { history.commitPending(packageName) }
                    .onFailure { debugLog("UpdateHistory") { "commit $packageName failed: $it" } }
            }
        }
        scope.launch {
            runCatching { reconcilePending() }
                .onFailure { debugLog("UpdateHistory") { "reconcile pending failed: $it" } }
        }
    }

    override fun start(meta: DownloadMeta, installAfterDownload: Boolean) {
        if (!installAfterDownload) {
            delegate.start(meta, installAfterDownload = false)
            return
        }
        scope.launch {
            // 已有进行中的任务时 DownloadCenter 会拒绝本次请求（暂停任务除外——恢复时改用新 meta），
            // 此时保留原 pending 才与最终真正安装的版本一致。
            val existing = delegate.states.value[meta.packageName]
            if (existing == null || existing.isPaused) {
                val previous = runCatching { packages.installedVersionName(meta.packageName) }.getOrNull()
                runCatching {
                    history.markPending(
                        UpdateHistoryEntry(
                            appId = meta.appId,
                            packageName = meta.packageName,
                            displayName = meta.displayName,
                            versionName = meta.versionName,
                            versionCode = meta.versionCode,
                            previousVersionName = previous.orEmpty(),
                            icon = meta.icon,
                            changeLog = meta.changeLog.take(MaxChangeLogLength),
                            installedAt = nowEpochMillis(),
                            source = meta.source,
                        )
                    )
                }.onFailure { debugLog("UpdateHistory") { "mark ${meta.packageName} pending failed: $it" } }
            }
            // 在存储可用时，pending 先于 start 落盘；历史故障不阻断下载主流程。
            delegate.start(meta, installAfterDownload)
        }
    }

    override fun clear(packageName: String) {
        scope.launch {
            runCatching { history.removePending(packageName) }
                .onFailure { debugLog("UpdateHistory") { "remove $packageName pending failed: $it" } }
            delegate.clear(packageName)
        }
    }

    /** 进程死亡期间完成的安装（通知栏恢复、静默安装）没有发射可收；启动时按已装版本对账遗留 pending。 */
    private suspend fun reconcilePending() {
        val pending = history.pendingEntries()
        if (pending.isEmpty()) return
        val installed = packages.installedVersionCodes(pending.map { it.packageName })
        pending.forEach { entry ->
            val installedCode = installed[entry.packageName] ?: return@forEach
            when {
                installedCode == entry.versionCode ->
                    // 同版本重下载型 pending 无法区分「死进程期间真装了」与「从未安装」，宁可不记也不造假条目。
                    if (entry.previousVersionName == entry.versionName) {
                        history.removePending(entry.packageName)
                    } else {
                        history.commitPending(entry.packageName)
                    }

                installedCode > entry.versionCode -> history.removePending(entry.packageName)
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
