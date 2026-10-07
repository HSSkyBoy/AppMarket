package top.app.market.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.installer.InstallerAttributionMode
import top.app.market.domain.model.installer.InstallerMode
import top.app.market.resources.Res
import top.app.market.resources.cancel
import top.app.market.resources.installer_attribution
import top.app.market.resources.installer_attribution_auto
import top.app.market.resources.installer_attribution_auto_summary
import top.app.market.resources.installer_attribution_custom
import top.app.market.resources.installer_attribution_custom_hint
import top.app.market.resources.installer_attribution_custom_summary
import top.app.market.resources.installer_attribution_custom_title
import top.app.market.resources.installer_attribution_google_play
import top.app.market.resources.installer_attribution_google_play_summary
import top.app.market.resources.installer_attribution_none
import top.app.market.resources.installer_attribution_none_summary
import top.app.market.resources.installer_delete_after_install
import top.app.market.resources.installer_delete_after_install_summary
import top.app.market.resources.installer_mode_default
import top.app.market.resources.installer_mode_default_summary
import top.app.market.resources.installer_mode_root
import top.app.market.resources.installer_mode_root_summary
import top.app.market.resources.installer_mode_shizuku
import top.app.market.resources.installer_mode_shizuku_summary
import top.app.market.resources.installer_mode_third_party
import top.app.market.resources.installer_mode_third_party_summary
import top.app.market.resources.installer_auto_launch_confirm
import top.app.market.resources.installer_auto_launch_confirm_summary
import top.app.market.resources.installer_no_user_action
import top.app.market.resources.installer_no_user_action_summary
import top.app.market.resources.installer_none
import top.app.market.resources.installer_pick
import top.app.market.resources.installer_save_to_downloads
import top.app.market.resources.installer_save_to_downloads_summary
import top.app.market.resources.installer_section
import top.app.market.resources.save
import top.app.market.ui.component.AppTextButton
import top.app.market.ui.component.MarketScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.viewmodel.InstallerSettingsViewModel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun InstallerSettingsScreen(
    viewModel: InstallerSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MarketScaffold(
        title = stringResource(Res.string.installer_section),
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
            item(key = "mode") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    InstallerModeRow(
                        label = stringResource(Res.string.installer_mode_default),
                        summary = stringResource(Res.string.installer_mode_default_summary),
                        selected = state.mode == InstallerMode.STANDARD,
                        onClick = { viewModel.setMode(InstallerMode.STANDARD) },
                    )
                    InstallerModeRow(
                        label = stringResource(Res.string.installer_mode_root),
                        summary = stringResource(Res.string.installer_mode_root_summary),
                        selected = state.mode == InstallerMode.ROOT,
                        onClick = { viewModel.setMode(InstallerMode.ROOT) },
                    )
                    InstallerModeRow(
                        label = stringResource(Res.string.installer_mode_shizuku),
                        summary = stringResource(Res.string.installer_mode_shizuku_summary),
                        selected = state.mode == InstallerMode.SHIZUKU,
                        onClick = { viewModel.setMode(InstallerMode.SHIZUKU) },
                    )
                    val selectedInstaller = state.installerCandidates.firstOrNull {
                        it.packageName == state.thirdPartyInstallerPackage
                    }
                    InstallerModeRow(
                        label = stringResource(Res.string.installer_mode_third_party),
                        summary = selectedInstaller?.label
                            ?: stringResource(Res.string.installer_mode_third_party_summary),
                        selected = state.mode == InstallerMode.THIRD_PARTY,
                        onClick = viewModel::showThirdPartyInstallerPicker,
                    )
                }
            }

            if (state.mode == InstallerMode.ROOT || state.mode == InstallerMode.SHIZUKU) {
                item(key = "attribution") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        val attributionModes = listOf(
                            InstallerAttributionMode.AUTO_BY_SOURCE,
                            InstallerAttributionMode.GOOGLE_PLAY,
                            InstallerAttributionMode.CUSTOM,
                            InstallerAttributionMode.NONE,
                        )
                        val attributionItems = listOf(
                            stringResource(Res.string.installer_attribution_auto),
                            stringResource(Res.string.installer_attribution_google_play),
                            stringResource(Res.string.installer_attribution_custom),
                            stringResource(Res.string.installer_attribution_none),
                        )
                        val attributionSummary = when (state.attributionMode) {
                            InstallerAttributionMode.AUTO_BY_SOURCE ->
                                stringResource(Res.string.installer_attribution_auto_summary)
                            InstallerAttributionMode.GOOGLE_PLAY ->
                                stringResource(Res.string.installer_attribution_google_play_summary)
                            InstallerAttributionMode.CUSTOM -> {
                                if (state.attributionCustomPackage.isNotBlank()) {
                                    stringResource(
                                        Res.string.installer_attribution_custom_summary,
                                        state.attributionCustomPackage,
                                    )
                                } else {
                                    stringResource(Res.string.installer_attribution_custom_hint)
                                }
                            }
                            InstallerAttributionMode.NONE ->
                                stringResource(Res.string.installer_attribution_none_summary)
                        }
                        OverlayDropdownPreference(
                            title = stringResource(Res.string.installer_attribution),
                            summary = attributionSummary,
                            items = attributionItems,
                            selectedIndex = attributionModes.indexOf(state.attributionMode).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                attributionModes.getOrNull(index)?.let(viewModel::setAttributionMode)
                            },
                        )
                        if (state.attributionMode == InstallerAttributionMode.CUSTOM) {
                            ArrowPreference(
                                title = stringResource(Res.string.installer_attribution_custom_title),
                                summary = state.attributionCustomPackage.ifBlank {
                                    stringResource(Res.string.installer_attribution_custom_hint)
                                },
                                onClick = viewModel::showCustomAttributionDialog,
                            )
                        }
                    }
                }
            }

            item(key = "save") {
                val deleteAfterInstall = state.mode == InstallerMode.THIRD_PARTY
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = stringResource(
                            if (deleteAfterInstall) {
                                Res.string.installer_delete_after_install
                            } else {
                                Res.string.installer_save_to_downloads
                            }
                        ),
                        summary = stringResource(
                            if (deleteAfterInstall) {
                                Res.string.installer_delete_after_install_summary
                            } else {
                                Res.string.installer_save_to_downloads_summary
                            }
                        ),
                        checked = if (deleteAfterInstall) !state.saveToDownloads else state.saveToDownloads,
                        onCheckedChange = { checked ->
                            viewModel.setSaveToDownloads(if (deleteAfterInstall) !checked else checked)
                        },
                    )
                    if (state.mode == InstallerMode.STANDARD) {
                        SwitchPreference(
                            title = stringResource(Res.string.installer_auto_launch_confirm),
                            summary = stringResource(Res.string.installer_auto_launch_confirm_summary),
                            checked = state.autoLaunchConfirmUi,
                            onCheckedChange = viewModel::setAutoLaunchConfirmUi,
                        )
                    }
                    if (
                        state.mode == InstallerMode.STANDARD &&
                        state.userActionNotRequiredConfigurable
                    ) {
                        SwitchPreference(
                            title = stringResource(Res.string.installer_no_user_action),
                            summary = stringResource(Res.string.installer_no_user_action_summary),
                            checked = state.userActionNotRequiredEnabled,
                            onCheckedChange = viewModel::setUserActionNotRequiredEnabled,
                        )
                    }
                }
            }

        }
    }

    WindowDialog(
        show = state.showInstallerPicker,
        title = stringResource(Res.string.installer_pick),
        onDismissRequest = viewModel::dismissThirdPartyInstallerPicker,
        insideMargin = DpSize(0.dp, 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.installerCandidates.isEmpty()) {
                Text(
                    text = stringResource(Res.string.installer_none),
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                ) {
                    items(
                        items = state.installerCandidates,
                        key = { it.packageName },
                    ) { candidate ->
                        RadioButtonPreference(
                            title = candidate.label,
                            summary = candidate.packageName,
                            selected = candidate.packageName == state.thirdPartyInstallerPackage,
                            radioButtonLocation = RadioButtonLocation.End,
                            insideMargin = PaddingValues(24.dp, 16.dp),
                            onClick = { viewModel.selectThirdPartyInstaller(candidate) },
                        )
                    }
                }
            }
            AppTextButton(
                text = stringResource(Res.string.cancel),
                onClick = viewModel::dismissThirdPartyInstallerPicker,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
    }

    if (state.showCustomAttributionDialog) {
        var customInput by remember(state.showCustomAttributionDialog, state.attributionCustomPackage) {
            mutableStateOf(state.attributionCustomPackage)
        }
        WindowDialog(
            show = state.showCustomAttributionDialog,
            title = stringResource(Res.string.installer_attribution_custom_title),
            onDismissRequest = viewModel::dismissCustomAttributionDialog,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = stringResource(Res.string.installer_attribution_custom_title),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    AppTextButton(
                        text = stringResource(Res.string.cancel),
                        onClick = viewModel::dismissCustomAttributionDialog,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    AppTextButton(
                        text = stringResource(Res.string.save),
                        enabled = customInput.isNotBlank(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { viewModel.setAttributionCustomPackage(customInput) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallerModeRow(
    label: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    RadioButtonPreference(
        title = label,
        summary = summary,
        selected = selected,
        onClick = onClick,
        radioButtonLocation = RadioButtonLocation.End,
    )
}
