package top.app.market.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.preference.HomePage
import top.app.market.domain.repository.ThemePreferencesRepository
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
import top.app.market.resources.settings_section_download_install
import top.app.market.resources.show_app_comments
import top.app.market.resources.show_app_comments_summary
import top.app.market.resources.show_promotions
import top.app.market.resources.show_promotions_summary
import top.app.market.resources.show_same_developer
import top.app.market.resources.show_same_developer_summary
import top.app.market.resources.show_system_apps
import top.app.market.resources.show_system_apps_summary
import top.app.market.resources.strip_name_subtitle
import top.app.market.resources.strip_name_subtitle_summary
import top.app.market.resources.theme
import top.app.market.resources.theme_summary
import top.app.market.resources.today_source
import top.app.market.resources.today_source_summary
import top.app.market.resources.update_history
import top.app.market.resources.update_history_summary
import top.app.market.resources.update_source
import top.app.market.resources.update_source_summary
import top.app.market.resources.xiaomi_island_optimization
import top.app.market.resources.xiaomi_island_optimization_summary
import top.app.market.ui.component.MainTabScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.component.SectionTitle
import top.app.market.ui.util.appSourceLabel
import top.app.market.viewmodel.InstallerSettingsViewModel
import top.app.market.viewmodel.UpdatesViewModel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
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
    val showAppComments by updatesViewModel.showAppComments.collectAsStateWithLifecycle()
    val showSameDeveloper by updatesViewModel.showSameDeveloper.collectAsStateWithLifecycle()
    val showPromotions by updatesViewModel.showPromotions.collectAsStateWithLifecycle()
    val stripAppNameSubtitle by updatesViewModel.stripAppNameSubtitle.collectAsStateWithLifecycle()
    val selectedSource = AppSource.entries.firstOrNull { it in searchSources } ?: AppSource.Default.first()
    val selectedUpdateSource by updatesViewModel.updateSource.collectAsStateWithLifecycle()
    val searchCapabilities = selectedSource.capabilities
    val showSearchFilters = searchCapabilities.supportsSearchAdsFilter ||
            searchCapabilities.supportsQuickAppFilter ||
            searchCapabilities.supportsReservationFilter
    val showDetailOptions = searchCapabilities.supportsPromotions ||
            searchCapabilities.supportsComments ||
            searchCapabilities.supportsSameDeveloperApps

    MainTabScaffold(title = stringResource(Res.string.nav_settings)) { topPadding, backdropModifier, scrollBehavior ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().then(backdropModifier).scrollEndHaptic().overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = topPadding + PageVerticalPadding,
                bottom = bottomPadding + PageVerticalPadding,
            ),
        ) {
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
