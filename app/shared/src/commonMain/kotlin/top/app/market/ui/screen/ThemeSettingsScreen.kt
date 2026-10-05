package top.app.market.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.theme.ThemeColorMode
import top.app.market.domain.model.theme.ThemeColorSource
import top.app.market.domain.model.theme.ThemeColorSpec
import top.app.market.domain.model.theme.ThemePaletteStyle
import top.app.market.platform.UiPlatform
import top.app.market.platform.isBlurSettingSupported
import top.app.market.platform.isPredictiveBackSupported
import top.app.market.resources.Res
import top.app.market.resources.nav_apps
import top.app.market.resources.nav_games
import top.app.market.resources.nav_search
import top.app.market.resources.nav_today
import top.app.market.resources.nav_updates
import top.app.market.resources.theme
import top.app.market.resources.theme_color_source
import top.app.market.resources.theme_color_source_custom
import top.app.market.resources.theme_color_source_custom_summary
import top.app.market.resources.theme_color_source_default
import top.app.market.resources.theme_color_source_default_summary
import top.app.market.resources.theme_color_source_monet
import top.app.market.resources.theme_color_source_monet_summary
import top.app.market.resources.theme_custom_color
import top.app.market.resources.theme_amoled
import top.app.market.resources.theme_accent_color
import top.app.market.resources.theme_accent_default
import top.app.market.resources.theme_color_spec
import top.app.market.resources.theme_color_spec_2021
import top.app.market.resources.theme_color_spec_2025
import top.app.market.resources.theme_color_spec_unsupported
import top.app.market.resources.theme_dark_mode
import top.app.market.resources.theme_dark_mode_dark
import top.app.market.resources.theme_dark_mode_light
import top.app.market.resources.theme_dark_mode_system
import top.app.market.resources.theme_use_monet
import top.app.market.resources.theme_enable_blur
import top.app.market.resources.theme_enable_blur_summary
import top.app.market.resources.theme_enable_glass
import top.app.market.resources.theme_enable_glass_summary
import top.app.market.resources.theme_floating_bottom_bar
import top.app.market.resources.theme_floating_bottom_bar_summary
import top.app.market.resources.theme_navigation_badge
import top.app.market.resources.theme_navigation_badge_summary
import top.app.market.resources.theme_page_scale
import top.app.market.resources.theme_page_scale_summary
import top.app.market.resources.theme_palette_content
import top.app.market.resources.theme_palette_expressive
import top.app.market.resources.theme_palette_fidelity
import top.app.market.resources.theme_palette_fruit_salad
import top.app.market.resources.theme_palette_monochrome
import top.app.market.resources.theme_palette_neutral
import top.app.market.resources.theme_palette_rainbow
import top.app.market.resources.theme_palette_style
import top.app.market.resources.theme_palette_tonal_spot
import top.app.market.resources.theme_palette_vibrant
import top.app.market.resources.theme_predictive_back
import top.app.market.resources.theme_predictive_back_summary
import top.app.market.resources.theme_section_appearance
import top.app.market.resources.theme_section_interaction
import top.app.market.resources.theme_section_navigation
import top.app.market.resources.theme_tab_today_summary
import top.app.market.resources.theme_tab_games_summary
import top.app.market.resources.theme_tab_apps_summary
import top.app.market.resources.theme_tab_updates_summary
import top.app.market.resources.theme_tab_search_summary
import top.app.market.ui.component.CardSegmentContainer
import top.app.market.ui.component.ColorPickerDialog
import top.app.market.ui.component.MarketScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.component.ScaleDialog
import top.app.market.ui.component.SectionTitle
import top.app.market.viewmodel.ThemeSettingsUiState
import top.app.market.viewmodel.ThemeSettingsViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val DEFAULT_SEED_COLOR = 0xFF5B7CFFL

private data class NavigationTabOption(
    val key: String,
    val titleRes: StringResource,
    val summaryRes: StringResource,
)

@Composable
fun ThemeSettingsScreen(
    viewModel: ThemeSettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uiPlatform = koinInject<UiPlatform>()
    ThemeSettingsContent(
        state = state,
        onBack = onBack,
        onEnableBlur = viewModel::setEnableBlur,
        onEnableFloatingBottomBar = viewModel::setEnableFloatingBottomBar,
        onEnableFloatingBottomBarBlur = viewModel::setEnableFloatingBottomBarBlur,
        onEnableNavigationBadge = viewModel::setEnableNavigationBadge,
        onEnablePredictiveBack = viewModel::setEnablePredictiveBack,
        onPageScale = viewModel::setPageScale,
        onTabEnabled = viewModel::setTabEnabled,
        onColorMode = viewModel::setColorMode,
        onColorSource = viewModel::setColorSource,
        onSeedColor = viewModel::setSeedColor,
        onPaletteStyle = viewModel::setPaletteStyle,
        onColorSpec = viewModel::setColorSpec,
        onAmoledDark = viewModel::setAmoledDark,
        blurSupported = isBlurSettingSupported(),
        predictiveBackSupported = isPredictiveBackSupported(),
        appManagementSupported = uiPlatform.packageInstallationSupported,
        modifier = modifier,
    )
}

