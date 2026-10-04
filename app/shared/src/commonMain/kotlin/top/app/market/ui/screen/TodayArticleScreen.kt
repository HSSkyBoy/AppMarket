package top.app.market.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayArticleBlock
import top.app.market.resources.Res
import top.app.market.resources.golden_award
import top.app.market.ui.component.AppAsyncImage
import top.app.market.ui.component.AppButton
import top.app.market.ui.component.AppIcon
import top.app.market.ui.component.CollapsibleMarketScaffold
import top.app.market.ui.component.LoadingBox
import top.app.market.ui.component.RelatedAppsList
import top.app.market.ui.util.allowColorSampling
import top.app.market.ui.util.appDisplayName
import top.app.market.ui.util.dominantImageColor
import top.app.market.ui.util.installActionText
import top.app.market.ui.util.todayAwardLabel
import top.app.market.ui.util.todayAppSummary
import top.app.market.viewmodel.TodayViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.time.Duration.Companion.milliseconds

// 头图预载最长等待，超时照常揭示
private const val ARTICLE_HEADER_IMAGE_WAIT_MS = 8_000L

@Composable
fun TodayArticleScreen(
    rId: String,
    viewModel: TodayViewModel,
    onOpenApp: (MarketAppInfo) -> Unit,
    onBack: () -> Unit,
    applyWindowInsets: Boolean = true,
) {
    LaunchedEffect(rId) { viewModel.loadArticle(rId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val article = state.article?.takeIf { it.rId == rId }
    val feedItem = state.feed.items.firstOrNull { it.rId == rId }
    val lazyListState = rememberLazyListState()
    val title = feedItem?.title?.ifBlank { article?.title.orEmpty() }
        ?: article?.title.orEmpty()
    val summary = feedItem?.summary.orEmpty()
    val awardName = feedItem?.awardName.orEmpty()
        .ifBlank { article?.awardName.orEmpty() }
        .ifBlank { stringResource(Res.string.golden_award) }
    val appName = feedItem?.app?.displayName
        ?: feedItem?.apps?.singleOrNull()?.displayName
        ?: article?.app?.displayName
        ?: article?.apps?.singleOrNull()?.displayName
    val awardLabel = todayAwardLabel(awardName, title, appName)
    val collapsedTitle = awardLabel
    val defaultAccentColor = MiuixTheme.colorScheme.primary
    var accentColor by remember(article?.headerImage, defaultAccentColor) {
        mutableStateOf(defaultAccentColor)
    }
    val isDeveloperLecture = rId.startsWith("developer-lecture:") ||
            (article?.showCoverText ?: feedItem?.showCoverText) == false

    // 头图预载完成前整页保持加载态；正文一旦揭示不再回退
    val platformContext = LocalPlatformContext.current
    var headerImageReady by remember(article?.headerImage) {
        mutableStateOf(article?.headerImage.isNullOrBlank())
    }
    LaunchedEffect(article?.headerImage) {
        val url = article?.headerImage?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        val preview = article.headerImagePreview.takeIf { it.isNotBlank() && it != url }
        val imageLoader = SingletonImageLoader.get(platformContext)
        withTimeoutOrNull(ARTICLE_HEADER_IMAGE_WAIT_MS.milliseconds) {
            coroutineScope {
                val requests = listOfNotNull(preview, url).map { candidate ->
                    async {
                        imageLoader.execute(
                            ImageRequest.Builder(platformContext)
                                .data(candidate)
                                .allowColorSampling()
                                .build()
                        )
                    }
                }
                val first = select { requests.forEach { request -> request.onAwait { it } } }
                requests.forEach { it.cancel() }
                first.image?.let { image -> dominantImageColor(image)?.let { accentColor = it } }
            }
        }
        headerImageReady = true
    }
    var contentRevealed by remember(rId) { mutableStateOf(false) }
    LaunchedEffect(article, headerImageReady) {
        val current = article ?: return@LaunchedEffect
        if (!contentRevealed && (current.apps.isEmpty() || headerImageReady)) {
            contentRevealed = true
        }
    }

    CollapsibleMarketScaffold(
        title = collapsedTitle,
        onBack = onBack,
        expandedContentBehindToolbar = true,
        expandedNavigationIconTint = MiuixTheme.colorScheme.onPrimary,
        showNavigationIcon = !isDeveloperLecture,
        applyWindowInsets = applyWindowInsets,
        expandedContent = {
            article?.takeIf { contentRevealed }?.let { currentArticle ->
                when (currentArticle.apps.size) {
                    0 -> if (currentArticle.headerImage.isNotBlank()) {
                        EditorialArticleHeader(
                            imageUrl = currentArticle.headerImage,
                            placeholderImageUrl = currentArticle.headerImagePreview,
                            awardLabel = awardLabel,
                            title = title,
                            reason = summary,
                            showTitleLabel = currentArticle.showTitleLabel,
                            showCoverText = currentArticle.showCoverText,
                            accentColor = accentColor,
                            onAccentColor = { accentColor = it },
                        )
                    }

                    1 -> SingleArticleHeader(
                        imageUrl = currentArticle.headerImage,
                        placeholderImageUrl = currentArticle.headerImagePreview,
                        awardLabel = awardLabel,
                        title = title,
                        reason = summary,
                        showTitleLabel = currentArticle.showTitleLabel,
                        showCoverText = currentArticle.showCoverText,
                        app = currentArticle.apps.first(),
                        accentColor = accentColor,
                        onAccentColor = { accentColor = it },
                        onOpenApp = onOpenApp
                    )

                    else -> GroupArticleHeader(
                        imageUrl = currentArticle.headerImage,
                        placeholderImageUrl = currentArticle.headerImagePreview,
                        title = title,
                        summary = summary,
                        apps = currentArticle.apps,
                        accentColor = accentColor,
                        onAccentColor = { accentColor = it },
                    )
                }
            }
        },
    ) { safePadding, backdropModifier, scrollBehavior ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .then(backdropModifier),
        ) {
            when {
                state.articleLoading ||
                        (article == null && state.articleError.isBlank()) ||
                        (article != null && !contentRevealed) ->
                    ArticleLoadingState(safePadding = safePadding)

                article == null -> ArticleErrorState(
                    message = state.articleError,
                    safePadding = safePadding,
                )

                else -> {
                    val bodyBlocks = remember(article.blocks, article.apps) {
                        orderedArticleBlocks(article).withoutBoundaryImages()
                    }
                    ArticleContent(
                        bodyBlocks = bodyBlocks,
                        safePadding = safePadding,
                        lazyListState = lazyListState,
                        scrollBehavior = scrollBehavior,
                        onOpenApp = onOpenApp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleLoadingState(safePadding: PaddingValues) {
    val layoutDirection = LocalLayoutDirection.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(
                start = safePadding.calculateStartPadding(layoutDirection),
                top = safePadding.calculateTopPadding(),
                end = safePadding.calculateEndPadding(layoutDirection),
                bottom = safePadding.calculateBottomPadding(),
            ),
    ) {
        LoadingBox(Modifier.fillMaxSize())
    }
}

@Composable
private fun ArticleErrorState(message: String, safePadding: PaddingValues) {
    val layoutDirection = LocalLayoutDirection.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(
                start = safePadding.calculateStartPadding(layoutDirection),
                top = safePadding.calculateTopPadding(),
                end = safePadding.calculateEndPadding(layoutDirection),
                bottom = safePadding.calculateBottomPadding(),
            ),
    ) {
        Text(
            text = message,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.main,
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
        )
    }
}

@Composable
private fun ArticleContent(
    bodyBlocks: List<ArticleBlock>,
    safePadding: PaddingValues,
    lazyListState: LazyListState,
    scrollBehavior: ScrollBehavior,
    onOpenApp: (MarketAppInfo) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = safePadding.calculateStartPadding(layoutDirection),
            top = safePadding.calculateTopPadding(),
            end = safePadding.calculateEndPadding(layoutDirection),
            bottom = safePadding.calculateBottomPadding(),
        ),
    ) {
        itemsIndexed(bodyBlocks, key = { index, _ -> index }) { index, block ->
            ArticleBlockContent(
                block = block,
                onOpenApp = onOpenApp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(
                        top = when {
                            index == 0 -> 24.dp
                            block is ArticleBlock.Heading -> 32.dp
                            else -> 16.dp
                        },
                        bottom = if (index == bodyBlocks.lastIndex) 24.dp else 0.dp,
                    ),
            )
        }
    }
}

@Composable
private fun EditorialArticleHeader(
    imageUrl: String,
    placeholderImageUrl: String,
    awardLabel: String,
    title: String,
    reason: String,
    showTitleLabel: Boolean,
    showCoverText: Boolean,
    accentColor: Color,
    onAccentColor: (Color) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(447.dp)
            .clipToBounds()
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        AppAsyncImage(
            url = imageUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!showCoverText) {
                        scaleX = 1.03f
                        scaleY = 1.03f
                    }
                },
            onDominantColor = onAccentColor,
            placeholderMemoryCacheUrl = placeholderImageUrl.takeIf { it.isNotBlank() && it != imageUrl },
        )
        if (showCoverText) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to accentColor.copy(alpha = 0f),
                            1f to accentColor,
                        ),
                    )
                    .padding(top = 32.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                if (showTitleLabel) {
                    Text(
                        text = awardLabel,
                        color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                        style = MiuixTheme.textStyles.footnote1.copy(lineHeight = 16.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { blendMode = BlendMode.Plus },
                    )
                }
                Text(
                    text = reason.ifBlank { title },
                    color = MiuixTheme.colorScheme.onPrimary,
                    style = MiuixTheme.textStyles.title2.copy(lineHeight = 30.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SingleArticleHeader(
    imageUrl: String,
    placeholderImageUrl: String,
    awardLabel: String,
    title: String,
    app: MarketAppInfo,
    reason: String,
    showTitleLabel: Boolean,
    showCoverText: Boolean,
    accentColor: Color,
    onAccentColor: (Color) -> Unit,
    onOpenApp: (MarketAppInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(447.dp)
                .clipToBounds()
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        ) {
            AppAsyncImage(
                url = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (!showCoverText) {
                            scaleX = 1.03f
                            scaleY = 1.03f
                        }
                    },
                onDominantColor = onAccentColor,
                placeholderMemoryCacheUrl = placeholderImageUrl.takeIf { it.isNotBlank() && it != imageUrl },
            )
            if (showCoverText) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to accentColor.copy(alpha = 0f),
                                1f to accentColor,
                            ),
                        )
                        .padding(top = 32.dp)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    if (showTitleLabel) {
                        Text(
                            text = awardLabel,
                            color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                            style = MiuixTheme.textStyles.footnote1.copy(lineHeight = 16.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                blendMode = BlendMode.Plus
                            }
                        )
                    }
                    Text(
                        text = reason.ifBlank { title },
                        color = MiuixTheme.colorScheme.onPrimary,
                        style = MiuixTheme.textStyles.title2.copy(lineHeight = 30.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        SingleArticleAppBar(
            app = app,
            accentColor = accentColor,
            showInstall = true,
            onOpenApp = onOpenApp,
        )
    }
}

@Composable
private fun GroupArticleHeader(
    imageUrl: String,
    placeholderImageUrl: String,
    title: String,
    summary: String,
    apps: List<MarketAppInfo>,
    accentColor: Color,
    onAccentColor: (Color) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(509.dp)
            .background(accentColor),
    ) {
        if (imageUrl.isNotBlank()) {
            AppAsyncImage(
                url = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                onDominantColor = onAccentColor,
                placeholderMemoryCacheUrl = placeholderImageUrl.takeIf { it.isNotBlank() && it != imageUrl },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to accentColor.copy(alpha = 0f),
                        0.5f to accentColor,
                    ),
                )
                .padding(top = 32.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
//                Text(
//                    text = "电子笔记应用",
//                    color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
//                    style = MiuixTheme.textStyles.footnote1,
//                )
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onPrimary,
                style = MiuixTheme.textStyles.title3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        blendMode = BlendMode.Plus
                    }
                )
            }

            AdaptiveAppIconRow(
                apps = apps,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SingleArticleAppBar(
    app: MarketAppInfo,
    accentColor: Color,
    showInstall: Boolean,
    onOpenApp: (MarketAppInfo) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accentColor)
            .clickable { onOpenApp(app) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                url = app.icon,
                contentDescription = appDisplayName(app.displayName, app.source),
                size = 48.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appDisplayName(app.displayName, app.source),
                    color = MiuixTheme.colorScheme.onPrimary,
                    style = MiuixTheme.textStyles.headline1.copy(lineHeight = 22.sp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.todayAppSummary(),
                    color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.4f),
                    style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        blendMode = BlendMode.Plus
                    }
                )
            }
        }

        if (showInstall) {
            ArticleInstallButton(
                onClick = { onOpenApp(app) },
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                    contentColor = MiuixTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun ArticleBlockContent(
    block: ArticleBlock,
    onOpenApp: (MarketAppInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is ArticleBlock.Heading -> Text(
            text = block.value,
            color = MiuixTheme.colorScheme.onBackground,
            style = MiuixTheme.textStyles.title2.copy(lineHeight = 30.sp),
            modifier = modifier,
        )

        is ArticleBlock.Paragraph -> Text(
            text = block.value,
            color = MiuixTheme.colorScheme.onBackground,
            style = MiuixTheme.textStyles.paragraph.copy(lineHeight = 22.sp),
            modifier = modifier,
        )

        is ArticleBlock.Image -> {
            // 只在服务端给了宽高时占位；富文本里的图没有尺寸，锁死比例会把竖图裁掉大半
            val ratio = block.width.takeIf { it > 0 }
                ?.let { width -> block.height.takeIf { it > 0 }?.let { width.toFloat() / it } }
            AppAsyncImage(
                url = block.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier)
                    .squircleBorder(0.2.dp, Color.Gray, 16.dp)
                    .squircleClip(16.dp),
            )
        }

        is ArticleBlock.Apps -> ArticleAppRows(
            apps = block.values,
            onOpenApp = onOpenApp,
            modifier = modifier,
        )
    }
}

@Composable
private fun ArticleAppRows(
    apps: List<MarketAppInfo>,
    onOpenApp: (MarketAppInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        RelatedAppsList(
            apps = apps,
            onOpenApp = onOpenApp,
        ) { app ->
            ArticleInstallButton(onClick = { onOpenApp(app) })
        }
    }
}

@Composable
private fun ArticleInstallButton(
    onClick: () -> Unit,
    colors: top.yukonga.miuix.kmp.basic.ButtonColors = ButtonDefaults.buttonColorsPrimary(),
) {
    AppButton(
        text = installActionText(),
        onClick = onClick,
        colors = colors,
        cornerRadius = 100.dp,
        minWidth = 0.dp,
        minHeight = 0.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    )
}

private sealed interface ArticleBlock {
    data class Heading(val value: String) : ArticleBlock
    data class Paragraph(val value: String) : ArticleBlock
    data class Image(
        val url: String,
        val width: Int = 0,
        val height: Int = 0,
    ) : ArticleBlock

    data class Apps(val values: List<MarketAppInfo>) : ArticleBlock
}

private fun List<ArticleBlock>.withoutBoundaryImages(): List<ArticleBlock> {
    val firstImage = indexOfFirst { it is ArticleBlock.Image }
    if (firstImage < 0) return this
    val lastImage = indexOfLast { it is ArticleBlock.Image }
    return filterIndexed { index, _ -> index != firstImage && index != lastImage }
}

private val imageTag =
    Regex("<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
private val strongParagraphTag = Regex(
    "<p\\b[^>]*>\\s*<(?:strong|b)\\b[^>]*>(.*?)</(?:strong|b)>\\s*</p>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val headingOpenTag = Regex("<h[1-6]\\b[^>]*>", RegexOption.IGNORE_CASE)
private val headingCloseTag = Regex("</h[1-6]\\s*>", RegexOption.IGNORE_CASE)
private val breakTag = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val blockBoundaryTag = Regex("</?(?:p|div|li|ul|ol)\\b[^>]*>", RegexOption.IGNORE_CASE)
private val anyTag = Regex("<[^>]+>")
private const val BlockSeparator = "\u0001"
private const val HeadingMarker = "\u0002"
private const val ImageMarker = "\u0003"

private fun orderedArticleBlocks(article: TodayArticle): List<ArticleBlock> {
    val result = mutableListOf<ArticleBlock>()
    val pendingApps = mutableListOf<MarketAppInfo>()
    val featuredPackage = article.apps.singleOrNull()?.packageName
    var skippedHeroBanner = false

    fun flushApps() {
        if (pendingApps.isNotEmpty()) {
            result += ArticleBlock.Apps(pendingApps.toList())
            pendingApps.clear()
        }
    }

    article.blocks.forEach { block ->
        if (block is TodayArticleBlock.App) {
            if (block.value.packageName != featuredPackage) pendingApps += block.value
            return@forEach
        }

        flushApps()
        when (block) {
            is TodayArticleBlock.Banner -> {
                if (!skippedHeroBanner) {
                    skippedHeroBanner = true
                } else if (block.imageUrl.isNotBlank()) {
                    result += ArticleBlock.Image(block.imageUrl, block.width, block.height)
                }
            }

            is TodayArticleBlock.RichText -> result += parseArticleBlocks(block.html)
            is TodayArticleBlock.Image -> if (block.imageUrl.isNotBlank()) {
                result += ArticleBlock.Image(block.imageUrl, block.width, block.height)
            }
        }
    }
    flushApps()
    return result
}

private fun parseArticleBlocks(html: String): List<ArticleBlock> {
    if (html.isBlank()) return emptyList()
    val images = mutableListOf<String>()
    var normalized = strongParagraphTag.replace(html) { match ->
        "$BlockSeparator$HeadingMarker${match.groupValues[1]}$BlockSeparator"
    }
    normalized = imageTag.replace(normalized) { match ->
        val index = images.size
        images += decodeArticleEntities(match.groupValues[1])
        "$BlockSeparator$ImageMarker$index$BlockSeparator"
    }
    normalized = headingOpenTag.replace(normalized, "$BlockSeparator$HeadingMarker")
    normalized = headingCloseTag.replace(normalized, BlockSeparator)
    normalized = breakTag.replace(normalized, "\n")
    normalized = blockBoundaryTag.replace(normalized, BlockSeparator)
    normalized = anyTag.replace(normalized, "")

    val blocks = normalized.split(BlockSeparator).mapNotNull { rawBlock ->
        val value = plainArticleText(rawBlock.removePrefix(HeadingMarker))
        when {
            rawBlock.startsWith(ImageMarker) -> {
                rawBlock.removePrefix(ImageMarker).trim().toIntOrNull()
                    ?.let(images::getOrNull)
                    ?.let { ArticleBlock.Image(it) }
            }

            value.isBlank() -> null
            rawBlock.startsWith(HeadingMarker) || value.looksLikeArticleHeading() -> ArticleBlock.Heading(
                value
            )

            else -> ArticleBlock.Paragraph(value)
        }
    }
    return blocks.ifEmpty {
        plainArticleText(html).takeIf(String::isNotBlank)
            ?.let { listOf(ArticleBlock.Paragraph(it)) }.orEmpty()
    }
}

private fun String.looksLikeArticleHeading(): Boolean =
    length in 2..24 && '\n' !in this && none { it in "。！？!?；;" }

private fun plainArticleText(value: String): String = decodeArticleEntities(value)
    .lines()
    .map(String::trim)
    .filter(String::isNotBlank)
    .joinToString("\n")

private fun decodeArticleEntities(value: String): String = value
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
