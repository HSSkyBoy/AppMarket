package top.app.market.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject
import top.app.market.domain.model.installer.InstallerMode
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.theme.ThemeColorMode
import top.app.market.domain.model.theme.ThemeColorSource
import top.app.market.domain.model.theme.ThemeColorSpec
import top.app.market.domain.model.theme.ThemePaletteStyle
import top.app.market.domain.model.preference.HomePage
import top.app.market.domain.repository.ThemePreferencesRepository
import top.app.market.platform.isBlurSettingSupported
import top.app.market.platform.isPredictiveBackSupported
import top.app.market.resources.Res
import top.app.market.resources.about
import top.app.market.resources.about_summary
import top.app.market.resources.app_detail
import top.app.market.resources.device_profile
import top.app.market.resources.device_profile_summary
import top.app.market.resources.downloading_apps
import top.app.market.resources.downloading_apps_summary
import top.app.market.resources.filter_quick_games
import top.app.market.resources.filter_quick_games_summary
import top.app.market.resources.filter_reservation_apps
import top.app.market.resources.filter_reservation_apps_summary
import top.app.market.resources.category_source
import top.app.market.resources.category_source_summary
import top.app.market.resources.focus_notification_optimization
import top.app.market.resources.general
import top.app.market.resources.home_page
import top.app.market.resources.home_page_summary
import top.app.market.resources.ignored_apps
import top.app.market.resources.ignored_apps_summary
import top.app.market.resources.installer_delta_fallback_notice
import top.app.market.resources.installer_delta_fallback_notice_summary
import top.app.market.resources.installer_delta_update
import top.app.market.resources.installer_delta_update_summary
import top.app.market.resources.installer_section
import top.app.market.resources.installer_auto_launch_confirm
import top.app.market.resources.installer_auto_launch_confirm_summary
import top.app.market.resources.installer_mode_default
import top.app.market.resources.installer_mode_root
import top.app.market.resources.installer_mode_shizuku
import top.app.market.resources.installer_mode_third_party
import top.app.market.resources.installer_mode_third_party_summary
import top.app.market.resources.installer_no_user_action
import top.app.market.resources.installer_no_user_action_summary
import top.app.market.resources.installer_save_to_downloads
import top.app.market.resources.installer_save_to_downloads_summary
import top.app.market.resources.installer_section_summary
import top.app.market.resources.language
import top.app.market.resources.language_en
import top.app.market.resources.language_summary
import top.app.market.resources.language_system
import top.app.market.resources.language_zh_cn
import top.app.market.resources.language_zh_hk
import top.app.market.resources.language_zh_tw
import top.app.market.resources.manual_update
import top.app.market.resources.manual_update_summary
import top.app.market.resources.nav_search
import top.app.market.resources.nav_settings
import top.app.market.resources.nav_today
import top.app.market.resources.nav_updates
import top.app.market.resources.remove_search_ads
import top.app.market.resources.remove_search_ads_summary
import top.app.market.resources.saved_packages
import top.app.market.resources.saved_packages_summary
import top.app.market.resources.search_sources
import top.app.market.resources.search_sources_summary
import top.app.market.resources.settings_search
import top.app.market.resources.settings_search_no_results
import top.app.market.resources.settings_section_download_install
import top.app.market.resources.show_app_comments
import top.app.market.resources.show_app_comments_summary
import top.app.market.resources.clipboard_link_detect
import top.app.market.resources.clipboard_link_detect_summary
import top.app.market.resources.show_promotions
import top.app.market.resources.show_promotions_summary
import top.app.market.resources.show_same_developer
import top.app.market.resources.show_same_developer_summary
import top.app.market.resources.show_system_apps
import top.app.market.resources.show_system_apps_summary
import top.app.market.resources.strip_name_subtitle
import top.app.market.resources.strip_name_subtitle_summary
import top.app.market.resources.theme_color_source
import top.app.market.resources.theme_color_source_custom
import top.app.market.resources.theme_color_source_default
import top.app.market.resources.theme_color_source_default_summary
import top.app.market.resources.theme_color_source_monet
import top.app.market.resources.theme_color_source_monet_summary
import top.app.market.resources.theme_color_source_custom_summary
import top.app.market.resources.theme_amoled
import top.app.market.resources.theme_accent_color
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
import top.app.market.resources.theme
import top.app.market.resources.theme_summary
import top.app.market.resources.category_source
import top.app.market.resources.category_source_summary
import top.app.market.resources.today_source
import top.app.market.resources.today_source_summary
import top.app.market.resources.update_history
import top.app.market.resources.update_history_summary
import top.app.market.resources.update_source
import top.app.market.resources.update_source_summary
import top.app.market.resources.xiaomi_island_optimization
import top.app.market.resources.xiaomi_island_optimization_summary
import top.app.market.ui.component.CardSegmentContainer
import top.app.market.ui.component.MainTabScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.component.SectionTitle
import top.app.market.ui.util.appSourceLabel
import top.app.market.viewmodel.InstallerSettingsViewModel
import top.app.market.viewmodel.InstallerSettingsUiState
import top.app.market.viewmodel.UpdatesUiState
import top.app.market.viewmodel.UpdatesViewModel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SettingsTab(
    updatesViewModel: UpdatesViewModel,
    installerSettingsViewModel: InstallerSettingsViewModel,
    bottomPadding: Dp,
    appManagementSupported: Boolean = true,
    onNavigateDeviceProfile: () -> Unit,
    onNavigateInstaller: () -> Unit,
    onNavigateIgnored: () -> Unit,
    onNavigateManualUpdate: () -> Unit,
    onNavigateUpdateHistory: () -> Unit,
    onNavigateSavedPackages: () -> Unit,
    onNavigateDownloadingApps: () -> Unit,
    onNavigateAbout: () -> Unit,
    onNavigateTheme: () -> Unit,
) {
    val themePreferences = koinInject<ThemePreferencesRepository>()
    val enabledTabs by themePreferences.enabledTabs.collectAsStateWithLifecycle()
    val appLanguage by themePreferences.appLanguage.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val updatesState by updatesViewModel.uiState.collectAsStateWithLifecycle()
    val installerState by installerSettingsViewModel.uiState.collectAsStateWithLifecycle()
    val homePage by updatesViewModel.homePage.collectAsStateWithLifecycle()
    val searchSources by updatesViewModel.searchSources.collectAsStateWithLifecycle()
    val todaySource by updatesViewModel.todaySource.collectAsStateWithLifecycle()
    val categorySource by updatesViewModel.categorySource.collectAsStateWithLifecycle()
    val showAppComments by updatesViewModel.showAppComments.collectAsStateWithLifecycle()
    val showSameDeveloper by updatesViewModel.showSameDeveloper.collectAsStateWithLifecycle()
    val showPromotions by updatesViewModel.showPromotions.collectAsStateWithLifecycle()
    val stripAppNameSubtitle by updatesViewModel.stripAppNameSubtitle.collectAsStateWithLifecycle()
    val detectClipboardLinks by updatesViewModel.detectClipboardLinks.collectAsStateWithLifecycle()
    val themeColorMode by themePreferences.colorMode.collectAsStateWithLifecycle()
    val themeColorSource by themePreferences.colorSource.collectAsStateWithLifecycle()
    val themePaletteStyle by themePreferences.paletteStyle.collectAsStateWithLifecycle()
    val enableBlur by themePreferences.enableBlur.collectAsStateWithLifecycle()
    val enableFloatingBottomBar by themePreferences.enableFloatingBottomBar.collectAsStateWithLifecycle()
    val enableFloatingBottomBarBlur by themePreferences.enableFloatingBottomBarBlur.collectAsStateWithLifecycle()
    val enableNavigationBadge by themePreferences.enableNavigationBadge.collectAsStateWithLifecycle()
    val enablePredictiveBack by themePreferences.enablePredictiveBack.collectAsStateWithLifecycle()
    val themeColorSpec by themePreferences.colorSpec.collectAsStateWithLifecycle()
    val amoledDark by themePreferences.amoledDark.collectAsStateWithLifecycle()
    val themeSeedColor by themePreferences.seedColor.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedSource = AppSource.entries.firstOrNull { it in searchSources } ?: AppSource.Default.first()
    val selectedUpdateSource by updatesViewModel.updateSource.collectAsStateWithLifecycle()
    val searchCapabilities = selectedSource.capabilities
    val showSearchFilters = searchCapabilities.supportsSearchAdsFilter ||
            searchCapabilities.supportsQuickAppFilter ||
            searchCapabilities.supportsReservationFilter
    val showDetailOptions = searchCapabilities.supportsPromotions ||
            searchCapabilities.supportsComments ||
            searchCapabilities.supportsSameDeveloperApps

    val searchEntries = settingsSearchEntries(
        appManagementSupported = appManagementSupported,
        enabledTabs = enabledTabs,
        updatesViewModel = updatesViewModel,
        updatesState = updatesState,
        installerSettingsViewModel = installerSettingsViewModel,
        themePreferences = themePreferences,
        coroutineScope = coroutineScope,
        installerState = installerState,
        selectedSource = selectedSource,
        selectedUpdateSource = selectedUpdateSource,
        todaySource = todaySource,
        categorySource = categorySource,
        appLanguage = appLanguage,
        showPromotions = showPromotions,
        showAppComments = showAppComments,
        showSameDeveloper = showSameDeveloper,
        stripAppNameSubtitle = stripAppNameSubtitle,
        detectClipboardLinks = detectClipboardLinks,
        homePage = homePage,
        colorMode = themeColorMode,
        colorSource = themeColorSource,
        paletteStyle = themePaletteStyle,
        seedColor = themeSeedColor,
        colorSpec = themeColorSpec,
        amoledDark = amoledDark,
        enableBlur = enableBlur,
        enableFloatingBottomBar = enableFloatingBottomBar,
        enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
        enableNavigationBadge = enableNavigationBadge,
        enablePredictiveBack = enablePredictiveBack,
        onNavigateDeviceProfile = onNavigateDeviceProfile,
        onNavigateInstaller = onNavigateInstaller,
        onNavigateIgnored = onNavigateIgnored,
        onNavigateManualUpdate = onNavigateManualUpdate,
        onNavigateUpdateHistory = onNavigateUpdateHistory,
        onNavigateSavedPackages = onNavigateSavedPackages,
        onNavigateDownloadingApps = onNavigateDownloadingApps,
        onNavigateAbout = onNavigateAbout,
        onNavigateTheme = onNavigateTheme,
    )

    MainTabScaffold(title = stringResource(Res.string.nav_settings)) { topPadding, backdropModifier, scrollBehavior ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().then(backdropModifier).scrollEndHaptic().overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = topPadding + PageVerticalPadding,
                bottom = bottomPadding + PageVerticalPadding,
            ),
        ) {
            item(key = "settings-search") {
                SearchBar(
                    expanded = searchExpanded,
                    onExpandedChange = { searchExpanded = it },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    inputField = {
                        InputField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onSearch = { },
                            expanded = searchExpanded,
                            onExpandedChange = { searchExpanded = it },
                            label = stringResource(Res.string.settings_search),
                        )
                    },
                ) {
                }
            }
            if (searchQuery.isNotBlank()) {
                val query = searchQuery.trim()
                val results = searchEntries.filter { entry ->
                    entry.title.contains(query, ignoreCase = true) ||
                        entry.summary?.contains(query, ignoreCase = true) == true
                }
                if (results.isEmpty()) {
                    item(key = "settings-search-empty") {
                        Text(
                            text = stringResource(Res.string.settings_search_no_results),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 32.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    itemsIndexed(
                        items = results,
                        key = { index, entry -> "search-$index-${entry.title}" },
                    ) { index, entry ->
                        CardSegmentContainer(isFirst = index == 0, isLast = index == results.lastIndex) {
                            SettingsSearchRow(entry)
                        }
                    }
                }
            } else {
            item {
                val sourceOptions = AppSource.entries
                val updateSourceOptions = AppSource.entries.filter { it.capabilities.supportsUpdates }
                val todaySourceOptions = AppSource.entries.filter { it.capabilities.supportsTodayFeed }
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    WindowDropdownPreference(
                        title = stringResource(Res.string.today_source),
                        summary = stringResource(Res.string.today_source_summary),
                        items = todaySourceOptions.map { appSourceLabel(it) },
                        selectedIndex = todaySourceOptions.indexOf(todaySource).coerceAtLeast(0),
                        onSelectedIndexChange = { updatesViewModel.setTodaySource(todaySourceOptions[it]) },
                    )
                    val categorySourceOptions = AppSource.CategorySources
                    WindowDropdownPreference(
                        title = stringResource(Res.string.category_source),
                        summary = stringResource(Res.string.category_source_summary),
                        items = categorySourceOptions.map { appSourceLabel(it) },
                        selectedIndex = categorySourceOptions.indexOf(categorySource).coerceAtLeast(0),
                        onSelectedIndexChange = { updatesViewModel.setCategorySource(categorySourceOptions[it]) },
                    )
                    if (appManagementSupported) {
                        WindowDropdownPreference(
                            title = stringResource(Res.string.update_source),
                            summary = stringResource(Res.string.update_source_summary),
                            items = updateSourceOptions.map { appSourceLabel(it) },
                            selectedIndex = updateSourceOptions.indexOf(selectedUpdateSource).coerceAtLeast(0),
                            onSelectedIndexChange = { updatesViewModel.setUpdateSource(updateSourceOptions[it]) },
                        )
                    }
                    WindowDropdownPreference(
                        title = stringResource(Res.string.search_sources),
                        summary = stringResource(Res.string.search_sources_summary),
                        items = sourceOptions.map { appSourceLabel(it) },
                        selectedIndex = sourceOptions.indexOf(selectedSource),
                        onSelectedIndexChange = { updatesViewModel.setSearchSource(sourceOptions[it]) },
                    )
                }
            }

                if (appManagementSupported) {
                    item { SectionTitle(text = stringResource(Res.string.nav_updates)) }
                    item {
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            SwitchPreference(
                                title = stringResource(Res.string.show_system_apps),
                                summary = stringResource(Res.string.show_system_apps_summary),
                                checked = updatesState.showSystemUpdates,
                                onCheckedChange = updatesViewModel::setShowSystemUpdates,
                            )
                            if (installerState.deltaUpdateSupported && selectedUpdateSource.capabilities.supportsDeltaUpdates) {
                                SwitchPreference(
                                    title = stringResource(Res.string.installer_delta_update),
                                    summary = stringResource(Res.string.installer_delta_update_summary),
                                    checked = installerState.deltaUpdateEnabled,
                                    onCheckedChange = installerSettingsViewModel::setDeltaUpdateEnabled,
                                )
                                if (installerState.deltaUpdateEnabled) {
                                    SwitchPreference(
                                        title = stringResource(Res.string.installer_delta_fallback_notice),
                                        summary = stringResource(Res.string.installer_delta_fallback_notice_summary),
                                        checked = installerState.deltaFallbackNoticeEnabled,
                                        onCheckedChange = installerSettingsViewModel::setDeltaFallbackNoticeEnabled,
                                    )
                                }
                            }
                            ArrowPreference(
                                title = stringResource(Res.string.ignored_apps),
                                summary = stringResource(Res.string.ignored_apps_summary),
                                onClick = onNavigateIgnored,
                            )
                            ArrowPreference(
                                title = stringResource(Res.string.manual_update),
                                summary = stringResource(Res.string.manual_update_summary),
                                onClick = onNavigateManualUpdate,
                            )
                            ArrowPreference(
                                title = stringResource(Res.string.update_history),
                                summary = stringResource(Res.string.update_history_summary),
                                onClick = onNavigateUpdateHistory,
                            )
                        }
                    }
                    if (showSearchFilters) {
                        item { SectionTitle(text = stringResource(Res.string.nav_search)) }
                    }
                } else {
                    if (showSearchFilters) {
                        item { SectionTitle(text = stringResource(Res.string.nav_search)) }
                    }
                }
                if (showSearchFilters) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            if (searchCapabilities.supportsSearchAdsFilter) {
                                SwitchPreference(
                                    title = stringResource(Res.string.remove_search_ads),
                                    summary = stringResource(Res.string.remove_search_ads_summary),
                                    checked = updatesState.removeSearchAds,
                                    onCheckedChange = updatesViewModel::setRemoveSearchAds,
                                )
                            }
                            if (searchCapabilities.supportsQuickAppFilter) {
                                SwitchPreference(
                                    title = stringResource(Res.string.filter_quick_games),
                                    summary = stringResource(Res.string.filter_quick_games_summary),
                                    checked = updatesState.filterQuickGames,
                                    onCheckedChange = updatesViewModel::setFilterQuickGames,
                                )
                            }
                            if (searchCapabilities.supportsReservationFilter) {
                                SwitchPreference(
                                    title = stringResource(Res.string.filter_reservation_apps),
                                    summary = stringResource(Res.string.filter_reservation_apps_summary),
                                    checked = updatesState.filterReservationApps,
                                    onCheckedChange = updatesViewModel::setFilterReservationApps,
                                )
                            }
                        }
                    }
                }
                if (showDetailOptions) {
                    item { SectionTitle(text = stringResource(Res.string.app_detail)) }
                    item {
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            if (searchCapabilities.supportsPromotions) {
                                SwitchPreference(
                                    title = stringResource(Res.string.show_promotions),
                                    summary = stringResource(Res.string.show_promotions_summary),
                                    checked = showPromotions,
                                    onCheckedChange = updatesViewModel::setShowPromotions,
                                )
                            }
                            if (searchCapabilities.supportsComments) {
                                SwitchPreference(
                                    title = stringResource(Res.string.show_app_comments),
                                    summary = stringResource(Res.string.show_app_comments_summary),
                                    checked = showAppComments,
                                    onCheckedChange = updatesViewModel::setShowAppComments,
                                )
                            }
                            if (searchCapabilities.supportsSameDeveloperApps) {
                                SwitchPreference(
                                    title = stringResource(Res.string.show_same_developer),
                                    summary = stringResource(Res.string.show_same_developer_summary),
                                    checked = showSameDeveloper,
                                    onCheckedChange = updatesViewModel::setShowSameDeveloper,
                                )
                            }
                        }
                    }
                }
                item { SectionTitle(text = stringResource(Res.string.settings_section_download_install)) }
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = stringResource(Res.string.downloading_apps),
                            summary = stringResource(Res.string.downloading_apps_summary),
                            onClick = onNavigateDownloadingApps,
                        )
                        if (installerState.installationSupported) {
                            ArrowPreference(
                                title = stringResource(Res.string.saved_packages),
                                summary = stringResource(Res.string.saved_packages_summary),
                                onClick = onNavigateSavedPackages,
                            )
                            ArrowPreference(
                                title = stringResource(Res.string.installer_section),
                                summary = stringResource(Res.string.installer_section_summary),
                                onClick = onNavigateInstaller,
                            )
                            if (installerState.focusNotificationSupported) {
                                SwitchPreference(
                                    // OS3 带岛胶囊称「超级岛」，OS2 仅焦点通知
                                    title = stringResource(
                                        if (installerState.xiaomiIslandSupported) {
                                            Res.string.xiaomi_island_optimization
                                        } else {
                                            Res.string.focus_notification_optimization
                                        }
                                    ),
                                    summary = stringResource(Res.string.xiaomi_island_optimization_summary),
                                    checked = installerState.xiaomiIslandOptimizationEnabled,
                                    onCheckedChange = installerSettingsViewModel::setXiaomiIslandOptimizationEnabled,
                                )
                            }
                        }
                    }
                }
                item { SectionTitle(text = stringResource(Res.string.general)) }
                item {
                    // 桌面端无「更新」页，且根据用户启用的分頁相應剔除
                    val homePageOptions = remember(appManagementSupported, enabledTabs) {
                        buildList {
                            if ("today" in enabledTabs) add(HomePage.TODAY)
                            if (appManagementSupported && "updates" in enabledTabs) add(HomePage.UPDATES)
                            if ("search" in enabledTabs) add(HomePage.SEARCH)
                        }.ifEmpty { listOf(HomePage.TODAY) }
                    }
                    val languageOptions = remember {
                        listOf(
                            LanguageOption(null, Res.string.language_system),
                            LanguageOption("zh-TW", Res.string.language_zh_tw),
                            LanguageOption("zh-HK", Res.string.language_zh_hk),
                            LanguageOption("zh-CN", Res.string.language_zh_cn),
                            LanguageOption("en", Res.string.language_en),
                        )
                    }
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        WindowDropdownPreference(
                            title = stringResource(Res.string.home_page),
                            summary = stringResource(Res.string.home_page_summary),
                            items = homePageOptions.map { stringResource(it.labelRes) },
                            selectedIndex = homePageOptions.indexOf(homePage).coerceAtLeast(0),
                            onSelectedIndexChange = { updatesViewModel.setHomePage(homePageOptions[it]) },
                        )
                        WindowDropdownPreference(
                            title = stringResource(Res.string.language),
                            summary = stringResource(Res.string.language_summary),
                            items = languageOptions.map { stringResource(it.titleRes) },
                            selectedIndex = languageOptions.indexOfFirst { it.code == appLanguage }.takeIf { it >= 0 } ?: 0,
                            onSelectedIndexChange = { index ->
                                coroutineScope.launch {
                                    themePreferences.setAppLanguage(languageOptions[index].code)
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(Res.string.strip_name_subtitle),
                            summary = stringResource(Res.string.strip_name_subtitle_summary),
                            checked = stripAppNameSubtitle,
                            onCheckedChange = updatesViewModel::setStripAppNameSubtitle,
                        )
                        SwitchPreference(
                            title = stringResource(Res.string.clipboard_link_detect),
                            summary = stringResource(Res.string.clipboard_link_detect_summary),
                            checked = detectClipboardLinks,
                            onCheckedChange = updatesViewModel::setDetectClipboardLinks,
                        )
                        ArrowPreference(
                            title = stringResource(Res.string.device_profile),
                            summary = stringResource(Res.string.device_profile_summary),
                            onClick = onNavigateDeviceProfile,
                        )
                        ArrowPreference(
                            title = stringResource(Res.string.theme),
                            summary = stringResource(Res.string.theme_summary),
                            onClick = onNavigateTheme,
                        )
                        ArrowPreference(
                            title = stringResource(Res.string.about),
                            summary = stringResource(Res.string.about_summary),
                            onClick = onNavigateAbout,
                        )
                    }
                }
            }

        }
    }
}

