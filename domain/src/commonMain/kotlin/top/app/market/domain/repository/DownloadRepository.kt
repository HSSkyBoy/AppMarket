package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.download.DownloadTaskKey
import top.app.market.domain.model.install.DeltaFallback
import top.app.market.domain.model.install.InstallUserAction
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface DownloadRepository {
    val states: StateFlow<Map<String, DownloadState>>
    val taskStates: StateFlow<Map<DownloadTaskKey, DownloadState>>
    val installedPackages: SharedFlow<String>
    val deltaFallbacks: SharedFlow<DeltaFallback>
    val pendingUserAction: StateFlow<InstallUserAction?>
    fun start(meta: DownloadMeta, installAfterDownload: Boolean = true)
    fun install(packageName: String)
    fun cancel(packageName: String)
    fun cancel(packageName: String, versionCode: Long)
    fun clear(packageName: String)
    fun consumePendingUserAction()
}
