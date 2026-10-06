package top.app.market.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.LayoutDirection
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketLink
import top.app.market.domain.model.update.IgnoredUpdate
import top.app.market.ui.screen.AboutScreen
import top.app.market.ui.screen.AppDetailScreen
import top.app.market.ui.screen.DeviceProfileScreen
import top.app.market.ui.screen.DownloadingAppsScreen
import top.app.market.ui.screen.HistoricalVersionsScreen
import top.app.market.ui.screen.IgnoredAppsScreen
import top.app.market.ui.screen.InstallerSettingsScreen
import top.app.market.ui.screen.ManualUpdateScreen
import top.app.market.ui.screen.SavedPackagesScreen
import top.app.market.ui.screen.ThemeSettingsScreen
import top.app.market.ui.screen.TodayArticleScreen
import top.app.market.ui.screen.UpdateHistoryScreen
import top.app.market.viewmodel.AppDetailViewModel
import top.app.market.viewmodel.DeviceProfileViewModel
import top.app.market.viewmodel.DownloadingAppsViewModel
import top.app.market.viewmodel.HistoricalVersionsViewModel
import top.app.market.viewmodel.IgnoredAppsViewModel
import top.app.market.viewmodel.InstallerSettingsViewModel
import top.app.market.viewmodel.ManualUpdateViewModel
import top.app.market.viewmodel.SavedPackagesViewModel
import top.app.market.viewmodel.SearchViewModel
import top.app.market.viewmodel.ThemeSettingsViewModel
import top.app.market.viewmodel.CategoryViewModel
import top.app.market.viewmodel.TodayViewModel
import top.app.market.viewmodel.UpdateHistoryViewModel
import top.app.market.viewmodel.UpdatesViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

