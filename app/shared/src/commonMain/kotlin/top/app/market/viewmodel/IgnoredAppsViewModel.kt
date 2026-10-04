package top.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.update.IgnoredUpdate
import top.app.market.domain.repository.UpdatePreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class IgnoredAppsUiState(
    val permanent: List<IgnoredUpdate> = emptyList(),
    val once: List<IgnoredUpdate> = emptyList(),
)

class IgnoredAppsViewModel(
    private val prefs: UpdatePreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<IgnoredAppsUiState> =
        combine(prefs.permanentIgnores, prefs.onceIgnores) { permanent, once ->
            IgnoredAppsUiState(permanent = permanent, once = once)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IgnoredAppsUiState())

    fun removePermanent(packageName: String) {
        viewModelScope.launch { prefs.removePermanent(packageName) }
    }

    fun removeOnce(packageName: String) {
        viewModelScope.launch { prefs.removeOnce(packageName) }
    }
}
