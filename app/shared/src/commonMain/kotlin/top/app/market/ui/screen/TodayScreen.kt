package top.app.market.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeaturedItem
import top.app.market.resources.Res
import top.app.market.resources.golden_award
import top.app.market.resources.nav_today
import top.app.market.resources.num_updates_pending
import top.app.market.resources.view
import top.app.market.ui.component.AppAsyncImage
import top.app.market.ui.component.AppButton
import top.app.market.ui.component.AppIcon
import top.app.market.ui.component.HidingMainTabScaffold
import top.app.market.ui.component.LoadingBox
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.theme.LocalEnableFloatingBottomBar
import top.app.market.ui.util.appDisplayName
import top.app.market.ui.util.todayAwardLabel
import top.app.market.ui.util.todayAppSummary
import top.app.market.viewmodel.TodayViewModel
import top.app.market.viewmodel.UpdatesViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.ceil

private val PaperCardRadius = 16.dp
private val TodayGridMinCellWidth = 320.dp

@Composable
fun TodayTab(
    viewModel: TodayViewModel,
    bottomPadding: Dp,
    onClickArticle: (TodayArticle) -> Unit,
    updatesViewModel: UpdatesViewModel? = null,
    onClickViewUpdates: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState = updatesViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val listState = rememberLazyGridState()
    val currentState by rememberUpdatedState(state)
    val pendingUpdates =
        if (updateState != null && !updateState.loading) updateState.updates else emptyList()
    val showUpdatesCard = pendingUpdates.isNotEmpty()
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current

    LaunchedEffect(showUpdatesCard) {
        if (listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset == 0) {
            listState.requestScrollToItem(0)
        }
    }

    LaunchedEffect(listState, viewModel) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearEnd = lastVisible >= 0 &&
                    layoutInfo.totalItemsCount > 0 &&
                    lastVisible >= layoutInfo.totalItemsCount - 3
            nearEnd &&
                    !currentState.feedLoading &&
                    !currentState.feedLoadingMore &&
                    currentState.feed.hasMore &&
                    currentState.feedError.isBlank()
        }
            .distinctUntilChanged()
            .collect { shouldLoadMore -> if (shouldLoadMore) viewModel.loadMore() }
    }

    HidingMainTabScaffold(
        title = stringResource(Res.string.nav_today),
        bottomTransitionHeight = if (enableFloatingBottomBar) {
            bottomPadding + PageVerticalPadding
        } else {
            0.dp
        },
    ) { topPadding, backdropModifier, scrollBehavior ->
        val initialLoading = state.feedLoading && state.feed.items.isEmpty()
        Crossfade(
            targetState = initialLoading,
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .then(backdropModifier),
            label = "today",
        ) { loading ->
            if (loading) {
                LoadingBox(
                    Modifier.fillMaxSize().padding(
                        top = topPadding + PageVerticalPadding,
                        bottom = bottomPadding + PageVerticalPadding,
                    ),
                )
                return@Crossfade
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(TodayGridMinCellWidth),
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = topPadding + PageVerticalPadding,
                    bottom = bottomPadding + PageVerticalPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (showUpdatesCard) {
                    item(
                        key = "updates",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        var pendingCardVisible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { pendingCardVisible = true }
                        AnimatedVisibility(
                            visible = pendingCardVisible,
                            enter = fadeIn(tween(220)) +
                                    expandVertically(tween(300), expandFrom = Alignment.Top),
                        ) {
                            PendingUpdatesCard(
                                updates = pendingUpdates,
                                onClick = onClickViewUpdates,
                                modifier = Modifier.padding(bottom = 20.dp),
                            )
                        }
                    }
                }
                items(state.feed.items, key = { "feed-${it.rId.ifBlank { it.articleLink }}" }) { item ->
                    FeaturedArticleCard(
                        item = item,
                        fullCoverOverlay = state.source.capabilities.todayFullCoverOverlay,
                        modifier = Modifier
                            .animateItem(placementSpec = null)
                            .padding(bottom = 20.dp),
                    ) {
                        openArticle(item, onClickArticle)
                    }
                }
                if (state.feedLoading || state.feedLoadingMore) {
                    item(
                        key = "feed-loading",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            InfiniteProgressIndicator(size = 24.dp)
                        }
                    }
                }
                if (state.feedError.isNotBlank()) {
                    item(
                        key = "feed-error",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Text(
                            text = state.feedError,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = viewModel::retryFeed)
                                .padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private fun openArticle(item: TodayFeaturedItem, onClickArticle: (TodayArticle) -> Unit) {
    val articleId = item.rId.ifBlank { return }
    onClickArticle(
        TodayArticle(
            rId = articleId,
            title = item.title,
            awardName = item.awardName,
            headerImage = item.coverImage,
            richTextHtml = item.summary,
            app = item.app ?: item.apps.singleOrNull(),
            apps = item.apps,
            showTitleLabel = item.showTitleLabel,
            showCoverText = item.showCoverText,
        ),
    )
}

@Composable
private fun PendingUpdatesCard(
    updates: List<MarketAppInfo>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstRow = remember(updates) { updates.filterIndexed { index, _ -> index % 2 == 0 } }
    val secondRow = remember(updates) { updates.filterIndexed { index, _ -> index % 2 == 1 } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val rowCapacity = ((maxWidth - 8.dp) / 80.dp).toInt().coerceAtLeast(1)
        val fitsOneRow = updates.size <= rowCapacity
        val singleRowLayout = updates.size <= rowCapacity * 2

        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = PaperCardRadius,
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(Res.string.num_updates_pending, updates.size),
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        style = MiuixTheme.textStyles.title3.copy(lineHeight = 24.sp),
                    )
                    AppButton(
                        text = stringResource(Res.string.view),
                        onClick = onClick,
                        cornerRadius = 100.dp,
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    )
                }
                Spacer(Modifier.height(1.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                ) {
                    when {
                        fitsOneRow -> StaticIconRow(updates)
                        singleRowLayout -> ScrollingIconRow(updates, moveLeft = true)
                        else -> Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            ScrollingIconRow(firstRow, moveLeft = true)
                            ScrollingIconRow(secondRow, moveLeft = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaticIconRow(
    apps: List<MarketAppInfo>,
    modifier: Modifier = Modifier,
    iconSize: Dp = 64.dp,
    itemWidth: Dp = 80.dp,
    rowHeight: Dp = 64.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(start = 16.dp - (itemWidth - iconSize) / 2),
        horizontalArrangement = Arrangement.Start,
    ) {
        apps.forEach { app ->
            Box(
                modifier = Modifier.size(width = itemWidth, height = rowHeight),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    url = app.icon,
                    contentDescription = appDisplayName(app.displayName, app.source),
                    size = iconSize,
                )
            }
        }
    }
}

@Composable
internal fun ScrollingIconRow(
    apps: List<MarketAppInfo>,
    moveLeft: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 64.dp,
    itemWidth: Dp = 80.dp,
    rowHeight: Dp = 64.dp,
) {
    if (apps.isEmpty()) {
        Box(modifier.fillMaxWidth().height(rowHeight))
        return
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        val minLoopItems = ceil(maxWidth / itemWidth).toInt().coerceAtLeast(1)
        val loopApps = remember(apps, minLoopItems) {
            if (apps.size >= minLoopItems) {
                apps
            } else {
                List(minLoopItems) { index -> apps[index % apps.size] }
            }
        }

        val transition = rememberInfiniteTransition(label = if (moveLeft) "left" else "right")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = (loopApps.size * 2500).coerceAtLeast(12_000), // 调整速度
                    easing = LinearEasing,
                ),
            ),
            label = "progress",
        )

        val cycleWidth = itemWidth * loopApps.size
        val cycleWidthPx = with(LocalDensity.current) { cycleWidth.toPx() }

        repeat(2) { copyIndex ->
            Row(
                modifier = Modifier
                    .wrapContentWidth(unbounded = true, align = Alignment.Start)
                    .width(cycleWidth)
                    .graphicsLayer {
                        translationX = if (moveLeft) {
                            copyIndex * cycleWidthPx - progress * cycleWidthPx
                        } else {
                            (copyIndex - 1) * cycleWidthPx + progress * cycleWidthPx
                        }
                    },
            ) {
                loopApps.forEachIndexed { index, app ->
                    Box(
                        modifier = Modifier.size(width = itemWidth, height = rowHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(
                            url = app.icon,
                            contentDescription = if (copyIndex == 0 && index < apps.size) {
                                appDisplayName(app.displayName, app.source)
                            } else {
                                null
                            },
                            size = iconSize,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdaptiveAppIconRow(
    apps: List<MarketAppInfo>,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
    itemSpacing: Dp = 16.dp,
) {
    BoxWithConstraints(modifier = modifier.height(iconSize)) {
        val requiredWidth = if (apps.isEmpty()) {
            0.dp
        } else {
            iconSize * apps.size + itemSpacing * (apps.size - 1)
        }
        if (requiredWidth > maxWidth) {
            ScrollingIconRow(
                apps = apps,
                moveLeft = true,
                iconSize = iconSize,
                itemWidth = iconSize + itemSpacing,
                rowHeight = iconSize,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(iconSize),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                apps.forEach { app ->
                    AppIcon(
                        url = app.icon,
                        contentDescription = appDisplayName(app.displayName, app.source),
                        size = iconSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturedArticleCard(
    item: TodayFeaturedItem,
    modifier: Modifier = Modifier,
    fullCoverOverlay: Boolean = false,
    onClick: () -> Unit,
) {
    val fallbackColor = MiuixTheme.colorScheme.primary
    var gradientColor by remember(item.coverImage, fallbackColor) { mutableStateOf(fallbackColor) }
    var coverRatio by remember(item.coverImage) { mutableStateOf<Float?>(null) }
    val app = item.app ?: item.apps.singleOrNull()
    val hasCover = item.coverImage.isNotBlank()
    val coverSizeModifier = Modifier
        .fillMaxWidth()
        .then(
            if (coverRatio != null) {
                Modifier.aspectRatio(coverRatio!!)
            } else {
                Modifier.height(330.dp)
            }
        )

    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = PaperCardRadius,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = gradientColor,
            contentColor = MiuixTheme.colorScheme.onPrimary,
        ),
        onClick = onClick,
    ) {
        // 小米今日整卡覆盖：两行文案与应用行都叠加在封面上；其余来源保持两行文案覆盖 + 应用行附加
        if (fullCoverOverlay && hasCover) {
            Box(coverSizeModifier) {
                FeaturedCoverImage(
                    item = item,
                    modifier = Modifier.fillMaxSize(),
                    onGradientColor = { gradientColor = it },
                    onCoverSize = { width, height -> coverRatio = width.toFloat() / height },
                )
                if (item.showCoverText) {
                    CoverScrim(gradientColor)
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomStart)) {
                        EditorialFeatureContent(item)
                        if (app != null) AppFeatureContent(app)
                    }
                } else if (app != null) {
                    AppFeatureContent(app, appBarColor = gradientColor)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                if (hasCover) {
                    Box(coverSizeModifier) {
                        FeaturedCoverImage(
                            item = item,
                            modifier = Modifier.fillMaxSize(),
                            onGradientColor = { gradientColor = it },
                            onCoverSize = { width, height -> coverRatio = width.toFloat() / height },
                        )
                        if (item.showCoverText) {
                            CoverScrim(gradientColor)
                            EditorialFeatureContent(item, Modifier.align(Alignment.BottomStart))
                        }
                    }
                }
                if (app != null) {
                    AppFeatureContent(
                        app = app,
                        appBarColor = gradientColor.takeUnless { hasCover && item.showCoverText },
                    )
                } else if (item.showCoverText) {
                    EditorialFeatureContent(item)
                }
            }
        }
    }
}

@Composable
private fun FeaturedCoverImage(
    item: TodayFeaturedItem,
    modifier: Modifier = Modifier,
    onGradientColor: (Color) -> Unit,
    onCoverSize: (width: Int, height: Int) -> Unit,
) {
    AppAsyncImage(
        url = item.coverImage,
        contentDescription = item.title,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        onDominantColor = onGradientColor,
        onLoaded = { image ->
            if (image.width > 0 && image.height > 0) {
                onCoverSize(image.width, image.height)
            }
        },
        showLoadingIndicator = true,
        loadingIndicatorAlignment = BiasAlignment(0f, -0.25f),
        loadingIndicatorColor = MiuixTheme.colorScheme.onPrimary,
    )
}

@Composable
private fun CoverScrim(color: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to color.copy(alpha = 0f),
                    0.54f to color.copy(alpha = 0f),
                    0.76f to color,
                    1f to color,
                ),
            ),
    )
}

@Composable
private fun EditorialFeatureContent(
    item: TodayFeaturedItem,
    modifier: Modifier = Modifier,
) {
    val label = item.awardName.ifBlank { stringResource(Res.string.golden_award) }
    val appName = (item.app ?: item.apps.singleOrNull())?.displayName
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        if (item.showTitleLabel) {
            FeatureLabel(todayAwardLabel(label, item.title, appName))
        }
        FeatureTitle(item.summary.ifBlank { item.title })
    }
}


@Composable
private fun AppFeatureContent(
    app: MarketAppInfo,
    appBarColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(appBarColor ?: MiuixTheme.colorScheme.onBackground.copy(alpha = 0.10f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            url = app.icon,
            contentDescription = appDisplayName(app.displayName, app.source),
            size = 48.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = appDisplayName(app.displayName, app.source),
                color = MiuixTheme.colorScheme.onPrimary,
                style = MiuixTheme.textStyles.headline1.copy(
                    fontWeight = FontWeight.Medium,
                    lineHeight = 22.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FeatureSummary(app.todayAppSummary())
        }
    }
}

@Composable
private fun FeatureLabel(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
        style = MiuixTheme.textStyles.body2.copy(lineHeight = 16.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.graphicsLayer {
            blendMode = BlendMode.Plus
        }
    )
}

@Composable
private fun FeatureTitle(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onPrimary,
        style = MiuixTheme.textStyles.title3.copy(lineHeight = 24.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FeatureSummary(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.4f),
        style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.graphicsLayer {
            blendMode = BlendMode.Plus
        }
    )
}
