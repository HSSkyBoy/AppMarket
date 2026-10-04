package top.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.download.DownloadTaskKey
import top.app.market.domain.model.market.HistoricalVersion
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.WandoujiaRepository
import top.app.market.platform.UiPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class HistoricalVersionsUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val errorMessage: String = "",
    val items: List<HistoricalVersion> = emptyList(),
    val nextOffset: Int? = 0,
) {
    val hasMore: Boolean get() = nextOffset != null
}

class HistoricalVersionsViewModel(
    private val wandoujia: WandoujiaRepository,
    private val downloads: DownloadRepository,
    private val uiPlatform: UiPlatform,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoricalVersionsUiState())
    val uiState: StateFlow<HistoricalVersionsUiState> = _uiState.asStateFlow()
    val downloadStates: StateFlow<Map<DownloadTaskKey, DownloadState>> = downloads.taskStates

    private var loadedKey: Pair<Long, String>? = null

    fun load(appId: Long, packageName: String) {
        val key = appId to packageName
        if (loadedKey == key || _uiState.value.loading && loadedKey != null) return
        loadedKey = key
        _uiState.value = HistoricalVersionsUiState()
        viewModelScope.launch {
            runCatchingCancellable { wandoujia.historicalVersions(appId, packageName, offset = 0) }
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            items = page.items,
                            nextOffset = page.nextOffset,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(loading = false, errorMessage = error.message ?: "History request failed")
                    }
                }
        }
    }

    fun loadMore(appId: Long, packageName: String) {
        val offset = _uiState.value.nextOffset ?: return
        if (_uiState.value.loading || _uiState.value.loadingMore) return
        _uiState.update { it.copy(loadingMore = true, errorMessage = "") }
        viewModelScope.launch {
            runCatchingCancellable { wandoujia.historicalVersions(appId, packageName, offset) }
                .onSuccess { page ->
                    _uiState.update { state ->
                        val known = state.items.asSequence().map { it.versionId }.toHashSet()
                        state.copy(
                            loadingMore = false,
                            items = state.items + page.items.filterNot { it.versionId in known },
                            nextOffset = page.nextOffset,
                            errorMessage = "",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loadingMore = false,
                            errorMessage = error.message ?: "History request failed",
                        )
                    }
                }
        }
    }

    fun retry(appId: Long, packageName: String) {
        if (_uiState.value.loading || _uiState.value.loadingMore) return
        if (_uiState.value.items.isEmpty()) {
            loadedKey = null
            load(appId, packageName)
        } else {
            loadMore(appId, packageName)
        }
    }

    fun download(version: HistoricalVersion) {
        runCatching { wandoujia.historicalDownloadMeta(version) }
            .onSuccess { downloads.start(it, installAfterDownload = false) }
            .onFailure { uiPlatform.showToast(it.message ?: "Download failed") }
    }

    fun pause(version: HistoricalVersion) {
        downloads.cancel(version.packageName, version.versionCode)
    }
}
