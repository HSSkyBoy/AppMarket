package top.app.market.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.market.AppCategory
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.AppSubCategory
import top.app.market.domain.model.market.GameRanking
import top.app.market.domain.model.market.GameSubCategory
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.resources.Res
import top.app.market.resources.game_category_all
import top.app.market.resources.game_category_strategy
import top.app.market.resources.game_category_action
import top.app.market.resources.game_category_racing
import top.app.market.resources.game_category_rpg
import top.app.market.resources.game_category_card
import top.app.market.resources.game_category_fighting
import top.app.market.resources.game_category_kids
import top.app.market.resources.game_category_casual
import top.app.market.resources.game_category_flight
import top.app.market.resources.game_category_runner
import top.app.market.resources.category_education
import top.app.market.resources.category_media
import top.app.market.resources.category_office
import top.app.market.resources.category_shopping
import top.app.market.resources.category_social
import top.app.market.resources.category_sports
import top.app.market.resources.category_tools
import top.app.market.resources.nav_apps
import top.app.market.resources.nav_games
import top.app.market.resources.no_results
import top.app.market.resources.open
import top.app.market.resources.reserve
import top.app.market.resources.ranking_hot
import top.app.market.resources.ranking_new
import top.app.market.resources.ranking_sell
import top.app.market.resources.retry
import top.app.market.resources.update
import top.app.market.ui.component.AppRow
import top.app.market.ui.component.LoadingBox
import top.app.market.ui.component.MainTabScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.model.AppActionKind
import top.app.market.ui.util.installActionText
import top.app.market.viewmodel.CategoryViewModel
import top.app.market.viewmodel.of
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun CategoryTab(
    category: AppCategory,
    viewModel: CategoryViewModel,
    bottomPadding: Dp,
    onOpenDetail: (MarketAppInfo) -> Unit,
    isCurrentPage: Boolean = true,
) {
    val sections by viewModel.sectionStates.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val categorySource by viewModel.categorySource.collectAsStateWithLifecycle()
    val downloadStates = viewModel.downloadStates.collectAsStateWithLifecycle()
    val state = sections.of(category, selection)
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val dynamicOptions = categorySource == AppSource.OPPO
    val waitingForOptions = dynamicOptions && options[category] == null
    val optionList = options[category].orEmpty()

    // 切到本页（或切换子分类 / 榜单）时才发起首屏请求，避免未访问的页签空耗网络
    LaunchedEffect(category, selection, isCurrentPage) {
        if (isCurrentPage) viewModel.ensureLoaded(category)
    }
    LaunchedEffect(listState, category, selection) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= 0 && info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .collect { atBottom -> if (atBottom) viewModel.loadMore(category) }
    }
    LaunchedEffect(state.epoch, selection) {
        if (state.epoch > 0) listState.scrollToItem(0)
    }

    val installText = installActionText()
    val updateText = stringResource(Res.string.update)
    val openText = stringResource(Res.string.open)
    val reserveText = stringResource(Res.string.reserve)
    val noResults = stringResource(Res.string.no_results)
    val retryText = stringResource(Res.string.retry)
    val title = stringResource(if (category == AppCategory.GAMES) Res.string.nav_games else Res.string.nav_apps)

    MainTabScaffold(title = title) { topPadding, backdropModifier, scrollBehavior ->
        val contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = topPadding + PageVerticalPadding,
            bottom = bottomPadding + PageVerticalPadding,
        )
        Crossfade(
            targetState = (state.loading && state.items.isEmpty()) || (waitingForOptions && state.errorMessage.isEmpty()),
            modifier = Modifier.fillMaxSize().then(backdropModifier),
            label = "category",
        ) { fullScreenLoading ->
            if (fullScreenLoading) {
                LoadingBox(Modifier.fillMaxSize().padding(contentPadding))
            } else {
                PullToRefresh(
                    isRefreshing = state.loading && state.items.isNotEmpty(),
                    onRefresh = { viewModel.refresh(category) },
                    pullToRefreshState = pullToRefreshState,
                    contentPadding = PaddingValues(top = topPadding),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = contentPadding,
                    ) {
                        // 子分类取自小米分类体系；华为只有单一应用榜
                        if (category == AppCategory.APPS && !dynamicOptions && categorySource != AppSource.HUAWEI) {
                            item(key = "sub-categories") {
                                SelectionChips(
                                    items = AppSubCategory.entries,
                                    selected = selection.appSubCategory,
                                    label = { stringResource(it.labelRes) },
                                    onSelect = viewModel::selectSubCategory,
                                )
                            }
                        }
                        // 游戏：TapTap 提供榜单，小米提供细分类，华为只有单一游戏榜
                    if (dynamicOptions && optionList.isNotEmpty()) {
                        item(key = "options") {
                            val selectedId = if (category == AppCategory.APPS) selection.appOption else selection.gameOption
                            SelectionChips(
                                items = optionList,
                                selected = optionList.firstOrNull { it.id == selectedId } ?: optionList.first(),
                                label = { it.name },
                                onSelect = { viewModel.selectOption(category, it.id) },
                            )
                        }
                    }
                        if (category == AppCategory.GAMES && categorySource == AppSource.TAPTAP) {
                            item(key = "rankings") {
                                SelectionChips(
                                    items = GameRanking.entries,
                                    selected = selection.gameRanking,
                                    label = { stringResource(it.labelRes) },
                                    onSelect = viewModel::selectGameRanking,
                                )
                            }
                        }
                        if (category == AppCategory.GAMES && categorySource == AppSource.XIAOMI) {
                            item(key = "game-sub-categories") {
                                SelectionChips(
                                    items = GameSubCategory.entries,
                                    selected = selection.gameSubCategory,
                                    label = { stringResource(it.labelRes) },
                                    onSelect = viewModel::selectGameSubCategory,
                                )
                            }
                        }
                        if (state.errorMessage.isNotEmpty()) {
                            item(key = "error") {
                                Text(
                                    text = state.errorMessage,
                                    color = MiuixTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                                Text(
                                    text = retryText,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp, vertical = 8.dp)
                                        .clickable { viewModel.refresh(category) },
                                )
                            }
                        } else if (!state.loading && state.items.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = noResults,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                        }
                        items(state.items, key = { it.app.packageName }) { item ->
                            val app = item.app
                            val packageName = app.packageName
                            val downloadState by remember(packageName) {
                                derivedStateOf { downloadStates.value[packageName] }
                            }
                            AppRow(
                                app = app,
                                modifier = Modifier,
                                actionText = when (item.actionKind) {
                                    AppActionKind.INSTALL -> installText
                                    AppActionKind.UPDATE -> updateText
                                    AppActionKind.OPEN -> openText
                                    AppActionKind.RESERVE -> reserveText
                                },
                                actionKind = item.actionKind,
                                downloadState = downloadState,
                                onOpenDetail = { onOpenDetail(app) },
                                onAction = { viewModel.onAction(item) },
                                onResumeDownload = { viewModel.download(app, item.actionKind == AppActionKind.UPDATE) },
                                onInstallDownloaded = viewModel::installDownloaded,
                                onCancel = viewModel::cancelDownload,
                            )
                        }
                        if (state.loadingMore) {
                            item(key = "loadmore") { LoadingBox() }
                        }
                    }
                }
            }
        }
    }
}