@Composable
fun AppNavigation(
    externalDetailLink: MarketLink? = null,
    externalDetailQuery: String? = null,
    onExternalDetailConsumed: (MarketLink) -> Unit = {},
    externalSearchKeyword: String? = null,
    onExternalSearchConsumed: (String) -> Unit = {},
    externalOpenDownloads: Boolean = false,
    onExternalDownloadsConsumed: () -> Unit = {},
) {
    val backStack = rememberNavBackStack<Route>(Route.Main)
    val navigator = remember { Navigator(backStack) }
    var pendingSearchKeyword by remember { mutableStateOf<String?>(null) }

    val updatesViewModel = koinViewModel<UpdatesViewModel>()
    val searchViewModel = koinViewModel<SearchViewModel>()
    val installerSettingsViewModel = koinViewModel<InstallerSettingsViewModel>()
    val todayViewModel = koinViewModel<TodayViewModel>()
    val categoryViewModel = koinViewModel<CategoryViewModel>()
    val swipeBackDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }

    LaunchedEffect(externalDetailLink, externalDetailQuery) {
        val link = externalDetailLink ?: return@LaunchedEffect
        navigator.openMarketLink(link, externalDetailQuery)
        onExternalDetailConsumed(link)
    }

    // 外部搜索链接：先回主页面，再交由主页面切页执行
    LaunchedEffect(externalSearchKeyword) {
        val keyword = externalSearchKeyword ?: return@LaunchedEffect
        navigator.popUntil { it is Route.Main }
        pendingSearchKeyword = keyword
        onExternalSearchConsumed(keyword)
    }

    LaunchedEffect(externalOpenDownloads) {
        if (!externalOpenDownloads) return@LaunchedEffect
        if (navigator.current() != Route.DownloadingApps) {
            navigator.push(Route.DownloadingApps)
        }
        onExternalDownloadsConsumed()
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.pop() },
            effects = NavDisplayEffects(
                enableCornerClip = true,
                cornerClipRadius = rememberNavSystemCornerRadius(),
                dimAmount = 0.5f,
            ),
        ) {
            entry<Route.Main> {
                MainPage(
                    navigator = navigator,
                    updatesViewModel = updatesViewModel,
                    searchViewModel = searchViewModel,
                    installerSettingsViewModel = installerSettingsViewModel,
                    todayViewModel = todayViewModel,
                    categoryViewModel = categoryViewModel,
                    pendingSearchKeyword = pendingSearchKeyword,
                    onPendingSearchConsumed = { pendingSearchKeyword = null },
                )
            }
            entry<Route.AppDetail>(swipeDismiss = swipeBackDirection) { route ->
                val vm = koinViewModel<AppDetailViewModel>()
                AppDetailScreen(
                    viewModel = vm,
                    appId = route.appId,
                    packageName = route.packageName,
                    externalQuery = route.externalQuery,
                    source = route.source,
                    onOpenDetail = {
                        navigator.push(
                            Route.AppDetail(
                                it.appId,
                                it.packageName,
                                it.displayName,
                                source = it.source,
                            )
                        )
                    },
                    onOpenHistory = { app ->
                        navigator.push(
                            Route.HistoricalVersions(
                                appId = app.appId,
                                packageName = app.packageName,
                                displayName = app.displayName,
                            )
                        )
                    },
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.HistoricalVersions>(swipeDismiss = swipeBackDirection) { route ->
                val vm = koinViewModel<HistoricalVersionsViewModel>()
                HistoricalVersionsScreen(
                    viewModel = vm,
                    appId = route.appId,
                    packageName = route.packageName,
                    displayName = route.displayName,
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.TodayArticle>(swipeDismiss = swipeBackDirection) { route ->
                TodayArticleScreen(
                    rId = route.rId,
                    viewModel = todayViewModel,
                    onOpenApp = {
                        navigator.push(
                            Route.AppDetail(
                                it.appId,
                                it.packageName,
                                it.displayName,
                                source = it.source,
                            )
                        )
                    },
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.DeviceProfile>(swipeDismiss = swipeBackDirection) {
                val vm = koinViewModel<DeviceProfileViewModel>()
                DeviceProfileScreen(vm, onBack = { navigator.pop() })
            }
            entry<Route.IgnoredApps>(swipeDismiss = swipeBackDirection) {
                val vm = koinViewModel<IgnoredAppsViewModel>()
                IgnoredAppsScreen(
                    viewModel = vm,
                    onOpenDetail = {
                        val activeSource = updatesViewModel.updateSource.value
                        navigator.push(ignoredDetailRoute(it, activeSource))
                    },
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.ManualUpdate>(swipeDismiss = swipeBackDirection) {
                val vm = koinViewModel<ManualUpdateViewModel>()
                ManualUpdateScreen(
                    viewModel = vm,
                    onOpenDetail = {
                        navigator.push(
                            Route.AppDetail(
                                it.appId,
                                it.packageName,
                                it.displayName,
                                source = it.source,
                            )
                        )
                    },
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.UpdateHistory>(swipeDismiss = swipeBackDirection) {
                val vm = koinViewModel<UpdateHistoryViewModel>()
                UpdateHistoryScreen(
                    viewModel = vm,
                    onOpenDetail = {
                        navigator.push(
                            Route.AppDetail(
                                it.appId,
                                it.packageName,
                                it.displayName,
                                source = it.source
                                    ?: updatesViewModel.updateSource.value,
                            )
                        )
                    },
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.SavedPackages>(swipeDismiss = swipeBackDirection) {
                val vm = koinViewModel<SavedPackagesViewModel>()
                SavedPackagesScreen(
                    viewModel = vm,
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.DownloadingApps>(swipeDismiss = swipeBackDirection) {
                val vm = koinViewModel<DownloadingAppsViewModel>()
                DownloadingAppsScreen(
                    viewModel = vm,
                    onOpenDetail = {
                        val source = it.source
                            ?: updatesViewModel.searchSources.value.firstOrNull()
                            ?: AppSource.Default.first()
                        navigator.push(
                            Route.AppDetail(
                                it.appId,
                                it.packageName,
                                it.displayName,
                                source = source,
                            )
                        )
                    },
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.InstallerSettings>(swipeDismiss = swipeBackDirection) {
                InstallerSettingsScreen(installerSettingsViewModel, onBack = { navigator.pop() })
            }
            entry<Route.About>(swipeDismiss = swipeBackDirection) {
                val uriHandler = LocalUriHandler.current
                AboutScreen(onBack = { navigator.pop() }, onOpenUrl = { uriHandler.openUri(it) })
            }
            entry<Route.ThemeSettings>(swipeDismiss = swipeBackDirection) {
                val vm = koinViewModel<ThemeSettingsViewModel>()
                ThemeSettingsScreen(vm, onBack = { navigator.pop() })
            }
        }
    }
}

internal fun ignoredDetailRoute(entry: IgnoredUpdate, activeSource: AppSource): Route.AppDetail {
    val sameSource = entry.source == activeSource
    return Route.AppDetail(
        appId = if (sameSource) entry.appId else 0L,
        packageName = entry.packageName,
        displayName = entry.displayName,
        externalQuery = if (sameSource) null else "id=${entry.packageName}",
        source = activeSource,
    )
}

/**
 * 打开商店链接对应的详情页：链接指明的来源优先，未指明（通用 market://、Google Play 等）时用小米。
 * 小米详情沿用链接原有查询参数（含 ref 等），其余来源按包名 / 站内 id 加载。
 * vivo / OPPO / 三星 / 豌豆荚的详情需要站内 id，只带包名时按包名查不到，改由小米打开。
 */
internal fun Navigator.openMarketLink(link: MarketLink, xiaomiQuery: String? = null) {
    if (link.packageName.isBlank() && link.storeAppId <= 0L) return
    val source = link.source
        ?.takeIf { link.storeAppId > 0L || it in PackageResolvableSources }
        ?: AppSource.XIAOMI
    val target = Route.AppDetail(
        appId = link.storeAppId,
        packageName = link.packageName,
        displayName = link.packageName,
        externalQuery = if (source == AppSource.XIAOMI) xiaomiQuery ?: "id=${link.packageName}" else null,
        source = source,
    )
    if (current() != target) push(target)
}

/** 仅凭包名即可加载详情的来源（已用真实请求验证）。 */
private val PackageResolvableSources = setOf(AppSource.XIAOMI, AppSource.HUAWEI, AppSource.HONOR, AppSource.TAPTAP)