private val HomePage.labelRes
    get() = when (this) {
        HomePage.TODAY -> Res.string.nav_today
        HomePage.UPDATES -> Res.string.nav_updates
        HomePage.SEARCH -> Res.string.nav_search
    }

private data class LanguageOption(
    val code: String?,
    val titleRes: StringResource,
)
private sealed interface SettingsSearchEntry {
    val title: String
    val summary: String?

    data class Switch(
        override val title: String,
        override val summary: String?,
        val checked: Boolean,
        val onChecked: (Boolean) -> Unit,
    ) : SettingsSearchEntry

    data class Dropdown(
        override val title: String,
        override val summary: String?,
        val items: List<String>,
        val selectedIndex: Int,
        val onSelect: (Int) -> Unit,
    ) : SettingsSearchEntry

    data class Link(
        override val title: String,
        override val summary: String?,
        val onClick: () -> Unit,
    ) : SettingsSearchEntry
}

@Composable
private fun SettingsSearchRow(entry: SettingsSearchEntry) {
    when (entry) {
        is SettingsSearchEntry.Switch -> SwitchPreference(
            title = entry.title,
            summary = entry.summary,
            checked = entry.checked,
            onCheckedChange = entry.onChecked,
        )

        is SettingsSearchEntry.Dropdown -> OverlayDropdownPreference(
            items = entry.items,
            selectedIndex = entry.selectedIndex.coerceAtLeast(0),
            title = entry.title,
            summary = entry.summary,
            onSelectedIndexChange = entry.onSelect,
        )

        is SettingsSearchEntry.Link -> ArrowPreference(
            title = entry.title,
            summary = entry.summary,
            onClick = entry.onClick,
        )
    }
}

