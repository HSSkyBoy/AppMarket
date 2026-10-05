package top.app.market.domain.repository

import kotlinx.coroutines.flow.StateFlow
import top.app.market.domain.model.theme.ThemeColorMode
import top.app.market.domain.model.theme.ThemeColorSource
import top.app.market.domain.model.theme.ThemeColorSpec
import top.app.market.domain.model.theme.ThemePaletteStyle

/** Persisted appearance and navigation preferences shared by all UI targets. */
interface ThemePreferencesRepository {
    val initialized: StateFlow<Boolean>
    val enableBlur: StateFlow<Boolean>
    val enableFloatingBottomBar: StateFlow<Boolean>
    val enableFloatingBottomBarBlur: StateFlow<Boolean>
    val enableNavigationBadge: StateFlow<Boolean>
    val navRailExpanded: StateFlow<Boolean>
    val enablePredictiveBack: StateFlow<Boolean>
    val pageScale: StateFlow<Float>
    val enabledTabs: StateFlow<Set<String>>
    val appLanguage: StateFlow<String?>
    val colorMode: StateFlow<ThemeColorMode>

    val colorSource: StateFlow<ThemeColorSource>

    val seedColor: StateFlow<Long?>
    val paletteStyle: StateFlow<ThemePaletteStyle>
    val colorSpec: StateFlow<ThemeColorSpec>

    val amoledDark: StateFlow<Boolean>

    suspend fun setEnableBlur(value: Boolean)
    suspend fun setEnableFloatingBottomBar(value: Boolean)
    suspend fun setEnableFloatingBottomBarBlur(value: Boolean)
    suspend fun setEnableNavigationBadge(value: Boolean)
    suspend fun setNavRailExpanded(value: Boolean)
    suspend fun setEnablePredictiveBack(value: Boolean)
    suspend fun setPageScale(value: Float)
    suspend fun setEnabledTabs(value: Set<String>)
    suspend fun setAppLanguage(value: String?)
    suspend fun setColorMode(value: ThemeColorMode)
    suspend fun setColorSource(value: ThemeColorSource)
    suspend fun setSeedColor(value: Long?)
    suspend fun setPaletteStyle(value: ThemePaletteStyle)
    suspend fun setColorSpec(value: ThemeColorSpec)
    suspend fun setAmoledDark(value: Boolean)
}
