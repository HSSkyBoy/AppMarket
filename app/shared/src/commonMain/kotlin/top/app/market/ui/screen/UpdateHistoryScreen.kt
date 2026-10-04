package top.app.market.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.update.UpdateHistoryEntry
import top.app.market.resources.Res
import top.app.market.resources.cancel
import top.app.market.resources.collapse_change_log
import top.app.market.resources.confirm
import top.app.market.resources.date_full
import top.app.market.resources.date_today
import top.app.market.resources.date_yesterday
import top.app.market.resources.expand_change_log
import top.app.market.resources.no_update_history
import top.app.market.resources.update_history
import top.app.market.resources.update_history_clear
import top.app.market.resources.update_history_clear_confirm
import top.app.market.resources.update_history_first_install
import top.app.market.ui.component.AppIcon
import top.app.market.ui.component.AppTextButton
import top.app.market.ui.component.CardSegmentContainer
import top.app.market.ui.component.MarketScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.component.SectionTitle
import top.app.market.ui.util.appDisplayName
import top.app.market.ui.util.currentLocalDate
import top.app.market.ui.util.formatTimeHm
import top.app.market.ui.util.localDateOf
import top.app.market.viewmodel.UpdateHistoryViewModel
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/** 同一天的历史记录分组（[entries] 保持新→旧）。 */
private data class HistoryDayGroup(val date: LocalDate, val entries: List<UpdateHistoryEntry>)

@Composable
fun UpdateHistoryScreen(
    viewModel: UpdateHistoryViewModel,
    onOpenDetail: (UpdateHistoryEntry) -> Unit,
    onBack: () -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    MarketScaffold(
        title = stringResource(Res.string.update_history),
        onBack = onBack,
        actions = {
            if (entries.isNotEmpty()) {
                IconButton(
                    onClick = { showClearDialog = true },
                    holdDownState = showClearDialog,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = stringResource(Res.string.update_history_clear),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    ) { innerPadding, backdropModifier, scrollBehavior ->
        val groups = remember(entries) {
            entries.groupBy { localDateOf(it.installedAt) }
                .map { (date, items) -> HistoryDayGroup(date, items) }
        }
        val layoutDirection = LocalLayoutDirection.current
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
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(Res.string.no_update_history),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.main,
                        )
                    }
                }
            }
            groups.forEachIndexed { groupIndex, group ->
                item(key = "day-${group.date}") {
                    SectionTitle(
                        text = dayLabel(group.date),
                        topPadding = if (groupIndex == 0) 0.dp else 20.dp,
                    )
                }
                itemsIndexed(
                    group.entries,
                    key = { _, e -> "${e.packageName}:${e.installedAt}" },
                ) { index, entry ->
                    CardSegmentContainer(
                        isFirst = index == 0,
                        isLast = index == group.entries.lastIndex,
                        modifier = Modifier.animateItem(placementSpec = null),
                    ) {
                        HistoryRow(entry = entry, onOpenDetail = onOpenDetail)
                    }
                }
            }
        }
    }

    WindowDialog(
        show = showClearDialog,
        title = stringResource(Res.string.update_history_clear),
        summary = stringResource(Res.string.update_history_clear_confirm),
        onDismissRequest = { showClearDialog = false },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            AppTextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(Res.string.cancel),
                onClick = { showClearDialog = false },
            )
            Spacer(Modifier.width(16.dp))
            AppTextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(Res.string.confirm),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    viewModel.clear()
                    showClearDialog = false
                },
            )
        }
    }
}

@Composable
private fun dayLabel(date: LocalDate): String {
    val today = currentLocalDate()
    return when (date) {
        today -> stringResource(Res.string.date_today)
        today.minus(1, DateTimeUnit.DAY) -> stringResource(Res.string.date_yesterday)
        else -> stringResource(Res.string.date_full, date.year, date.month.number, date.day)
    }
}

@Composable
private fun HistoryRow(
    entry: UpdateHistoryEntry,
    onOpenDetail: (UpdateHistoryEntry) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail(entry) }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                url = entry.icon,
                contentDescription = appDisplayName(entry.displayName, entry.source),
                size = 48.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = appDisplayName(entry.displayName, entry.source),
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (entry.previousVersionName.isEmpty()) {
                        stringResource(Res.string.update_history_first_install, entry.versionName)
                    } else {
                        "${entry.previousVersionName} → ${entry.versionName}"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                formatTimeHm(entry.installedAt),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (entry.changeLog.isNotBlank()) {
            var expanded by rememberSaveable(entry.packageName, entry.installedAt) { mutableStateOf(false) }
            // 三态：null 表示尚未完成首次文本测量，避免「展开」在测量前闪现
            var hasVisualOverflow by remember(entry.packageName, entry.installedAt) {
                mutableStateOf<Boolean?>(null)
            }
            val collapsedChangeLog = remember(entry.changeLog) {
                entry.changeLog.replace(WhitespaceRegex, " ").trim()
            }
            val changeLogStyle = MiuixTheme.textStyles.body2
            Box(Modifier.fillMaxWidth()) {
                Text(
                    text = if (expanded) entry.changeLog else collapsedChangeLog,
                    style = changeLogStyle,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Clip,
                    onTextLayout = { result ->
                        if (!expanded) hasVisualOverflow = result.hasVisualOverflow
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!expanded && hasVisualOverflow == true) {
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
                Text(
                    text = stringResource(Res.string.collapse_change_log),
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable(interactionSource = null, indication = null) {
                            expanded = false
                        },
                    color = MiuixTheme.colorScheme.primary,
                    style = changeLogStyle,
                )
            }
        }
    }
}
