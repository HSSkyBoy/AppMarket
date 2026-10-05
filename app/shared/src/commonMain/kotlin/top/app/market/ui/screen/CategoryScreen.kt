package top.app.market.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.resources.Res
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
import top.yukonga.miuix.kmp.basic.Text
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
    val subCategory by viewModel.appSubCategory.collectAsStateWithLifecycle()
    val ranking by viewModel.gameRanking.collectAsStateWithLifecycle()
    val categorySource by viewModel.categorySource.collectAsStateWithLifecycle()
    val downloadStates = viewModel.downloadStates.collectAsStateWithLifecycle()
    val state = sections.of(category, subCategory, ranking)
    val listState = rememberLazyListState()

    // 切到本页（或切换子分类）时才发起首屏请求，避免未访问的页签空耗网络
    LaunchedEffect(category, subCategory, ranking, isCurrentPage) {
        if (isCurrentPage) viewModel.ensureLoaded(category, subCategory, ranking)
    }
    LaunchedEffect(listState, category, subCategory, ranking) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= 0 && info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .collect { atBottom -> if (atBottom) viewModel.loadMore(category, subCategory, ranking) }
    }
    LaunchedEffect(state.epoch, subCategory, ranking) {
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
            targetState = state.loading && state.items.isEmpty(),
            modifier = Modifier.fillMaxSize().then(backdropModifier),
            label = "category",
        ) { fullScreenLoading ->
            if (fullScreenLoading) {
                LoadingBox(Modifier.fillMaxSize().padding(contentPadding))
            } else {
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
                    if (category == AppCategory.APPS && categorySource != AppSource.HUAWEI) {
                        item(key = "sub-categories") {
                            SelectionChips(
                                items = AppSubCategory.entries,
                                selected = subCategory,
                                labelRes = { it.labelRes },
                                onSelect = viewModel::selectSubCategory,
                            )
                        }
                    }
                    // 仅 TapTap 提供游戏榜单；小米游戏为单一分类
                    if (category == AppCategory.GAMES && categorySource == AppSource.TAPTAP) {
                        item(key = "rankings") {
                            SelectionChips(
                                items = GameRanking.entries,
                                selected = ranking,
                                labelRes = { it.labelRes },
                                onSelect = viewModel::selectGameRanking,
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
                                    .clickable { viewModel.retry(category, subCategory, ranking) },
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

@Composable
private fun <T> SelectionChips(
    items: List<T>,
    selected: T,
    labelRes: (T) -> StringResource,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            Text(
                text = stringResource(labelRes(item)),
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
