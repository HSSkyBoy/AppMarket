package top.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.MarketSourceRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import top.app.market.platform.UiPlatform
import top.app.market.resources.Res
import top.app.market.resources.download_failed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

class DownloadingAppsViewModel(
    private val sources: MarketSourceRepository,
    private val prefs: UpdatePreferencesRepository,
    private val downloads: DownloadRepository,
    private val uiPlatform: UiPlatform,
) : ViewModel() {

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states
    private val pendingResumes = mutableSetOf<String>()

    /** 任务记录里的下载地址可能已过期，恢复前重新拉取元数据；同版本任务会复用暂存数据续传。 */
    fun resume(state: DownloadState) {
        if (!pendingResumes.add(state.packageName)) return
        viewModelScope.launch {
            try {
                val source = state.source
                    ?: prefs.searchSources.value.firstOrNull()
                    ?: AppSource.Default.first()
                runCatchingCancellable {
                    sources.downloadMeta(source, state.toAppInfo(source), state.displayName)
                }
                    .onSuccess { downloads.start(it) }
                    .onFailure { error ->
                        uiPlatform.showToast(error.message ?: getString(Res.string.download_failed))
                    }
            } finally {
                pendingResumes.remove(state.packageName)
            }
        }
    }

    fun installDownloaded(packageName: String) = downloads.install(packageName)
    fun pause(packageName: String) = downloads.cancel(packageName)

    private fun DownloadState.toAppInfo(source: AppSource) = MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        publisherName = "",
        versionName = "",
        versionCode = 0L,
        icon = icon,
        apkSize = 0L,
        ratingScore = 0.0,
        source = source,
    )
}