@Composable
private fun ThemeSettingsContent(
    state: ThemeSettingsUiState,
    onBack: () -> Unit,
    onEnableBlur: (Boolean) -> Unit,
    onEnableFloatingBottomBar: (Boolean) -> Unit,
    onEnableFloatingBottomBarBlur: (Boolean) -> Unit,
    onEnableNavigationBadge: (Boolean) -> Unit,
    onEnablePredictiveBack: (Boolean) -> Unit,
    onPageScale: (Float) -> Unit,
    onTabEnabled: (String, Boolean) -> Unit,
    onColorMode: (ThemeColorMode) -> Unit,
    onColorSource: (ThemeColorSource) -> Unit,
    onSeedColor: (Long?) -> Unit,
    onPaletteStyle: (ThemePaletteStyle) -> Unit,
    onColorSpec: (ThemeColorSpec) -> Unit,
    onAmoledDark: (Boolean) -> Unit,
    blurSupported: Boolean,
    predictiveBackSupported: Boolean,
    appManagementSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    var sliderValue by remember(state.pageScale) { mutableFloatStateOf(state.pageScale) }
    var showScaleDialog by rememberSaveable { mutableStateOf(false) }
    var showColorPicker by rememberSaveable { mutableStateOf(false) }

    val tabOptions = remember(appManagementSupported) {
        buildList {
            add(NavigationTabOption("today", Res.string.nav_today, Res.string.theme_tab_today_summary))
            add(NavigationTabOption("games", Res.string.nav_games, Res.string.theme_tab_games_summary))
            add(NavigationTabOption("apps", Res.string.nav_apps, Res.string.theme_tab_apps_summary))
            if (appManagementSupported) {
                add(NavigationTabOption("updates", Res.string.nav_updates, Res.string.theme_tab_updates_summary))
            }
            add(NavigationTabOption("search", Res.string.nav_search, Res.string.theme_tab_search_summary))
        }
    }

    MarketScaffold(
        title = stringResource(Res.string.theme),
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding, backdropModifier, scrollBehavior ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropModifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                top = innerPadding.calculateTopPadding() + PageVerticalPadding,
                bottom = innerPadding.calculateBottomPadding() + PageVerticalPadding,
            ),
        ) {
            item(key = "section-appearance") {
                SectionTitle(
                    text = stringResource(Res.string.theme_section_appearance),
                    topPadding = 0.dp,
                )
            }
            val monetEnabled = state.colorSource != ThemeColorSource.DEFAULT
            item(key = "dark-mode") {
                CardSegmentContainer(isFirst = true, isLast = false) {
                    OverlayDropdownPreference(
                        items = listOf(
                            stringResource(Res.string.theme_dark_mode_system),
                            stringResource(Res.string.theme_dark_mode_light),
                            stringResource(Res.string.theme_dark_mode_dark),
                        ),
                        selectedIndex = colorModeIndex(state.colorMode),
                        title = stringResource(Res.string.theme_dark_mode),
                        onSelectedIndexChange = { index ->
                            onColorMode(colorModeAt(index))
                        },
                    )
                }
            }
            item(key = "amoled") {
                CardSegmentContainer(isFirst = false, isLast = false) {
                    SwitchPreference(
                        title = stringResource(Res.string.theme_amoled),
                        checked = state.amoledDark,
                        onCheckedChange = onAmoledDark,
                    )
                }
            }
            item(key = "use-monet") {
                CardSegmentContainer(isFirst = false, isLast = false) {
                    SwitchPreference(
                        title = stringResource(Res.string.theme_use_monet),
                        checked = monetEnabled,
                        onCheckedChange = { on ->
                            onColorSource(
                                if (on) {
                                    if (state.seedColor != null) ThemeColorSource.CUSTOM else ThemeColorSource.MONET
                                } else {
                                    ThemeColorSource.DEFAULT
                                }
                            )
                        },
                    )
                }
            }
            if (monetEnabled) {
                item(key = "palette-style") {
                    CardSegmentContainer(isFirst = false, isLast = false) {
                        OverlayDropdownPreference(
                            items = ThemePaletteStyle.entries.map { stringResource(paletteStyleRes(it)) },
                            selectedIndex = ThemePaletteStyle.entries.indexOf(state.paletteStyle),
                            title = stringResource(Res.string.theme_palette_style),
                            startAction = { PaletteDots() },
                            onSelectedIndexChange = { index ->
                                onPaletteStyle(ThemePaletteStyle.entries[index])
                            },
                        )
                    }
                }
                item(key = "color-spec") {
                    CardSegmentContainer(isFirst = false, isLast = false) {
                        OverlayDropdownPreference(
                            items = listOf(
                                stringResource(Res.string.theme_color_spec_2021),
                                stringResource(Res.string.theme_color_spec_2025),
                            ),
                            selectedIndex = colorSpecIndex(state.colorSpec),
                            title = stringResource(Res.string.theme_color_spec),
                            summary = if (state.paletteStyle.supportsSpec2025) null else stringResource(Res.string.theme_color_spec_unsupported),
                            enabled = state.paletteStyle.supportsSpec2025,
                            onSelectedIndexChange = { index ->
                                onColorSpec(colorSpecAt(index))
                            },
                        )
                    }
                }
                item(key = "accent-color") {
                    val seedColor = state.seedColor
                    CardSegmentContainer(isFirst = false, isLast = false) {
                        ArrowPreference(
                            title = stringResource(Res.string.theme_accent_color),
                            startAction = { PaletteDots() },
                            endActions = {
                                Text(
                                    text = seedColor?.let { "#%06X".format((it and 0xFFFFFF).toInt()) }
                                        ?: stringResource(Res.string.theme_accent_default),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            },
                            onClick = { showColorPicker = true },
                        )
                    }
                }
            }
            if (blurSupported) {
                item(key = "blur") {
                    CardSegmentContainer(
                        isFirst = false,
                        isLast = false,
                    ) {
                        SwitchPreference(
                            title = stringResource(Res.string.theme_enable_blur),
                            summary = stringResource(Res.string.theme_enable_blur_summary),
                            checked = state.enableBlur,
                            onCheckedChange = onEnableBlur,
                        )
                    }
                }
            }
            item(key = "floating-bottom-bar") {
                CardSegmentContainer(
                    isFirst = false,
                    isLast = false,
                ) {
                    SwitchPreference(
                        title = stringResource(Res.string.theme_floating_bottom_bar),
                        summary = stringResource(Res.string.theme_floating_bottom_bar_summary),
                        checked = state.enableFloatingBottomBar,
                        onCheckedChange = onEnableFloatingBottomBar,
                    )
                }
            }
            if (blurSupported) {
                item(key = "floating-bottom-bar-glass") {
                    AnimatedVisibility(
                        visible = state.enableFloatingBottomBar,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        CardSegmentContainer(
                            isFirst = false,
                            isLast = false,
                        ) {
                            SwitchPreference(
                                title = stringResource(Res.string.theme_enable_glass),
                                summary = stringResource(Res.string.theme_enable_glass_summary),
                                checked = state.enableFloatingBottomBarBlur,
                                onCheckedChange = onEnableFloatingBottomBarBlur,
                            )
                        }
                    }
                }
            }
            item(key = "navigation-badge") {
                CardSegmentContainer(
                    isFirst = false,
                    isLast = true,
                ) {
                    SwitchPreference(
                        title = stringResource(Res.string.theme_navigation_badge),
                        summary = stringResource(Res.string.theme_navigation_badge_summary),
                        checked = state.enableNavigationBadge,
                        onCheckedChange = onEnableNavigationBadge,
                    )
                }
            }
            item(key = "section-navigation") {
                SectionTitle(
                    text = stringResource(Res.string.theme_section_navigation),
                )
            }
            items(
                count = tabOptions.size,
                key = { tabOptions[it].key },
            ) { index ->
                val option = tabOptions[index]
                val isChecked = option.key in state.enabledTabs
                val canToggleOff = state.enabledTabs.size > 1 || !isChecked
                CardSegmentContainer(
                    isFirst = index == 0,
                    isLast = index == tabOptions.lastIndex,
                ) {
                    SwitchPreference(
                        title = stringResource(option.titleRes),
                        summary = stringResource(option.summaryRes),
                        checked = isChecked,
                        enabled = canToggleOff,
                        onCheckedChange = { onTabEnabled(option.key, it) },
                    )
                }
            }

            item(key = "section-interaction") {
                SectionTitle(
                    text = stringResource(Res.string.theme_section_interaction),
                )
            }
            if (predictiveBackSupported) {
                item(key = "predictive-back") {
                    CardSegmentContainer(
                        isFirst = true,
                        isLast = false,
                    ) {
                        SwitchPreference(
                            title = stringResource(Res.string.theme_predictive_back),
                            summary = stringResource(Res.string.theme_predictive_back_summary),
                            checked = state.enablePredictiveBack,
                            onCheckedChange = onEnablePredictiveBack,
                        )
                    }
                }
            }
            item(key = "page-scale") {
                CardSegmentContainer(
                    isFirst = !predictiveBackSupported,
                    isLast = true,
                ) {
                    ArrowPreference(
                        title = stringResource(Res.string.theme_page_scale),
                        summary = stringResource(Res.string.theme_page_scale_summary),
                        endActions = { Text("${(sliderValue * 100).toInt()}%", color = MiuixTheme.colorScheme.onSurfaceVariantActions) },
                        onClick = { showScaleDialog = !showScaleDialog },
                        holdDownState = showScaleDialog,
                        bottomAction = {
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = { onPageScale(sliderValue) },
                                valueRange = 0.8f..1.1f,
                                showKeyPoints = true,
                                keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                                magnetThreshold = 0.01f,
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                            )
                        },
                    )
                }
            }
        }
        ScaleDialog(
            show = showScaleDialog,
            onDismissRequest = { showScaleDialog = false },
            scaleProvider = { state.pageScale },
            onScaleChange = onPageScale,
        )
        ColorPickerDialog(
            show = showColorPicker,
            initialColor = Color(state.seedColor ?: DEFAULT_SEED_COLOR),
            title = stringResource(Res.string.theme_custom_color),
            onDismissRequest = { showColorPicker = false },
            onConfirm = { color ->
                showColorPicker = false
                onSeedColor(color.toArgb().toLong() and 0xFFFFFFFFL)
            },
            onResetRequest = {
                showColorPicker = false
                onSeedColor(null)
            },
        )
    }
}

