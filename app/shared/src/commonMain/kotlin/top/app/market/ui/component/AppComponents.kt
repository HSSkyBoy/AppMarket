package top.app.market.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.isDownloadBlocked
import top.app.market.resources.Res
import top.app.market.resources.collapse_change_log
import top.app.market.resources.expand_change_log
import top.app.market.resources.ignore_once
import top.app.market.resources.ignore_permanent
import top.app.market.resources.no_change_log
import top.app.market.resources.search_ad_tag
import top.app.market.resources.search_quickapp_tag
import top.app.market.ui.model.AppActionKind
import top.app.market.ui.util.allowColorSampling
import top.app.market.ui.util.appDisplayName
import top.app.market.ui.util.appSourceLabel
import top.app.market.ui.util.dominantImageColor
import top.app.market.ui.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import coil3.Image as CoilImage

@Composable
fun AppAsyncImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    colorFilter: androidx.compose.ui.graphics.ColorFilter? = null,
    onDominantColor: ((Color) -> Unit)? = null,
    onLoaded: ((CoilImage) -> Unit)? = null,
    placeholderMemoryCacheUrl: String? = null,
    showLoadingIndicator: Boolean = false,
    loadingIndicatorAlignment: Alignment = Alignment.Center,
    loadingIndicatorColor: Color = Color.Gray,
) {
    val platformContext = LocalPlatformContext.current
    val model = remember(url, platformContext, onDominantColor != null, placeholderMemoryCacheUrl) {
        if (onDominantColor == null && placeholderMemoryCacheUrl == null) {
            url
        } else {
            ImageRequest.Builder(platformContext)
                .data(url)
                .apply {
                    if (onDominantColor != null) allowColorSampling()
                    if (placeholderMemoryCacheUrl != null) placeholderMemoryCacheKey(placeholderMemoryCacheUrl)
                }
                .build()
        }
    }
    var loading by remember(url) { mutableStateOf(true) }
    // onSuccess 在主线程，而取色要做整图位图拷贝
    var sampledImage by remember(url) { mutableStateOf<CoilImage?>(null) }
    val currentOnDominantColor by rememberUpdatedState(onDominantColor)
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    LaunchedEffect(sampledImage) {
        val source = sampledImage ?: return@LaunchedEffect
        val color = withContext(Dispatchers.Default) { dominantImageColor(source) }
        color?.let { currentOnDominantColor?.invoke(it) }
    }
    val image = @Composable { imageModifier: Modifier ->
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = imageModifier,
            contentScale = contentScale,
            alignment = alignment,
            colorFilter = colorFilter,
            onSuccess = { state ->
                if (onDominantColor != null) sampledImage = state.result.image
                currentOnLoaded?.invoke(state.result.image)
                loading = false
            },
            onError = { loading = false },
        )
    }
    if (showLoadingIndicator) {
        Box(modifier = modifier, contentAlignment = loadingIndicatorAlignment) {
            image(Modifier.matchParentSize())
            if (loading) InfiniteProgressIndicator(color = loadingIndicatorColor)
        }
    } else {
        image(modifier)
    }
}

@Composable
fun AppIcon(
    url: String,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    AppAsyncImage(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .squircleBorder(0.2.dp, Color.Gray, size * 0.25f)
            .squircleClip(size * 0.25f),
    )
}

@Composable
fun RelatedAppsList(
    apps: List<MarketAppInfo>,
    onOpenApp: (MarketAppInfo) -> Unit,
    title: (MarketAppInfo) -> String = { app -> app.displayName },
    summary: (MarketAppInfo) -> String = { app ->
        app.publisherName.ifBlank { app.packageName }
    },
    action: @Composable (MarketAppInfo) -> Unit,
) {
    RelatedAppsCard(
        apps = apps,
        onOpenApp = onOpenApp,
        title = title,
        summary = summary,
        action = action,
    )
}

@Composable
fun RelatedAppsCard(
    apps: List<MarketAppInfo>,
    onOpenApp: (MarketAppInfo) -> Unit,
    title: (MarketAppInfo) -> String = { app -> app.displayName },
    summary: (MarketAppInfo) -> String,
    colors: CardColors = CardColors(
        MiuixTheme.colorScheme.surface,
        MiuixTheme.colorScheme.onSurface
    ),
    action: @Composable (MarketAppInfo) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = colors,
        insideMargin = PaddingValues(0.dp),
    ) {
        Column {
            apps.forEach { app ->
                RelatedAppRow(
                    app = app,
                    title = title(app),
                    summary = summary(app),
                    onOpenApp = { onOpenApp(app) },
                    action = { action(app) },
                )
            }
        }
    }
}

