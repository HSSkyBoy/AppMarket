package top.app.market.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.AppSource.OPPO
import top.app.market.domain.model.market.AppSource.SAMSUNG
import top.app.market.domain.model.profile.MarketProfile
import top.app.market.domain.model.profile.ProfileSource
import top.app.market.domain.model.profile.ProfileTemplate
import top.app.market.platform.UiPlatform
import top.app.market.resources.Res
import top.app.market.resources.cancel
import top.app.market.resources.delete
import top.app.market.resources.device_profile
import top.app.market.resources.profile_delete_template_confirm
import top.app.market.resources.profile_save_template
import top.app.market.resources.profile_source
import top.app.market.resources.profile_source_current_device
import top.app.market.resources.profile_source_custom
import top.app.market.resources.profile_source_device
import top.app.market.resources.profile_source_preset
import top.app.market.resources.profile_template_name
import top.app.market.resources.save
import top.app.market.resources.saved
import top.app.market.ui.component.AppButton
import top.app.market.ui.component.AppTextButton
import top.app.market.ui.component.LoadingBox
import top.app.market.ui.component.MarketScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.component.deferredTopPadding
import top.app.market.ui.util.appSourceLabel
import top.app.market.viewmodel.DeviceProfileViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

private val TabRowBottomPadding = 6.dp

@Composable
fun DeviceProfileScreen(
    viewModel: DeviceProfileViewModel,
    onBack: () -> Unit,
) {
    val uiPlatform = koinInject<UiPlatform>()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val templateNames by viewModel.templateNames.collectAsStateWithLifecycle()
    val overriddenFields by viewModel.overriddenFields.collectAsStateWithLifecycle()
    val canUseDevice by viewModel.canUseDevice.collectAsStateWithLifecycle()
    val oppoRequestContext by viewModel.oppoRequestContext.collectAsStateWithLifecycle()
    val samsungRequestContext by viewModel.samsungRequestContext.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val showSaveTemplateDialog by viewModel.showSaveTemplateDialog.collectAsStateWithLifecycle()
    var deleteTemplateSource by remember { mutableStateOf<AppSource?>(null) }
    val savedMsg = stringResource(Res.string.saved)
    val customLabel = stringResource(Res.string.profile_source_custom)
    val presetLabel = stringResource(Res.string.profile_source_preset)
    val oppoRequestValues = listOf(
        "user-region" to oppoRequestContext.userRegion,
        "system-locale" to oppoRequestContext.systemLocale,
        "supported-locales" to oppoRequestContext.supportedLocales,
        "locale" to oppoRequestContext.locale,
    )
    val samsungRequestValues = listOf(
        "countryCode" to samsungRequestContext.countryCode,
        "lang" to samsungRequestContext.language,
        "mcc" to samsungRequestContext.mcc,
        "mnc" to samsungRequestContext.mnc,
        "csc" to samsungRequestContext.csc,
    )
    val editableSources = DeviceProfileViewModel.EDITABLE_SOURCES
    val tabs = editableSources.map { appSourceLabel(it) }
    val pagerState = rememberPagerState(initialPage = 0) { editableSources.size }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    if (editableSources.any { profiles[it] == null }) {
        MarketScaffold(title = stringResource(Res.string.device_profile), onBack = onBack) { innerPadding, _, _ ->
            LoadingBox(Modifier.fillMaxSize().padding(innerPadding))
        }
        return
    }

    // 页面切换后旧页仍组合在 pager 里，焦点会留在它的输入框上；切页时统一清掉。
    LaunchedEffect(pagerState.currentPage) {
        focusManager.clearFocus()
    }

    MarketScaffold(
        title = stringResource(Res.string.device_profile),
        onBack = onBack,
        bottomContent = { scrollBehavior ->
            // Top bar expanded: extra breathing room above the tabs; collapsed: flush with the bar.
            val dynamicTopPadding = remember(scrollBehavior) {
                { PageVerticalPadding * (1f - scrollBehavior.state.collapsedFraction) }
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = TabRowBottomPadding)
                    .deferredTopPadding(dynamicTopPadding),
            ) {
                TabRow(
                    tabs = tabs,
                    selectedTabIndex = pagerState.currentPage,
                    onTabSelected = { index ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(
                                page = index,
                                animationSpec = top.app.market.ui.navigation.PagerNavigationSpringSpec,
                            )
                        }
                    },
                    colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent),
                    minWidth = 92.dp,
                    maxWidth = 116.dp,
                    height = 40.dp,
                )
            }
        },
    ) { innerPadding, backdropModifier, scrollBehavior ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().then(backdropModifier),
            overscrollEffect = null,
            userScrollEnabled = false,
        ) { page ->
            val appSource = editableSources[page]
            DeviceProfileSourcePage(
                viewModel = viewModel,
                appSource = appSource,
                profile = profiles[appSource] ?: return@HorizontalPager,
                source = sources[appSource] ?: ProfileSource.PRESET,
                overridden = overriddenFields[appSource].orEmpty(),
                selectedTemplate = templateNames[appSource],
                templates = templates,
                canUseDevice = canUseDevice[appSource] == true,
                customLabel = customLabel,
                presetLabel = presetLabel,
                deviceLabel = deviceLabel(appSource),
                oppoRequestValues = oppoRequestValues,
                samsungRequestValues = samsungRequestValues,
                hasCustomRequestContext = appSource == SAMSUNG &&
                        DeviceProfileViewModel.hasCustomSamsungRequestContext(
                            samsungRequestContext,
                        ),
                modifier = backdropModifier,
                innerPadding = innerPadding,
                scrollBehavior = scrollBehavior,
                onSave = {
                    viewModel.save(appSource)
                    uiPlatform.showToast(savedMsg)
                },
                onSaveTemplate = { viewModel.setShowSaveTemplateDialog(appSource) },
                onRequestDeleteTemplate = { deleteTemplateSource = appSource },
            )
        }
    }

    SaveTemplateDialog(
        show = showSaveTemplateDialog != null,
        defaultName = viewModel.defaultTemplateName(),
        onDismiss = { viewModel.setShowSaveTemplateDialog(null) },
        onSave = { name ->
            showSaveTemplateDialog?.let { viewModel.saveTemplate(name, it) }
            uiPlatform.showToast(savedMsg)
        },
    )
    val deleteTemplateName = deleteTemplateSource?.let { templateNames[it] }
    DeleteTemplateDialog(
        show = deleteTemplateSource != null && deleteTemplateName != null,
        templateName = deleteTemplateName.orEmpty(),
        onDismiss = { deleteTemplateSource = null },
        onDelete = { name ->
            viewModel.deleteTemplate(name)
            deleteTemplateSource = null
        },
    )
}

