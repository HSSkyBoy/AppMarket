package top.app.market.data.local.preferences

import top.app.market.data.local.BooleanPreferenceKey
import top.app.market.data.local.StringPreferenceKey

object ThemePreferenceKeys {
    private const val NS = "theme_preferences"
    val EnableBlur = BooleanPreferenceKey(NS, "enable_blur")
    val EnableFloatingBottomBar = BooleanPreferenceKey(NS, "enable_floating_bottom_bar")
    val EnableFloatingBottomBarBlur = BooleanPreferenceKey(NS, "enable_floating_bottom_bar_blur")
    val EnableNavigationBadge = BooleanPreferenceKey(NS, "enable_navigation_badge", true)
    val NavRailExpanded = BooleanPreferenceKey(NS, "nav_rail_expanded")
    val EnablePredictiveBack = BooleanPreferenceKey(NS, "enable_predictive_back")
    val PageScale = StringPreferenceKey(NS, "page_scale")
    val EnabledTabs = StringPreferenceKey(NS, "enabled_tabs")
    val AppLanguage = StringPreferenceKey(NS, "app_language")
    val ColorMode = StringPreferenceKey(NS, "color_mode")
    val ColorSource = StringPreferenceKey(NS, "color_source")
    val SeedColor = StringPreferenceKey(NS, "seed_color")
    val PaletteStyle = StringPreferenceKey(NS, "palette_style")
    val ColorSpec = StringPreferenceKey(NS, "color_spec")
    val AmoledDark = BooleanPreferenceKey(NS, "amoled_dark")
}
