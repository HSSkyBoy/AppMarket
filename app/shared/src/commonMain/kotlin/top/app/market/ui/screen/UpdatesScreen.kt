package top.app.market.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.platform.UiPlatform
import top.app.market.resources.Res
import top.app.market.resources.app_list_permission_refresh_hint
import top.app.market.resources.app_list_permission_required
import top.app.market.resources.back
import top.app.market.resources.collapse_change_log
import top.app.market.resources.expand_change_log
import top.app.market.resources.go_to_settings
import top.app.market.resources.ignore_once
import top.app.market.resources.ignore_permanent
import top.app.market.resources.nav_updates
import top.app.market.resources.no_change_log
import top.app.market.resources.no_updates
import top.app.market.resources.num_updates_pending
import top.app.market.resources.update
import top.app.market.resources.update_all
import top.app.market.resources.update_check_failed
import top.app.market.resources.updates_title
import top.app.market.resources.version_transition
import top.app.market.resources.version_transition_label
import top.app.market.ui.component.AppActionButton
import top.app.market.ui.component.AppButton
import top.app.market.ui.component.AppIcon
import top.app.market.ui.component.CardSegmentContainer
import top.app.market.ui.component.LoadingBox
import top.app.market.ui.component.MainTabScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.model.AppActionKind
import top.app.market.ui.util.appDisplayName
import top.app.market.ui.util.formatSize
import top.app.market.viewmodel.UpdatesViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun UpdatesTab(
    viewModel: UpdatesViewModel,
    bottomPadding: Dp,
    onOpenDetail: (MarketAppInfo) -> Unit,
) {
    val uiPlatform = koinInject<UiPlatform>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadStates = viewModel.downloadStates.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    MainTabScaffold(
        title = stringResource(Res.string.nav_updates)
    ) { topPadding, backdropModifier, scrollBehavior ->
        val listPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = topPadding + PageVerticalPadding,
            bottom = bottomPadding + PageVerticalPadding,
        )
        val content = when {
            state.updates.isNotEmpty() -> UpdatesContent.Cards
            state.loading -> UpdatesContent.Loading
            state.appListPermissionRequired -> UpdatesContent.PermissionRequired
            state.checkFailed -> UpdatesContent.Error
            else -> UpdatesContent.Empty
        }
        // Fade between the full-screen states and the card list; keyed on the state shape so a data
        // refresh doesn't re-fade the whole tree (cards animate individually via animateItem).
        Crossfade(
            targetState = content,
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .then(backdropModifier), label = "updates"
        ) { target ->
            when (target) {
                UpdatesContent.Loading -> LoadingBox(Modifier.fillMaxSize().padding(listPadding))
                UpdatesContent.PermissionRequired -> AppListPermissionRequiredState(
                    modifier = Modifier.fillMaxSize().padding(listPadding),
                    uiPlatform = uiPlatform,
                )

                UpdatesContent.Error -> UpdatesHint(
                    stringResource(Res.string.update_check_failed),
                    listPadding
                )

                UpdatesContent.Empty -> UpdatesHint(
                    stringResource(Res.string.no_updates),
                    listPadding
                )

                UpdatesContent.Cards -> UpdatesCardList(
                    updates = state.updates,
                    downloadStates = downloadStates,
                    listState = listState,
                    listPadding = listPadding,
                    viewModel = viewModel,
                    onOpenDetail = onOpenDetail,
                    modifier = Modifier
                        .fillMaxHeight()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                )
            }
        }
    }
}

private enum class UpdatesContent { Loading, PermissionRequired, Error, Empty, Cards }


@Composable
private fun UpdatesCardList(
    updates: List<MarketAppInfo>,
    downloadStates: State<Map<String, DownloadState>>,
    listState: LazyListState,
    listPadding: PaddingValues,
    viewModel: UpdatesViewModel,
    onOpenDetail: (MarketAppInfo) -> Unit,
    modifier: Modifier
) {
    val updateActionText = stringResource(Res.string.update)
    val totalUpdateSize = remember(updates) {
        updates.sumOf { app -> app.deltaSize.takeIf { it > 0 } ?: app.apkSize }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = listPadding,
    ) {
        item(key = "summary") {
            Card(
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .fillMaxWidth(),
                insideMargin = PaddingValues(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            stringResource(Res.string.num_updates_pending, updates.size),
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.headline1.copy(
                                fontWeight = FontWeight.Medium,
                                lineHeight = 22.sp,
                            ),
                        )
                        Text(
                            formatSize(totalUpdateSize),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                        )
                    }
                    AppButton(
                        text = stringResource(Res.string.update_all),
                        onClick = { viewModel.downloadAll(updates) },
                        cornerRadius = 100.dp,
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    )
                }
            }
        }
        itemsIndexed(updates, key = { _, app -> app.packageName }) { index, app ->
            val packageName = app.packageName
            val downloadState by remember(packageName) {
                derivedStateOf { downloadStates.value[packageName] }
            }
            CardSegmentContainer(
                isFirst = index == 0,
                isLast = index == updates.lastIndex,
                modifier = Modifier.animateItem(placementSpec = null),
                horizontalPadding = 0.dp,
            ) {
                UpdateItemRow(
                    app = app,
                    downloadState = downloadState,
                    onOpenDetail = { onOpenDetail(app) },
                    onAction = { viewModel.download(app) },
                    onInstallDownloaded = viewModel::installDownloaded,
                    onCancel = viewModel::cancelDownload,
                    onIgnoreOnce = { viewModel.ignoreOnce(app) },
                    onIgnorePermanent = { viewModel.ignorePermanently(app) },
                    actionText = updateActionText,
                )
            }
        }
    }
}