internal fun colorModeIndex(mode: ThemeColorMode): Int = ThemeColorMode.entries.indexOf(mode)

internal fun colorModeAt(index: Int): ThemeColorMode = ThemeColorMode.entries[index.coerceIn(0, ThemeColorMode.entries.lastIndex)]

internal fun colorSourceIndex(source: ThemeColorSource): Int = ThemeColorSource.entries.indexOf(source)

internal fun colorSourceAt(index: Int): ThemeColorSource = ThemeColorSource.entries[index.coerceIn(0, ThemeColorSource.entries.lastIndex)]

internal fun colorSourceSummaryRes(source: ThemeColorSource): StringResource = when (source) {
    ThemeColorSource.DEFAULT -> Res.string.theme_color_source_default_summary
    ThemeColorSource.MONET -> Res.string.theme_color_source_monet_summary
    ThemeColorSource.CUSTOM -> Res.string.theme_color_source_custom_summary
}

internal fun colorSpecIndex(spec: ThemeColorSpec): Int = ThemeColorSpec.entries.indexOf(spec)

internal fun colorSpecAt(index: Int): ThemeColorSpec =
    ThemeColorSpec.entries[index.coerceIn(0, ThemeColorSpec.entries.lastIndex)]

internal fun paletteStyleRes(style: ThemePaletteStyle): StringResource = when (style) {
    ThemePaletteStyle.TONAL_SPOT -> Res.string.theme_palette_tonal_spot
    ThemePaletteStyle.NEUTRAL -> Res.string.theme_palette_neutral
    ThemePaletteStyle.VIBRANT -> Res.string.theme_palette_vibrant
    ThemePaletteStyle.EXPRESSIVE -> Res.string.theme_palette_expressive
    ThemePaletteStyle.RAINBOW -> Res.string.theme_palette_rainbow
    ThemePaletteStyle.FRUIT_SALAD -> Res.string.theme_palette_fruit_salad
    ThemePaletteStyle.MONOCHROME -> Res.string.theme_palette_monochrome
    ThemePaletteStyle.FIDELITY -> Res.string.theme_palette_fidelity
    ThemePaletteStyle.CONTENT -> Res.string.theme_palette_content
}

@Composable
private fun PaletteDots(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            MiuixTheme.colorScheme.primary,
            MiuixTheme.colorScheme.secondary,
            MiuixTheme.colorScheme.tertiaryContainer,
        ).forEach { color ->
            Box(
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
        }
    }
}
