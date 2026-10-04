package top.app.market.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.update.IgnoredUpdate
import top.app.market.resources.Res
import top.app.market.resources.ignored_apps
import top.app.market.resources.ignored_once
import top.app.market.resources.ignored_permanent
import top.app.market.resources.no_ignored
import top.app.market.resources.restore
import top.app.market.resources.system_app_tag
import top.app.market.ui.component.AppCompactButton
import top.app.market.ui.component.AppIcon
import top.app.market.ui.component.CardSegmentContainer
import top.app.market.ui.component.MarketScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.component.SectionTitle
import top.app.market.ui.util.appDisplayName
import top.app.market.viewmodel.IgnoredAppsViewModel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun IgnoredAppsScreen(
    viewModel: IgnoredAppsViewModel,
    onOpenDetail: (IgnoredUpdate) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permanentTitle = stringResource(Res.string.ignored_permanent)
    val onceTitle = stringResource(Res.string.ignored_once)
    val emptyHint = stringResource(Res.string.no_ignored)
    val systemTag = stringResource(Res.string.system_app_tag)

    MarketScaffold(title = stringResource(Res.string.ignored_apps), onBack = onBack) { innerPadding, backdropModifier, scrollBehavior ->
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
            ignoredSection(
                title = permanentTitle,
                entries = state.permanent,
                emptyHint = emptyHint,
                systemTag = systemTag,
                isFirst = true,
                onOpenDetail = onOpenDetail,
                onRestore = { viewModel.removePermanent(it.packageName) },
            )
            ignoredSection(
                title = onceTitle,
                entries = state.once,
                emptyHint = emptyHint,
                systemTag = systemTag,
                onOpenDetail = onOpenDetail,
                onRestore = { viewModel.removeOnce(it.packageName) },
            )
        }
    }
}

private fun LazyListScope.ignoredSection(
    title: String,
    entries: List<IgnoredUpdate>,
    emptyHint: String,
    systemTag: String,
    onOpenDetail: (IgnoredUpdate) -> Unit,
    onRestore: (IgnoredUpdate) -> Unit,
    isFirst: Boolean = false,
) {
    item(key = "title-$title") {
        SectionTitle(text = title, topPadding = if (isFirst) 0.dp else 20.dp)
    }
    if (entries.isEmpty()) {
        item(key = "card-$title") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    text = emptyHint,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.main,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    } else {
        itemsIndexed(entries, key = { _, entry -> "$title:${entry.packageName}" }) { index, entry ->
            CardSegmentContainer(
                isFirst = index == 0,
                isLast = index == entries.lastIndex,
                modifier = Modifier.animateItem(placementSpec = null),
            ) {
                IgnoredAppRow(
                    entry = entry,
                    systemTag = systemTag,
                    onOpenDetail = onOpenDetail,
                    onRestore = onRestore,
                )
            }
        }
    }
}

@Composable
private fun IgnoredAppRow(
    entry: IgnoredUpdate,
    systemTag: String,
    onOpenDetail: (IgnoredUpdate) -> Unit,
    onRestore: (IgnoredUpdate) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail(entry) }
            .padding(16.dp),
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
                if (entry.isSystemApp) systemTag else entry.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        AppCompactButton(
            text = stringResource(Res.string.restore),
            onClick = { onRestore(entry) },
            modifier = Modifier.width(70.dp),
        )
    }
}