@Composable
private fun DeviceProfileSourcePage(
    viewModel: DeviceProfileViewModel,
    appSource: AppSource,
    profile: MarketProfile,
    source: ProfileSource,
    overridden: Set<String>,
    selectedTemplate: String?,
    templates: List<ProfileTemplate>,
    canUseDevice: Boolean,
    customLabel: String,
    presetLabel: String,
    deviceLabel: String,
    oppoRequestValues: List<Pair<String, String>>,
    samsungRequestValues: List<Pair<String, String>>,
    hasCustomRequestContext: Boolean,
    modifier: Modifier,
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onSave: () -> Unit,
    onSaveTemplate: () -> Unit,
    onRequestDeleteTemplate: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
            end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
            top = innerPadding.calculateTopPadding() + PageVerticalPadding - TabRowBottomPadding,
            bottom = innerPadding.calculateBottomPadding() + PageVerticalPadding,
        ),
    ) {
        item(key = "source") {
            Card(modifier = Modifier.fillMaxWidth()) {
                val templateNamesForSource = templates.map { it.name }
                val sourceItems = buildList {
                    add(customLabel)
                    add(presetLabel)
                    addAll(templateNamesForSource)
                    if (canUseDevice) add(deviceLabel)
                }
                val selectedTemplateIndex = selectedTemplate?.let { name ->
                    templateNamesForSource.indexOf(name).takeIf { it >= 0 }?.plus(2)
                }
                val selectedIndex = when {
                    hasCustomRequestContext || overridden.isNotEmpty() -> 0
                    selectedTemplateIndex != null -> selectedTemplateIndex
                    source == ProfileSource.DEVICE && canUseDevice -> sourceItems.lastIndex
                    else -> 1
                }
                WindowDropdownPreference(
                    title = stringResource(Res.string.profile_source),
                    items = sourceItems,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { itemIndex ->
                        when {
                            itemIndex == 0 -> viewModel.useCustom(appSource)
                            itemIndex == 1 -> viewModel.setSource(ProfileSource.PRESET, appSource)
                            itemIndex in 2 until 2 + templateNamesForSource.size ->
                                viewModel.applyTemplate(templateNamesForSource[itemIndex - 2], appSource)

                            canUseDevice && itemIndex == sourceItems.lastIndex ->
                                viewModel.setSource(ProfileSource.DEVICE, appSource)
                        }
                    },
                )
            }
        }
        if (appSource == OPPO) {
            items(oppoRequestValues, key = { "oppo-header-${it.first}" }) { (name, value) ->
                TextField(
                    value = value,
                    onValueChange = { viewModel.updateOppoRequestContext(name, it) },
                    label = name,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (appSource == SAMSUNG) {
            items(samsungRequestValues, key = { "samsung-header-${it.first}" }) { (name, value) ->
                TextField(
                    value = value,
                    onValueChange = { viewModel.updateSamsungRequestContext(name, it) },
                    label = name,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(DeviceProfileViewModel.fieldsFor(appSource), key = { it }) { name ->
            TextField(
                value = DeviceProfileViewModel.valueOf(profile, name),
                onValueChange = { viewModel.update(appSource, name, it) },
                label = name,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "save") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = stringResource(Res.string.save),
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
                AppTextButton(
                    text = stringResource(Res.string.profile_save_template),
                    onClick = onSaveTemplate,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (selectedTemplate != null) {
                    AppTextButton(
                        text = stringResource(Res.string.delete),
                        colors = ButtonDefaults.textButtonColors(
                            textColor = MiuixTheme.colorScheme.error,
                        ),
                        onClick = onRequestDeleteTemplate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun deviceLabel(appSource: AppSource): String = stringResource(
    if (appSource == OPPO) {
        Res.string.profile_source_current_device
    } else {
        Res.string.profile_source_device
    }
)

@Composable
private fun SaveTemplateDialog(
    show: Boolean,
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(show, defaultName) { mutableStateOf(defaultName) }
    WindowDialog(
        show = show,
        title = stringResource(Res.string.profile_save_template),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(Res.string.profile_template_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                AppTextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                AppTextButton(
                    text = stringResource(Res.string.save),
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { onSave(name) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DeleteTemplateDialog(
    show: Boolean,
    templateName: String,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(Res.string.delete),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(Res.string.profile_delete_template_confirm, templateName),
                color = MiuixTheme.colorScheme.onSurface,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                AppTextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                AppTextButton(
                    text = stringResource(Res.string.delete),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { onDelete(templateName) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
