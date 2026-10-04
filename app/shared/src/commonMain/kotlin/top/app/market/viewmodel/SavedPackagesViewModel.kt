package top.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.download.DownloadPhase
import top.app.market.domain.model.installer.SavedPackage
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.SavedPackageRepository
import top.app.market.platform.UiPlatform
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SavedPackagesUiState(
    val loading: Boolean = false,
    val packages: List<SavedPackage> = emptyList(),
    val installingPackage: SavedPackage? = null,
    val installedPackage: SavedPackage? = null,
)

class SavedPackagesViewModel(
    private val service: SavedPackageRepository,
    private val downloads: DownloadRepository,
    private val uiPlatform: UiPlatform,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SavedPackagesUiState(loading = true))
    val uiState: StateFlow<SavedPackagesUiState> = _uiState.asStateFlow()
    private var installRequestJob: Job? = null

    init {
        refresh()
        observeInstallState()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val packages = runCatchingCancellable { service.list() }
                .onFailure { uiPlatform.showToast(it.message ?: "Load failed") }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(loading = false, packages = packages) }
        }
    }

    fun install(pkg: SavedPackage) {
        if (_uiState.value.installingPackage != null) return
        _uiState.update {
            it.copy(
                installingPackage = pkg,
                installedPackage = null,
            )
        }
        installRequestJob = viewModelScope.launch {
            runCatchingCancellable { service.install(pkg.id) }
                .onFailure { clearInstallingPackage(pkg.id) }
        }
    }

    fun cancelInstall() {
        val pkg = _uiState.value.installingPackage ?: return
        clearInstallingPackage(pkg.id)
        installRequestJob?.cancel()
        installRequestJob = null
        downloads.cancel(pkg.packageName)
    }

    fun dismissInstallSuccess() {
        _uiState.update { it.copy(installedPackage = null) }
    }

    fun delete(pkg: SavedPackage) {
        viewModelScope.launch {
            runCatchingCancellable { service.delete(pkg.id) }
                .onSuccess { refresh() }
                .onFailure {
                    uiPlatform.showToast(it.message ?: "Delete failed")
                    refresh()
                }
        }
    }

    private fun observeInstallState() {
        viewModelScope.launch {
            downloads.installedPackages.collect { packageName ->
                val target = _uiState.value.installingPackage
                    ?.takeIf { it.packageName == packageName }
                    ?: return@collect
                _uiState.update { state ->
                    if (state.installingPackage?.id == target.id) {
                        state.copy(
                            installingPackage = null,
                            installedPackage = target,
                        )
                    } else {
                        state
                    }
                }
                runCatchingCancellable { service.list() }
                    .onSuccess { packages ->
                        _uiState.update { it.copy(packages = packages) }
                    }
            }
        }
        viewModelScope.launch {
            downloads.states.collect { states ->
                val installing = _uiState.value.installingPackage ?: return@collect
                val installState = states[installing.packageName] ?: return@collect
                val stopped = installState.phase == DownloadPhase.FAILED ||
                        (installState.phase == DownloadPhase.DOWNLOADED && installState.errorMessage.isNotBlank())
                if (stopped) clearInstallingPackage(installing.id)
            }
        }
    }

    private fun clearInstallingPackage(id: String) {
        _uiState.update { state ->
            if (state.installingPackage?.id == id) {
                state.copy(installingPackage = null)
            } else {
                state
            }
        }
    }
}
