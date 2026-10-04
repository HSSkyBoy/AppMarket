package top.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateStatus
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.MarketSourceRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import top.app.market.resources.Res
import top.app.market.resources.manual_update_required
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

@Immutable
data class ManualUpdateUiState(
    val packageName: String = "",
    val versionCode: String = "",
    val loading: Boolean = false,
    val errorMessage: String = "",
    val result: MarketAppInfo? = null,
    /** The server returned no downloadable APK for this version (shown as "服务器未开放该版本 APK"). */
    val noApk: Boolean = false,
    /** When [noApk], whether the server at least recognized the package (adds an explanatory line). */
    val recognizedNoUpdate: Boolean = false,
)

class ManualUpdateViewModel(
    private val sources: MarketSourceRepository,
    private val prefs: UpdatePreferencesRepository,
    private val downloads: DownloadRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualUpdateUiState())
    val uiState: StateFlow<ManualUpdateUiState> = _uiState.asStateFlow()
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states
    private val pendingDownloads = mutableSetOf<String>()

    fun setPackageName(value: String) = _uiState.update { it.copy(packageName = value.trim()) }
    fun setVersionCode(value: String) = _uiState.update { it.copy(versionCode = value.filter(Char::isDigit)) }

    fun check() {
        val state = _uiState.value
        if (state.loading) return
        val packageName = state.packageName.trim()
        val versionCode = state.versionCode.toLongOrNull()
        if (packageName.isBlank() || versionCode == null) {
            viewModelScope.launch {
                val message = getString(Res.string.manual_update_required)
                _uiState.update {
                    it.copy(errorMessage = message, result = null, noApk = false, recognizedNoUpdate = false)
                }
            }
            return
        }
        _uiState.update {
            it.copy(loading = true, errorMessage = "", result = null, noApk = false, recognizedNoUpdate = false)
        }
        viewModelScope.launch {
            runCatchingCancellable {
                sources.checkManualUpdate(
                    source = prefs.updateSource.value, request = ManualUpdateRequest(
                        packageName = packageName,
                        versionCode = versionCode,
                    )
                )
            }.onSuccess { result ->
                when (result.status) {
                    ManualUpdateStatus.UPDATE_AVAILABLE -> _uiState.update {
                        it.copy(loading = false, result = result.app, noApk = false, recognizedNoUpdate = false, errorMessage = "")
                    }
                    // No target version and no update item — say so plainly.
                    ManualUpdateStatus.RECOGNIZED_NO_UPDATE -> _uiState.update {
                        it.copy(loading = false, result = null, noApk = true, recognizedNoUpdate = true, errorMessage = "")
                    }

                    ManualUpdateStatus.NOT_FOUND -> _uiState.update {
                        it.copy(loading = false, result = null, noApk = true, recognizedNoUpdate = false, errorMessage = "")
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        errorMessage = error.message ?: "请求失败",
                        result = null,
                        noApk = false,
                        recognizedNoUpdate = false,
                    )
                }
            }
        }
    }

    fun download(app: MarketAppInfo) {
        if (!pendingDownloads.add(app.packageName)) return
        viewModelScope.launch {
            try {
                runCatchingCancellable {
                    sources.downloadUpdateMeta(app.source, app)
                }.onSuccess { downloads.start(it) }.onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "下载失败") }
                }
            } finally {
                pendingDownloads.remove(app.packageName)
            }
        }
    }

    fun installDownloaded(packageName: String) = downloads.install(packageName)
    fun cancelDownload(packageName: String) = downloads.cancel(packageName)
    fun clearDownload(packageName: String) = downloads.clear(packageName)
}