@Composable
fun RelatedAppRow(
    app: MarketAppInfo,
    title: String = app.displayName,
    summary: String,
    onOpenApp: () -> Unit,
    action: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenApp)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            url = app.icon,
            contentDescription = appDisplayName(app.displayName, app.source),
            size = 48.dp,
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .widthIn(max = 195.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = appDisplayName(title, app.source),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    // Default fills width only (wrap height); pass Modifier.fillParentMaxSize() from a LazyColumn
    // item to truly center the spinner in the viewport instead of pinning it to the top.
    Box(modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        InfiniteProgressIndicator()
    }
}

private const val CollapsedChangeLogMaxLines = 1

@Composable
fun AppRow(
    app: MarketAppInfo,
    modifier: Modifier = Modifier,
    actionText: String,
    actionKind: AppActionKind,
    downloadState: DownloadState?,
    onOpenDetail: () -> Unit,
    onAction: () -> Unit,
    onResumeDownload: () -> Unit = onAction,
    onInstallDownloaded: (String) -> Unit,
    onCancel: (String) -> Unit,
    onIgnoreOnce: (() -> Unit)? = null,
    onIgnorePermanent: (() -> Unit)? = null,
    showUpdateLog: Boolean = false,
    showSearchTags: Boolean = false,
    /** 单源没有歧义，只在多源时标来源。 */
    showSourceTag: Boolean = false,
    initialChangeLogExpanded: Boolean = false,
    grouped: Boolean = false,
) {
    val hasMenu = onIgnoreOnce != null && onIgnorePermanent != null
    val showMenu = remember { mutableStateOf(false) }
    var menuMounted by remember { mutableStateOf(false) }
    val onRowLongPress = if (hasMenu) {
        {
            menuMounted = true
            showMenu.value = true
        }
    } else null
    var changeLogExpanded by rememberSaveable(app.packageName, app.versionCode) {
        mutableStateOf(
            initialChangeLogExpanded
        )
    }
    val noChangeLogText = stringResource(Res.string.no_change_log)
    val versionSizeText = remember(app) { appVersionSizeText(app) }
    val spSize = with(LocalDensity.current) { 1.sp.toDp() }
    val content: @Composable () -> Unit = {
        Box(
            modifier = if (showUpdateLog && !changeLogExpanded) {
                Modifier.height(58 * spSize)
            } else {
                Modifier
            },
        ) {
            if (hasMenu && menuMounted) {
                IgnoreUpdateMenu(
                    show = showMenu.value,
                    onDismiss = { showMenu.value = false },
                    onIgnoreOnce = {
                        showMenu.value = false
                        onIgnoreOnce.invoke()
                    },
                    onIgnorePermanent = {
                        showMenu.value = false
                        onIgnorePermanent.invoke()
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (grouped) 8.dp else 16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AppIcon(
                    url = app.icon,
                    contentDescription = appDisplayName(app.displayName, app.source),
                    size = if (grouped) 48.dp else 52 * spSize,
                    modifier = Modifier
                        .padding(
                            start = if (grouped) 0.dp else 3 * spSize,
                            top = if (grouped) 0.dp else 3 * spSize
                        ),
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            appDisplayName(app.displayName, app.source),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MiuixTheme.textStyles.headline1,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (showSourceTag) {
                            SearchTag(
                                text = appSourceLabel(app.source),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        if (showSearchTags && app.isAd) {
                            SearchTag(
                                text = stringResource(Res.string.search_ad_tag),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        if (showSearchTags && app.type.equals("quickGame", ignoreCase = true)) {
                            SearchTag(
                                text = stringResource(Res.string.search_quickapp_tag),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    if (showUpdateLog) {
                        Text(
                            versionSizeText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        UpdateChangeLogText(
                            text = app.changeLog.ifBlank { noChangeLogText },
                            expanded = changeLogExpanded,
                            onExpandedChange = { changeLogExpanded = it },
                            onOpenDetail = onOpenDetail,
                            onLongPress = onRowLongPress,
                        )
                    } else {
                        Text(
                            app.publisherName.ifBlank { app.packageName },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            versionSizeText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                AppActionButton(
                    packageName = app.packageName,
                    actionText = actionText,
                    actionKind = actionKind,
                    downloadState = downloadState,
                    enabled = actionKind == AppActionKind.OPEN || !app.isDownloadBlocked(),
                    onAction = onAction,
                    onResumeDownload = onResumeDownload,
                    onInstallDownloaded = onInstallDownloaded,
                    onCancel = onCancel,
                    minHeight = 34.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.padding(top = (29 * spSize - 17.dp)),
                )
            }
        }
    }
    if (grouped) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpenDetail,
                    onLongClick = onRowLongPress,
                )
                .padding(13.dp),
        ) {
            content()
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            onClick = onOpenDetail,
            onLongPress = onRowLongPress,
            showIndication = true,
            holdDownState = showMenu.value,
            insideMargin = PaddingValues(all = 13.dp),
        ) {
            content()
        }
    }
}

/** Small muted tag shown after the app name for search-result markers (promoted / quick app). */
@Composable
private fun SearchTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote2,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .squircleSurface(
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f),
                cornerRadius = 5.dp,
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun UpdateChangeLogText(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val textMeasurer = rememberTextMeasurer()
    val style = MiuixTheme.textStyles.body2
    val bodyColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val actionColor = MiuixTheme.colorScheme.primary
    val expandText = stringResource(Res.string.expand_change_log)
    val collapseText = stringResource(Res.string.collapse_change_log)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val hasMultiLinesOfText = remember(
            text,
            widthPx,
            style,
            bodyColor,
            textMeasurer,
        ) {
            widthPx > 0 && textMeasurer.measure(
                AnnotatedString(text, spanStyle = SpanStyle(color = bodyColor)),
                style = style,
                maxLines = CollapsedChangeLogMaxLines,
                constraints = Constraints(maxWidth = widthPx),
            ).hasVisualOverflow
        }

        when {
            !hasMultiLinesOfText -> {
                ChangeLogBodyText(
                    text = text,
                    style = style,
                    color = bodyColor,
                    maxLines = Int.MAX_VALUE,
                    onOpenDetail = onOpenDetail,
                    onLongPress = onLongPress,
                )
            }

            expanded -> {
                Column(Modifier.fillMaxWidth()) {
                    ChangeLogBodyText(
                        text = text,
                        style = style,
                        color = bodyColor,
                        maxLines = Int.MAX_VALUE,
                        onOpenDetail = onOpenDetail,
                        onLongPress = onLongPress,
                    )
                    ChangeLogActionText(
                        text = collapseText,
                        style = style,
                        color = actionColor,
                        onClick = { onExpandedChange(false) },
                        onLongPress = onLongPress,
                        modifier = Modifier.align(Alignment.Start),
                    )
                }
            }

            else -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChangeLogBodyText(
                        text = text,
                        style = style,
                        color = bodyColor,
                        maxLines = CollapsedChangeLogMaxLines,
                        onOpenDetail = onOpenDetail,
                        onLongPress = onLongPress,
                        modifier = Modifier.weight(1f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(24.dp).height(22.dp).background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        MiuixTheme.colorScheme.surfaceVariant
                                    ),
                                ),
                            ),
                        )
                        ChangeLogActionText(
                            text = expandText,
                            style = style,
                            color = actionColor,
                            onClick = { onExpandedChange(true) },
                            onLongPress = onLongPress,
                            modifier = Modifier.background(MiuixTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeLogBodyText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    onOpenDetail: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = if (maxLines == CollapsedChangeLogMaxLines) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = modifier.pointerInput(text, onOpenDetail, onLongPress) {
            detectTapGestures(
                onLongPress = { onLongPress?.invoke() },
                onTap = { onOpenDetail() },
            )
        },
    )
}

@Composable
private fun ChangeLogActionText(
    text: String,
    style: TextStyle,
    color: Color,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        style = style.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.pointerInput(text, onClick, onLongPress) {
            detectTapGestures(
                onLongPress = { onLongPress?.invoke() },
                onTap = { onClick() },
            )
        },
    )
}

private fun appVersionSizeText(app: MarketAppInfo) = buildAnnotatedString {
    val fullSize = formatSize(app.apkSize)
    val deltaSize = formatSize(app.deltaSize)
    if (fullSize.isBlank()) return@buildAnnotatedString
    if (app.versionName.isNotBlank()) {
        append(app.versionName)
        append("  ")
    }
    if (deltaSize.isNotBlank() && app.deltaSize > 0L && app.deltaSize < app.apkSize) {
        append(deltaSize)
        append("  ")
        val start = length
        append(fullSize)
        addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
    } else {
        append(fullSize)
    }
}

@Composable
internal fun IgnoreUpdateMenu(
    show: Boolean,
    onDismiss: () -> Unit,
    onIgnoreOnce: () -> Unit,
    onIgnorePermanent: () -> Unit,
) {
    val onceText = stringResource(Res.string.ignore_once)
    val permanentText = stringResource(Res.string.ignore_permanent)
    OverlayListPopup(
        show = show,
        alignment = PopupPositionProvider.Align.End,
        onDismissRequest = onDismiss,
    ) {
        ListPopupColumn {
            DropdownImpl(
                text = onceText,
                optionSize = 2,
                isSelected = false,
                index = 0,
                onSelectedIndexChange = { onIgnoreOnce() },
            )
            DropdownImpl(
                text = permanentText,
                optionSize = 2,
                isSelected = false,
                index = 1,
                onSelectedIndexChange = { onIgnorePermanent() },
            )
        }
    }
}
