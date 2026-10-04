package top.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.repository.ThemePreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ThemeSettingsUiState(
    val enableBlur: Boolean = false,
    val enableFloatingBottomBar: Boolean = false,
    val enableFloatingBottomBarBlur: Boolean = false,
    val enableNavigationBadge: Boolean = true,
    val enablePredictiveBack: Boolean = false,
    val pageScale: Float = 1f,
)

class ThemeSettingsViewModel(
    private val preferences: ThemePreferencesRepository,
) : ViewModel() {
    private val appearance = combine(preferences.enableBlur, preferences.enableFloatingBottomBar) { blur, floating ->
        blur to floating
    }

    val uiState: StateFlow<ThemeSettingsUiState> = combine(
        appearance,
        preferences.enableFloatingBottomBarBlur,
        preferences.enableNavigationBadge,
        preferences.enablePredictiveBack,
        preferences.pageScale,
    ) { appearance, glass, badge, predictiveBack, scale ->
        ThemeSettingsUiState(
            enableBlur = appearance.first,
            enableFloatingBottomBar = appearance.second,
            enableFloatingBottomBarBlur = glass,
            enableNavigationBadge = badge,
            enablePredictiveBack = predictiveBack,
            pageScale = scale,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettingsUiState())

    fun setEnableBlur(value: Boolean) = persist { preferences.setEnableBlur(value) }
    fun setEnableFloatingBottomBar(value: Boolean) = persist { preferences.setEnableFloatingBottomBar(value) }
    fun setEnableFloatingBottomBarBlur(value: Boolean) = persist { preferences.setEnableFloatingBottomBarBlur(value) }
    fun setEnableNavigationBadge(value: Boolean) = persist { preferences.setEnableNavigationBadge(value) }
    fun setEnablePredictiveBack(value: Boolean) = persist { preferences.setEnablePredictiveBack(value) }
    fun setPageScale(value: Float) = persist { preferences.setPageScale(value) }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
