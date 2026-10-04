package top.app.market.ui.screen

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.resources.Res
import top.app.market.resources.manual_update
import top.app.market.resources.manual_update_available
import top.app.market.resources.manual_update_check
import top.app.market.resources.manual_update_current
import top.app.market.resources.manual_update_no_apk
import top.app.market.resources.manual_update_package
import top.app.market.resources.manual_update_recognized_no_update
import top.app.market.resources.manual_update_version_code
import top.app.market.resources.update
import top.app.market.ui.component.AppActionButton
import top.app.market.ui.component.AppButton
import top.app.market.ui.component.AppButtonText
import top.app.market.ui.component.AppIcon
import top.app.market.ui.component.MarketScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.model.AppActionKind
import top.app.market.ui.util.appDisplayName
import top.app.market.ui.util.formatSize
import top.app.market.viewmodel.ManualUpdateViewModel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ManualUpdateScreen(
    viewModel: ManualUpdateViewModel,
    onOpenDetail: (MarketAppInfo) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadStates = viewModel.downloadStates.collectAsStateWithLifecycle()
    val updateText = stringResource(Res.string.update)

    MarketScaffold(
        title = stringResource(Res.string.manual_update),
        onBack = onBack
    ) { innerPadding, backdropModifier, scrollBehavior ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropModifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                top = innerPadding.calculateTopPadding() + PageVerticalPadding,
                bottom = innerPadding.calculateBottomPadding() + PageVerticalPadding,
            ),
        ) {
            item(key = "package") {
                TextField(
                    value = state.packageName,
                    onValueChange = viewModel::setPackageName,
                    label = stringResource(Res.string.manual_update_package),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "version") {
                TextField(
                    value = state.versionCode,
                    onValueChange = viewModel::setVersionCode,
                    label = stringResource(Res.string.manual_update_version_code),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "check") {
                AppButton(
                    onClick = viewModel::check,
                    enabled = !state.loading,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.loading) {
                        InfiniteProgressIndicator(color = MiuixTheme.colorScheme.onPrimary, size = 18.dp)
                    } else {
                        AppButtonText(stringResource(Res.string.manual_update_check))
                    }
                }
            }

            if (state.errorMessage.isNotBlank()) {
                item(key = "error") {
                    Text(
                        text = state.errorMessage,
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.main,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (state.noApk) {
                item(key = "no_apk") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.manual_update_no_apk),
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.main,
                        )
                        if (state.recognizedNoUpdate) {
                            Text(
                                text = stringResource(Res.string.manual_update_recognized_no_update),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.body2,
                            )
                        }
                    }
                }
            }

            state.result?.let { app ->
                item(key = "result") {
                    val packageName = app.packageName
                    val downloadState by remember(packageName) {
                        derivedStateOf { downloadStates.value[packageName] }
                    }
                    ManualUpdateResultCard(
                        app = app,
                        currentVersionCode = state.versionCode,
                        actionText = updateText,
                        downloadState = downloadState,
                        onOpenDetail = { onOpenDetail(app) },
                        onDownload = { viewModel.download(app) },
                        onInstallDownloaded = viewModel::installDownloaded,
                        onCancel = viewModel::cancelDownload,
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualUpdateResultCard(
    app: MarketAppInfo,
    currentVersionCode: String,
    actionText: String,
    downloadState: DownloadState?,
    onOpenDetail: () -> Unit,
    onDownload: () -> Unit,
    onInstallDownloaded: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenDetail,
        showIndication = true,
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppIcon(
                url = app.icon,
                contentDescription = appDisplayName(app.displayName, app.source),
                size = 58.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = appDisplayName(app.displayName, app.source),
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Text(
                    stringResource(
                        Res.string.manual_update_current,
                        "-",
                        currentVersionCode,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    stringResource(Res.string.manual_update_available, app.versionName, app.versionCode.toString()),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = formatSize(app.apkSize),
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            AppActionButton(
                packageName = app.packageName,
                actionText = actionText,
                actionKind = AppActionKind.UPDATE,
                downloadState = downloadState,
                onAction = onDownload,
                onResumeDownload = onDownload,
                onInstallDownloaded = onInstallDownloaded,
                onCancel = onCancel,
                modifier = Modifier.width(70.dp),
            )
        }
    }
}