// 不用横向滚动：Compose Desktop 的 horizontalScroll 不响应鼠标拖动，换行排布在桌面 / 手机都能看全所有分类
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SelectionChips(
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            Text(
                text = label(item),
                style = MiuixTheme.textStyles.body1,
                color = if (isSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .squircleSurface(
                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer,
                        cornerRadius = 14.dp,
                    )
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

private val GameRanking.labelRes: StringResource
    get() = when (this) {
        GameRanking.HOT -> Res.string.ranking_hot
        GameRanking.NEW -> Res.string.ranking_new
        GameRanking.SELL -> Res.string.ranking_sell
    }

private val AppSubCategory.labelRes: StringResource
    get() = when (this) {
        AppSubCategory.TOOLS -> Res.string.category_tools
        AppSubCategory.MEDIA -> Res.string.category_media
        AppSubCategory.SOCIAL -> Res.string.category_social
        AppSubCategory.OFFICE -> Res.string.category_office
        AppSubCategory.EDUCATION -> Res.string.category_education
        AppSubCategory.SHOPPING -> Res.string.category_shopping
        AppSubCategory.SPORTS -> Res.string.category_sports
    }

private val GameSubCategory.labelRes: StringResource
    get() = when (this) {
        GameSubCategory.ALL -> Res.string.game_category_all
        GameSubCategory.STRATEGY -> Res.string.game_category_strategy
        GameSubCategory.ACTION -> Res.string.game_category_action
        GameSubCategory.RACING -> Res.string.game_category_racing
        GameSubCategory.RPG -> Res.string.game_category_rpg
        GameSubCategory.CARD -> Res.string.game_category_card
        GameSubCategory.FIGHTING -> Res.string.game_category_fighting
        GameSubCategory.KIDS -> Res.string.game_category_kids
        GameSubCategory.CASUAL -> Res.string.game_category_casual
        GameSubCategory.FLIGHT -> Res.string.game_category_flight
        GameSubCategory.RUNNER -> Res.string.game_category_runner
    }