private fun switchEntry(
    title: String,
    summary: String?,
    checked: Boolean,
    scope: CoroutineScope,
    onChecked: suspend (Boolean) -> Unit,
) = SettingsSearchEntry.Switch(title, summary, checked) { value ->
    scope.launch { onChecked(value) }
}

@Composable
private fun settingsSearchEntries(
    appManagementSupported: Boolean,
    enabledTabs: Set<String>,
    updatesViewModel: UpdatesViewModel,
    updatesState: UpdatesUiState,
    installerSettingsViewModel: InstallerSettingsViewModel,
    installerState: InstallerSettingsUiState,
    themePreferences: ThemePreferencesRepository,
    coroutineScope: CoroutineScope,
    selectedSource: AppSource,
    selectedUpdateSource: AppSource,
    todaySource: AppSource,
    categorySource: AppSource,
    appLanguage: String?,
    showPromotions: Boolean,
    showAppComments: Boolean,
    showSameDeveloper: Boolean,
    stripAppNameSubtitle: Boolean,
    detectClipboardLinks: Boolean,
    homePage: HomePage,
    colorMode: ThemeColorMode,
    colorSource: ThemeColorSource,
    paletteStyle: ThemePaletteStyle,
    seedColor: Long?,
    colorSpec: ThemeColorSpec,
    amoledDark: Boolean,
    enableBlur: Boolean,
    enableFloatingBottomBar: Boolean,
    enableFloatingBottomBarBlur: Boolean,
    enableNavigationBadge: Boolean,
    enablePredictiveBack: Boolean,
    onNavigateDeviceProfile: () -> Unit,
    onNavigateInstaller: () -> Unit,
    onNavigateIgnored: () -> Unit,
    onNavigateManualUpdate: () -> Unit,
    onNavigateUpdateHistory: () -> Unit,
    onNavigateSavedPackages: () -> Unit,
    onNavigateDownloadingApps: () -> Unit,
    onNavigateAbout: () -> Unit,
    onNavigateTheme: () -> Unit,
): List<SettingsSearchEntry> {
    val sourceOptions = AppSource.entries
    val updateSourceOptions = AppSource.entries.filter { it.capabilities.supportsUpdates }
    val todaySourceOptions = AppSource.entries.filter { it.capabilities.supportsTodayFeed }
    val searchCapabilities = selectedSource.capabilities
    val homePageOptions = buildList {
        if ("today" in enabledTabs) add(HomePage.TODAY)
        if (appManagementSupported && "updates" in enabledTabs) add(HomePage.UPDATES)
        if ("search" in enabledTabs) add(HomePage.SEARCH)
    }.ifEmpty { listOf(HomePage.TODAY) }
    val languageOptions = listOf(
        LanguageOption(null, Res.string.language_system),
        LanguageOption("zh-TW", Res.string.language_zh_tw),
        LanguageOption("zh-HK", Res.string.language_zh_hk),
        LanguageOption("zh-CN", Res.string.language_zh_cn),
        LanguageOption("en", Res.string.language_en),
    )
    val installerModes = InstallerMode.entries
    val installerModeItems = listOf(
        stringResource(Res.string.installer_mode_default),
        stringResource(Res.string.installer_mode_root),
        stringResource(Res.string.installer_mode_shizuku),
        stringResource(Res.string.installer_mode_third_party),
    )
    val colorModeItems = listOf(
        stringResource(Res.string.theme_dark_mode_system),
        stringResource(Res.string.theme_dark_mode_light),
        stringResource(Res.string.theme_dark_mode_dark),
    )
    val paletteStyleItems = ThemePaletteStyle.entries.map { stringResource(paletteStyleRes(it)) }
    val blurSupported = isBlurSettingSupported()
    val predictiveBackSupported = isPredictiveBackSupported()

    return buildList {
        add(
            SettingsSearchEntry.Dropdown(
                title = stringResource(Res.string.today_source),
                summary = stringResource(Res.string.today_source_summary),
                items = todaySourceOptions.map { appSourceLabel(it) },
                selectedIndex = todaySourceOptions.indexOf(todaySource).coerceAtLeast(0),
                onSelect = { index -> updatesViewModel.setTodaySource(todaySourceOptions[index]) },
            )
        )
        add(
            SettingsSearchEntry.Dropdown(
                title = stringResource(Res.string.category_source),
                summary = stringResource(Res.string.category_source_summary),
                items = AppSource.CategorySources.map { appSourceLabel(it) },
                selectedIndex = AppSource.CategorySources.indexOf(categorySource).coerceAtLeast(0),
                onSelect = { index -> updatesViewModel.setCategorySource(AppSource.CategorySources[index]) },
            )
        )
        if (appManagementSupported) {
            add(
                SettingsSearchEntry.Dropdown(
                    title = stringResource(Res.string.update_source),
                    summary = stringResource(Res.string.update_source_summary),
                    items = updateSourceOptions.map { appSourceLabel(it) },
                    selectedIndex = updateSourceOptions.indexOf(selectedUpdateSource).coerceAtLeast(0),
                    onSelect = { index -> updatesViewModel.setUpdateSource(updateSourceOptions[index]) },
                )
            )
        }
        add(
            SettingsSearchEntry.Dropdown(
                title = stringResource(Res.string.search_sources),
                summary = stringResource(Res.string.search_sources_summary),
                items = sourceOptions.map { appSourceLabel(it) },
                selectedIndex = sourceOptions.indexOf(selectedSource).coerceAtLeast(0),
                onSelect = { index -> updatesViewModel.setSearchSource(sourceOptions[index]) },
            )
        )
        if (appManagementSupported) {
            add(
                switchEntry(
                    title = stringResource(Res.string.show_system_apps),
                    summary = stringResource(Res.string.show_system_apps_summary),
                    checked = updatesState.showSystemUpdates,
                    scope = coroutineScope,
                ) { updatesViewModel.setShowSystemUpdates(it) }
            )
            if (installerState.deltaUpdateSupported && selectedUpdateSource.capabilities.supportsDeltaUpdates) {
                add(
                    switchEntry(
                        title = stringResource(Res.string.installer_delta_update),
                        summary = stringResource(Res.string.installer_delta_update_summary),
                        checked = installerState.deltaUpdateEnabled,
                        scope = coroutineScope,
                    ) { installerSettingsViewModel.setDeltaUpdateEnabled(it) }
                )
                if (installerState.deltaUpdateEnabled) {
                    add(
                        switchEntry(
                            title = stringResource(Res.string.installer_delta_fallback_notice),
                            summary = stringResource(Res.string.installer_delta_fallback_notice_summary),
                            checked = installerState.deltaFallbackNoticeEnabled,
                            scope = coroutineScope,
                        ) { installerSettingsViewModel.setDeltaFallbackNoticeEnabled(it) }
                    )
                }
            }
            add(
                SettingsSearchEntry.Link(
                    title = stringResource(Res.string.ignored_apps),
                    summary = stringResource(Res.string.ignored_apps_summary),
                    onClick = onNavigateIgnored,
                )
            )
            add(
                SettingsSearchEntry.Link(
                    title = stringResource(Res.string.manual_update),
                    summary = stringResource(Res.string.manual_update_summary),
                    onClick = onNavigateManualUpdate,
                )
            )
            add(
                SettingsSearchEntry.Link(
                    title = stringResource(Res.string.update_history),
                    summary = stringResource(Res.string.update_history_summary),
                    onClick = onNavigateUpdateHistory,
                )
            )
        }
        if (searchCapabilities.supportsSearchAdsFilter) {
            add(
                switchEntry(
                    title = stringResource(Res.string.remove_search_ads),
                    summary = stringResource(Res.string.remove_search_ads_summary),
                    checked = updatesState.removeSearchAds,
                    scope = coroutineScope,
                ) { updatesViewModel.setRemoveSearchAds(it) }
            )
        }
        if (searchCapabilities.supportsQuickAppFilter) {
            add(
                switchEntry(
                    title = stringResource(Res.string.filter_quick_games),
                    summary = stringResource(Res.string.filter_quick_games_summary),
                    checked = updatesState.filterQuickGames,
                    scope = coroutineScope,
                ) { updatesViewModel.setFilterQuickGames(it) }
            )
        }
        if (searchCapabilities.supportsReservationFilter) {
            add(
                switchEntry(
                    title = stringResource(Res.string.filter_reservation_apps),
                    summary = stringResource(Res.string.filter_reservation_apps_summary),
                    checked = updatesState.filterReservationApps,
                    scope = coroutineScope,
                ) { updatesViewModel.setFilterReservationApps(it) }
            )
        }
        if (searchCapabilities.supportsPromotions) {
            add(
                switchEntry(
                    title = stringResource(Res.string.show_promotions),
                    summary = stringResource(Res.string.show_promotions_summary),
                    checked = showPromotions,
                    scope = coroutineScope,
                ) { updatesViewModel.setShowPromotions(it) }
            )
        }
        if (searchCapabilities.supportsComments) {
            add(
                switchEntry(
                    title = stringResource(Res.string.show_app_comments),
                    summary = stringResource(Res.string.show_app_comments_summary),
                    checked = showAppComments,
                    scope = coroutineScope,
                ) { updatesViewModel.setShowAppComments(it) }
            )
        }
        if (searchCapabilities.supportsSameDeveloperApps) {
            add(
                switchEntry(
                    title = stringResource(Res.string.show_same_developer),
                    summary = stringResource(Res.string.show_same_developer_summary),
                    checked = showSameDeveloper,
                    scope = coroutineScope,
                ) { updatesViewModel.setShowSameDeveloper(it) }
            )
        }
        add(
            SettingsSearchEntry.Link(
                title = stringResource(Res.string.downloading_apps),
                summary = stringResource(Res.string.downloading_apps_summary),
                onClick = onNavigateDownloadingApps,
            )
        )
        if (installerState.installationSupported) {
            add(
                SettingsSearchEntry.Link(
                    title = stringResource(Res.string.saved_packages),
                    summary = stringResource(Res.string.saved_packages_summary),
                    onClick = onNavigateSavedPackages,
                )
            )
            add(
                SettingsSearchEntry.Link(
                    title = stringResource(Res.string.installer_section),
                    summary = stringResource(Res.string.installer_section_summary),
                    onClick = onNavigateInstaller,
                )
            )
            if (installerState.focusNotificationSupported) {
                add(
                    switchEntry(
                        title = stringResource(
                            if (installerState.xiaomiIslandSupported) {
                                Res.string.xiaomi_island_optimization
                            } else {
                                Res.string.focus_notification_optimization
                            }
                        ),
                        summary = stringResource(Res.string.xiaomi_island_optimization_summary),
                        checked = installerState.xiaomiIslandOptimizationEnabled,
                        scope = coroutineScope,
                    ) { installerSettingsViewModel.setXiaomiIslandOptimizationEnabled(it) }
                )
            }
            add(
                SettingsSearchEntry.Dropdown(
                    title = stringResource(Res.string.installer_section),
                    summary = stringResource(Res.string.installer_mode_third_party_summary),
                    items = installerModeItems,
                    selectedIndex = installerModes.indexOf(installerState.mode).coerceAtLeast(0),
                    onSelect = { index ->
                        when (installerModes.getOrNull(index)) {
                            InstallerMode.THIRD_PARTY -> onNavigateInstaller()
                            null -> Unit
                            else -> installerSettingsViewModel.setMode(installerModes[index])
                        }
                    },
                )
            )
            add(
                switchEntry(
                    title = stringResource(Res.string.installer_save_to_downloads),
                    summary = stringResource(Res.string.installer_save_to_downloads_summary),
                    checked = installerState.saveToDownloads,
                    scope = coroutineScope,
                ) { installerSettingsViewModel.setSaveToDownloads(it) }
            )
            add(
                switchEntry(
                    title = stringResource(Res.string.installer_auto_launch_confirm),
                    summary = stringResource(Res.string.installer_auto_launch_confirm_summary),
                    checked = installerState.autoLaunchConfirmUi,
                    scope = coroutineScope,
                ) { installerSettingsViewModel.setAutoLaunchConfirmUi(it) }
            )
            if (installerState.mode == InstallerMode.STANDARD && installerState.userActionNotRequiredConfigurable) {
                add(
                    switchEntry(
                        title = stringResource(Res.string.installer_no_user_action),
                        summary = stringResource(Res.string.installer_no_user_action_summary),
                        checked = installerState.userActionNotRequiredEnabled,
                        scope = coroutineScope,
                    ) { installerSettingsViewModel.setUserActionNotRequiredEnabled(it) }
                )
            }
        }
        add(
            SettingsSearchEntry.Dropdown(
                title = stringResource(Res.string.home_page),
                summary = stringResource(Res.string.home_page_summary),
                items = homePageOptions.map { stringResource(it.labelRes) },
                selectedIndex = homePageOptions.indexOf(homePage).coerceAtLeast(0),
                onSelect = { index -> updatesViewModel.setHomePage(homePageOptions[index]) },
            )
        )
        add(
            SettingsSearchEntry.Dropdown(
                title = stringResource(Res.string.language),
                summary = stringResource(Res.string.language_summary),
                items = languageOptions.map { stringResource(it.titleRes) },
                selectedIndex = languageOptions.indexOfFirst { it.code == appLanguage }.takeIf { it >= 0 } ?: 0,
                onSelect = { index ->
                    coroutineScope.launch { themePreferences.setAppLanguage(languageOptions[index].code) }
                },
            )
        )
        add(
            switchEntry(
                title = stringResource(Res.string.strip_name_subtitle),
                summary = stringResource(Res.string.strip_name_subtitle_summary),
                checked = stripAppNameSubtitle,
                scope = coroutineScope,
            ) { updatesViewModel.setStripAppNameSubtitle(it) }
        )
        add(
            switchEntry(
                title = stringResource(Res.string.clipboard_link_detect),
                summary = stringResource(Res.string.clipboard_link_detect_summary),
                checked = detectClipboardLinks,
                scope = coroutineScope,
            ) { updatesViewModel.setDetectClipboardLinks(it) }
        )
        add(
            SettingsSearchEntry.Link(
                title = stringResource(Res.string.device_profile),
                summary = stringResource(Res.string.device_profile_summary),
                onClick = onNavigateDeviceProfile,
            )
        )
        add(
            SettingsSearchEntry.Link(
                title = stringResource(Res.string.theme),
                summary = stringResource(Res.string.theme_summary),
                onClick = onNavigateTheme,
            )
        )
        add(
            SettingsSearchEntry.Link(
                title = stringResource(Res.string.about),
                summary = stringResource(Res.string.about_summary),
                onClick = onNavigateAbout,
            )
        )
        add(
            SettingsSearchEntry.Dropdown(
                title = stringResource(Res.string.theme_dark_mode),
                summary = null,
                items = colorModeItems,
                selectedIndex = colorModeIndex(colorMode),
                onSelect = { index ->
                    coroutineScope.launch { themePreferences.setColorMode(colorModeAt(index)) }
                },
            )
        )
        add(
            switchEntry(
                title = stringResource(Res.string.theme_use_monet),
                summary = null,
                checked = colorSource != ThemeColorSource.DEFAULT,
                scope = coroutineScope,
            ) { on ->
                themePreferences.setColorSource(
                    if (on) {
                        if (seedColor != null) ThemeColorSource.CUSTOM else ThemeColorSource.MONET
                    } else {
                        ThemeColorSource.DEFAULT
                    }
                )
            }
        )
        if (colorSource != ThemeColorSource.DEFAULT) {
            add(
                SettingsSearchEntry.Dropdown(
                    title = stringResource(Res.string.theme_palette_style),
                    summary = stringResource(paletteStyleRes(paletteStyle)),
                    items = paletteStyleItems,
                    selectedIndex = ThemePaletteStyle.entries.indexOf(paletteStyle),
                    onSelect = { index ->
                        ThemePaletteStyle.entries.getOrNull(index)?.let { style ->
                            coroutineScope.launch { themePreferences.setPaletteStyle(style) }
                        }
                    },
                )
            )
            add(
                SettingsSearchEntry.Dropdown(
                    title = stringResource(Res.string.theme_color_spec),
                    summary = if (paletteStyle.supportsSpec2025) null else stringResource(Res.string.theme_color_spec_unsupported),
                    items = listOf(
                        stringResource(Res.string.theme_color_spec_2021),
                        stringResource(Res.string.theme_color_spec_2025),
                    ),
                    selectedIndex = ThemeColorSpec.entries.indexOf(colorSpec),
                    onSelect = { index ->
                        if (!paletteStyle.supportsSpec2025) return@Dropdown
                        ThemeColorSpec.entries.getOrNull(index)?.let { spec ->
                            coroutineScope.launch { themePreferences.setColorSpec(spec) }
                        }
                    },
                )
            )
            add(
                SettingsSearchEntry.Link(
                    title = stringResource(Res.string.theme_accent_color),
                    summary = null,
                    onClick = onNavigateTheme,
                )
            )
        }
        add(
            switchEntry(
                title = stringResource(Res.string.theme_amoled),
                summary = null,
                checked = amoledDark,
                scope = coroutineScope,
            ) { themePreferences.setAmoledDark(it) }
        )
        if (blurSupported) {
            add(
                switchEntry(
                    title = stringResource(Res.string.theme_enable_blur),
                    summary = stringResource(Res.string.theme_enable_blur_summary),
                    checked = enableBlur,
                    scope = coroutineScope,
                ) { themePreferences.setEnableBlur(it) }
            )
        }
        add(
            switchEntry(
                title = stringResource(Res.string.theme_floating_bottom_bar),
                summary = stringResource(Res.string.theme_floating_bottom_bar_summary),
                checked = enableFloatingBottomBar,
                scope = coroutineScope,
            ) { themePreferences.setEnableFloatingBottomBar(it) }
        )
        if (blurSupported) {
            add(
                switchEntry(
                    title = stringResource(Res.string.theme_enable_glass),
                    summary = stringResource(Res.string.theme_enable_glass_summary),
                    checked = enableFloatingBottomBarBlur,
                    scope = coroutineScope,
                ) { themePreferences.setEnableFloatingBottomBarBlur(it) }
            )
        }
        add(
            switchEntry(
                title = stringResource(Res.string.theme_navigation_badge),
                summary = stringResource(Res.string.theme_navigation_badge_summary),
                checked = enableNavigationBadge,
                scope = coroutineScope,
            ) { themePreferences.setEnableNavigationBadge(it) }
        )
        if (predictiveBackSupported) {
            add(
                switchEntry(
                    title = stringResource(Res.string.theme_predictive_back),
                    summary = stringResource(Res.string.theme_predictive_back_summary),
                    checked = enablePredictiveBack,
                    scope = coroutineScope,
                ) { themePreferences.setEnablePredictiveBack(it) }
            )
        }
        add(
            SettingsSearchEntry.Link(
                title = stringResource(Res.string.theme_page_scale),
                summary = stringResource(Res.string.theme_page_scale_summary),
                onClick = onNavigateTheme,
            )
        )
    }
}
