package top.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.theme.ThemeColorMode
import top.app.market.domain.model.theme.ThemeColorSource
import top.app.market.domain.model.theme.ThemeColorSpec
import top.app.market.domain.model.theme.ThemePaletteStyle
import top.app.market.domain.repository.ThemePreferencesRepository
import kotlinx.coroutines.flow.Flow
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
    val colorMode: ThemeColorMode = ThemeColorMode.SYSTEM,
    val colorSource: ThemeColorSource = ThemeColorSource.DEFAULT,
    val seedColor: Long? = null,
    val paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TONAL_SPOT,
    val colorSpec: ThemeColorSpec = ThemeColorSpec.SPEC_2021,
    val amoledDark: Boolean = false,
)

class ThemeSettingsViewModel(
    private val preferences: ThemePreferencesRepository,
) : ViewModel() {
    private val appearance = combine(
        preferences.enableBlur,
        preferences.enableFloatingBottomBar,
        preferences.enableFloatingBottomBarBlur,
    ) { blur, floating, glass -> Triple(blur, floating, glass) }

    private val baseState: Flow<ThemeSettingsUiState> = combine(
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
    }

    private val colorState: Flow<ThemeSettingsUiState> = combine(
        preferences.colorMode,
        preferences.colorSource,
        preferences.seedColor,
        preferences.paletteStyle,
        preferences.colorSpec,
    ) { mode, source, seed, style, spec ->
        ThemeSettingsUiState(
            colorMode = mode,
            colorSource = source,
            seedColor = seed,
            paletteStyle = style,
            colorSpec = spec,
        )
    }

    val uiState: StateFlow<ThemeSettingsUiState> = combine(
        baseState,
        colorState,
        preferences.amoledDark,
    ) { base, color, amoled ->
        base.copy(
            colorMode = color.colorMode,
            colorSource = color.colorSource,
            seedColor = color.seedColor,
            paletteStyle = color.paletteStyle,
            colorSpec = color.colorSpec,
            amoledDark = amoled,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettingsUiState())

    fun setEnableBlur(value: Boolean) = persist { preferences.setEnableBlur(value) }
    fun setEnableFloatingBottomBar(value: Boolean) = persist { preferences.setEnableFloatingBottomBar(value) }
    fun setEnableFloatingBottomBarBlur(value: Boolean) = persist { preferences.setEnableFloatingBottomBarBlur(value) }
    fun setEnableNavigationBadge(value: Boolean) = persist { preferences.setEnableNavigationBadge(value) }
    fun setEnablePredictiveBack(value: Boolean) = persist { preferences.setEnablePredictiveBack(value) }
    fun setPageScale(value: Float) = persist { preferences.setPageScale(value) }
    fun setColorMode(value: ThemeColorMode) = persist { preferences.setColorMode(value) }
    fun setColorSource(value: ThemeColorSource) = persist { preferences.setColorSource(value) }
    fun setSeedColor(value: Long?) = persist { preferences.setSeedColor(value) }
    fun setPaletteStyle(value: ThemePaletteStyle) = persist { preferences.setPaletteStyle(value) }
    fun setColorSpec(value: ThemeColorSpec) = persist { preferences.setColorSpec(value) }
    fun setAmoledDark(value: Boolean) = persist { preferences.setAmoledDark(value) }

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
