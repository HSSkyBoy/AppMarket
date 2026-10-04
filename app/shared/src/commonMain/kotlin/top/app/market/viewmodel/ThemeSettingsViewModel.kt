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
    val enabledTabs: Set<String> = setOf("today", "updates", "search"),
)

class ThemeSettingsViewModel(
    private val preferences: ThemePreferencesRepository,
) : ViewModel() {
    private val appearance = combine(
        preferences.enableBlur,
        preferences.enableFloatingBottomBar,
        preferences.enableFloatingBottomBarBlur,
    ) { blur, floating, glass -> Triple(blur, floating, glass) }

    val uiState: StateFlow<ThemeSettingsUiState> = combine(
        appearance,
        preferences.enableNavigationBadge,
        preferences.enablePredictiveBack,
        preferences.pageScale,
        preferences.enabledTabs,
    ) { (blur, floating, glass), badge, predictiveBack, scale, tabs ->
        ThemeSettingsUiState(
            enableBlur = blur,
            enableFloatingBottomBar = floating,
            enableFloatingBottomBarBlur = glass,
            enableNavigationBadge = badge,
            enablePredictiveBack = predictiveBack,
            pageScale = scale,
            enabledTabs = tabs,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettingsUiState())

    fun setEnableBlur(value: Boolean) = persist { preferences.setEnableBlur(value) }
    fun setEnableFloatingBottomBar(value: Boolean) = persist { preferences.setEnableFloatingBottomBar(value) }
    fun setEnableFloatingBottomBarBlur(value: Boolean) = persist { preferences.setEnableFloatingBottomBarBlur(value) }
    fun setEnableNavigationBadge(value: Boolean) = persist { preferences.setEnableNavigationBadge(value) }
    fun setEnablePredictiveBack(value: Boolean) = persist { preferences.setEnablePredictiveBack(value) }
    fun setPageScale(value: Float) = persist { preferences.setPageScale(value) }

    fun setTabEnabled(tabKey: String, enabled: Boolean) = persist {
        val current = preferences.enabledTabs.value
        if (!enabled && current.size <= 1 && current.contains(tabKey)) {
            return@persist
        }
        val updated = if (enabled) current + tabKey else current - tabKey
        preferences.setEnabledTabs(updated)
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