@Composable
private fun UpdatesHeader(onBack: () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Back,
                contentDescription = stringResource(Res.string.back),
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                    },
            )
        }
        Box(
            modifier = Modifier.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(Res.string.updates_title),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.title1.copy(lineHeight = 40.sp),
            )
        }
    }
}

// 也被更新历史页复用：折叠态把日志空白压平成单行
internal val WhitespaceRegex = Regex("\\s+")

@Composable
private fun UpdateItemRow(
    app: MarketAppInfo,
    downloadState: DownloadState?,
    actionText: String,
    onOpenDetail: () -> Unit,
    onAction: () -> Unit,
    onInstallDownloaded: (String) -> Unit,
    onCancel: (String) -> Unit,
    onIgnoreOnce: () -> Unit,
    onIgnorePermanent: () -> Unit,
) {
    var expanded by remember(app.packageName, app.versionCode) { mutableStateOf(false) }
    val changeLog = app.changeLog.ifBlank { stringResource(Res.string.no_change_log) }
    // 折叠态把换行等空白压平成单行连续文字，避免首行只是「新增」之类的小标题
    val collapsedChangeLog = remember(changeLog) {
        changeLog.replace(WhitespaceRegex, " ").trim()
    }
    // 「展开」除了展开日志还负责露出忽略操作，因此折叠态始终显示，不做溢出检测
    val showMore = !expanded
    val changeLogStyle = MiuixTheme.textStyles.body2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon(
                    url = app.icon,
                    contentDescription = appDisplayName(app.displayName, app.source),
                    size = 48.dp,
                )
                Column(Modifier.width(195.dp)) {
                    Text(
                        text = appDisplayName(app.displayName, app.source),
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MiuixTheme.textStyles.headline1.copy(
                            fontWeight = FontWeight.Medium,
                            lineHeight = 22.sp,
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.version_transition_label),
                            maxLines = 1,
                            style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(
                                Res.string.version_transition,
                                app.installedVersionName.ifBlank { "-" },
                                app.versionName,
                            ),
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .basicMarquee(iterations = Int.MAX_VALUE),
                            maxLines = 1,
                            style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        text = updateSizeText(app),
                        maxLines = 1,
                        style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            AppActionButton(
                packageName = app.packageName,
                actionText = actionText,
                actionKind = AppActionKind.UPDATE,
                downloadState = downloadState,
                onAction = onAction,
                onResumeDownload = onAction,
                onInstallDownloaded = onInstallDownloaded,
                onCancel = onCancel,
                minHeight = 34.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.fillMaxWidth()) {
            SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (expanded) changeLog else collapsedChangeLog,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Clip,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    style = changeLogStyle,
                )
            }
            if (showMore) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .height(IntrinsicSize.Min)
                        .clickable(interactionSource = null, indication = null) {
                            expanded = true
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(58.dp).fillMaxHeight().background(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                1f to MiuixTheme.colorScheme.surfaceContainer,
                            ),
                        ),
                    )
                    Text(
                        text = stringResource(Res.string.expand_change_log),
                        modifier = Modifier.background(MiuixTheme.colorScheme.surfaceContainer),
                        color = MiuixTheme.colorScheme.primary,
                        style = changeLogStyle,
                    )
                }
            }
        }
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(Res.string.ignore_once),
                        modifier = Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onIgnoreOnce,
                        ),
                        color = MiuixTheme.colorScheme.primary,
                        style = changeLogStyle,
                    )
                    Text(
                        text = stringResource(Res.string.ignore_permanent),
                        modifier = Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onIgnorePermanent,
                        ),
                        color = MiuixTheme.colorScheme.primary,
                        style = changeLogStyle,
                    )
                }
                Text(
                    text = stringResource(Res.string.collapse_change_log),
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        expanded = false
                    },
                    color = MiuixTheme.colorScheme.primary,
                    style = changeLogStyle,
                )
            }
        }
    }
}

internal fun updateSizeText(app: MarketAppInfo) = buildAnnotatedString {
    val fullSize = formatSize(app.apkSize)
    val hasDelta = app.deltaSize > 0L && app.deltaSize < app.apkSize
    if (!hasDelta) {
        append(fullSize)
        return@buildAnnotatedString
    }

    append(formatSize(app.deltaSize))
    append("  ")
    val fullSizeStart = length
    append(fullSize)
    addStyle(
        SpanStyle(textDecoration = TextDecoration.LineThrough),
        fullSizeStart,
        length,
    )
}

@Composable
private fun UpdatesHint(text: String, padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.main,
        )
    }
}

@Composable
private fun AppListPermissionRequiredState(
    modifier: Modifier = Modifier,
    uiPlatform: UiPlatform,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.app_list_permission_required),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            style = MiuixTheme.textStyles.main,
        )
        Text(
            stringResource(Res.string.app_list_permission_refresh_hint),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            style = MiuixTheme.textStyles.body2,
        )
        AppButton(
            text = stringResource(Res.string.go_to_settings),
            onClick = { uiPlatform.openAppSettings() },
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 100.dp,
            colors = ButtonDefaults.buttonColorsPrimary(),
        )
    }
}
