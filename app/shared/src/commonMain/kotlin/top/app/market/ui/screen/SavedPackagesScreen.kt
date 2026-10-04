package top.app.market.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.installer.SavedPackage
import top.app.market.resources.Res
import top.app.market.resources.cancel
import top.app.market.resources.confirm
import top.app.market.resources.delete
import top.app.market.resources.install
import top.app.market.resources.install_success_message
import top.app.market.resources.install_success_title
import top.app.market.resources.install_waiting_message
import top.app.market.resources.installing_short
import top.app.market.resources.no_saved_packages
import top.app.market.resources.saved_package_delete_confirm
import top.app.market.resources.saved_packages
import top.app.market.ui.component.AppCompactButton
import top.app.market.ui.component.AppIcon
import top.app.market.ui.component.AppTextButton
import top.app.market.ui.component.CardSegmentContainer
import top.app.market.ui.component.LoadingBox
import top.app.market.ui.component.MarketScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.util.formatSize
import top.app.market.ui.util.formatTimeHm
import top.app.market.viewmodel.SavedPackagesViewModel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun SavedPackagesScreen(
    viewModel: SavedPackagesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deletingPackage by remember { mutableStateOf<SavedPackage?>(null) }
    var retainedInstallingName by remember { mutableStateOf("") }
    var retainedInstalledName by remember { mutableStateOf("") }
    LaunchedEffect(state.installingPackage?.displayName) {
        state.installingPackage?.displayName?.let { retainedInstallingName = it }
    }
    LaunchedEffect(state.installedPackage?.displayName) {
        state.installedPackage?.displayName?.let { retainedInstalledName = it }
    }

    MarketScaffold(
        title = stringResource(Res.string.saved_packages),
        onBack = onBack,
    ) { innerPadding, backdropModifier, scrollBehavior ->
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
            when {
                state.loading -> item(key = "loading") { LoadingBox(Modifier.fillParentMaxSize()) }
                state.packages.isEmpty() -> item(key = "empty") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(Res.string.no_saved_packages),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            itemsIndexed(
                state.packages,
                key = { _, pkg -> pkg.id },
            ) { index, pkg ->
                CardSegmentContainer(
                    isFirst = index == 0,
                    isLast = index == state.packages.lastIndex,
                    modifier = Modifier.animateItem(placementSpec = null),
                ) {
                    SavedPackageRow(
                        pkg = pkg,
                        onInstall = { viewModel.install(pkg) },
                        onDelete = { deletingPackage = pkg },
                    )
                }
            }
        }
    }

    DeleteSavedPackageDialog(
        show = deletingPackage != null,
        packageName = deletingPackage?.fileName.orEmpty(),
        onDismiss = { deletingPackage = null },
        onDelete = {
            deletingPackage?.let(viewModel::delete)
            deletingPackage = null
        },
    )
    InstallWaitingDialog(
        show = state.installingPackage != null,
        packageName = state.installingPackage?.displayName ?: retainedInstallingName,
    )
    InstallSuccessDialog(
        show = state.installedPackage != null,
        packageName = state.installedPackage?.displayName ?: retainedInstalledName,
        onDismiss = viewModel::dismissInstallSuccess,
    )
}

@Composable
private fun InstallWaitingDialog(
    show: Boolean,
    packageName: String,
) {
    WindowDialog(
        show = show,
        title = stringResource(Res.string.installing_short),
        onDismissRequest = {},
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfiniteProgressIndicator(size = 24.dp)
            Text(
                text = stringResource(Res.string.install_waiting_message, packageName),
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun InstallSuccessDialog(
    show: Boolean,
    packageName: String,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(Res.string.install_success_title),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(Res.string.install_success_message, packageName),
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            AppTextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(Res.string.confirm),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun DeleteSavedPackageDialog(
    show: Boolean,
    packageName: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(Res.string.delete),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(Res.string.saved_package_delete_confirm, packageName),
                color = MiuixTheme.colorScheme.onSurface,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                AppTextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.cancel),
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(16.dp))
                AppTextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.confirm),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun SavedPackageRow(
    pkg: SavedPackage,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pkg.icon.isNotBlank()) {
                AppIcon(
                    url = pkg.icon,
                    contentDescription = pkg.displayName,
                    size = 48.dp,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    pkg.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    pkg.packageName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    packageSummary(pkg),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCompactButton(
                text = stringResource(Res.string.install),
                onClick = onInstall,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            AppCompactButton(
                text = stringResource(Res.string.delete),
                onClick = onDelete,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun packageSummary(pkg: SavedPackage): String {
    val parts = listOfNotNull(
        pkg.versionName.takeIf { it.isNotBlank() },
        formatSize(pkg.size).takeIf { it.isNotBlank() },
        formatTimeHm(pkg.modifiedAt),
    )
    return parts.joinToString("  ")
}
