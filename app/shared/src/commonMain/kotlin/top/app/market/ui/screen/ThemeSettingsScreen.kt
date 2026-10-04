package top.app.market.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.platform.UiPlatform
import top.app.market.platform.isBlurSettingSupported
import top.app.market.platform.isPredictiveBackSupported
import top.app.market.resources.Res
import top.app.market.resources.nav_search
import top.app.market.resources.nav_today
import top.app.market.resources.nav_updates
import top.app.market.resources.theme
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
import top.app.market.resources.theme_predictive_back
import top.app.market.resources.theme_predictive_back_summary
import top.app.market.resources.theme_section_appearance
import top.app.market.resources.theme_section_interaction
import top.app.market.resources.theme_section_navigation
import top.app.market.resources.theme_tab_today_summary
import top.app.market.resources.theme_tab_updates_summary
import top.app.market.resources.theme_tab_search_summary
import top.app.market.ui.component.CardSegmentContainer
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
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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
    blurSupported: Boolean,
    predictiveBackSupported: Boolean,
    appManagementSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    var sliderValue by remember(state.pageScale) { mutableFloatStateOf(state.pageScale) }
    var showScaleDialog by rememberSaveable { mutableStateOf(false) }

    val tabOptions = remember(appManagementSupported) {
        buildList {
            add(NavigationTabOption("today", Res.string.nav_today, Res.string.theme_tab_today_summary))
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
            if (blurSupported) {
                item(key = "blur") {
                    CardSegmentContainer(
                        isFirst = true,
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
                    isFirst = !blurSupported,
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
    }
}
