package top.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.exception.InstalledPackagesUnavailableException
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.preference.HomePage
import top.app.market.domain.model.installer.InstallerMode
import top.app.market.domain.model.update.IgnoredUpdate
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.InstallerPreferencesRepository
import top.app.market.domain.repository.MarketSourceRepository
import top.app.market.domain.repository.PackageRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import top.app.market.platform.UiPlatform
import top.app.market.resources.Res
import top.app.market.resources.update_system_app_standard_hint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

@Immutable
data class UpdatesUiState(
    val loading: Boolean = false,
    val checkFailed: Boolean = false,
    val appListPermissionRequired: Boolean = false,
    val showSystemUpdates: Boolean = true,
    val removeSearchAds: Boolean = false,
    val filterQuickGames: Boolean = false,
    val filterReservationApps: Boolean = false,
    val updates: List<MarketAppInfo> = emptyList(),
)

/** Raw (unfiltered) update-check result. The visible [UpdatesUiState] is derived from this + prefs. */
private data class RawUpdates(
    val loading: Boolean = false,
    val checkFailed: Boolean = false,
    val appListPermissionRequired: Boolean = false,
    val all: List<MarketAppInfo> = emptyList(),
)

class UpdatesViewModel(
    private val sources: MarketSourceRepository,
    private val prefs: UpdatePreferencesRepository,
    private val downloads: DownloadRepository,
    private val installerPrefs: InstallerPreferencesRepository,
    private val packages: PackageRepository,
    private val uiPlatform: UiPlatform,
) : ViewModel() {

    // Seeded loading=true so Compose can request notification permission before the first live check
    // without flashing "no updates".
    private val _raw = MutableStateFlow(RawUpdates(loading = true))

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    // Separate flow (not in [uiState]) so the pager can read the seeded value synchronously.
    val homePage: StateFlow<HomePage> = prefs.homePage
    val searchSources: StateFlow<Set<AppSource>> = prefs.searchSources
    val todaySource: StateFlow<AppSource> = prefs.todaySource
    val categorySource: StateFlow<AppSource> = prefs.categorySource
    val updateSource: StateFlow<AppSource> = prefs.updateSource

    // App-detail display toggles; consumed by the detail screen, surfaced here for the settings UI.
    val showAppComments: StateFlow<Boolean> = prefs.showAppComments
    val showSameDeveloper: StateFlow<Boolean> = prefs.showSameDeveloper
    val showPromotions: StateFlow<Boolean> = prefs.showPromotions

    // 全局应用名精简开关；App 根部经 CompositionLocal 下发，这里仅供设置页读写。
    val stripAppNameSubtitle: StateFlow<Boolean> = prefs.stripAppNameSubtitle

    // Visible list = pure function of the raw result + preference flows; filtered off-main via flowOn.
    val uiState: StateFlow<UpdatesUiState> =
        combine(
            _raw,
            prefs.showSystemUpdates,
            prefs.removeSearchAds,
            combine(prefs.filterQuickGames, prefs.filterReservationApps) { games, reservations -> games to reservations },
            combine(prefs.permanentIgnores, prefs.onceIgnores) { permanent, once -> permanent to once },
        ) { raw, showSystem, removeAds, filters, ignores ->
            derive(raw, showSystem, removeAds, filters.first, filters.second, ignores.first, ignores.second)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, UpdatesUiState(loading = true))

    private val pendingDownloads = mutableSetOf<String>()
    private var initialCheckStarted = false
    private var checkJob: kotlinx.coroutines.Job? = null

    init {
        // Seed the last result for an instant warm launch — reconciled against the installed set so
        // out-of-band updates don't flash a stale row. Only while the live check hasn't returned yet.
        viewModelScope.launch {
            val selectedSource = prefs.updateSource.value
            // The legacy cache is Xiaomi-owned. Never show it while a non-Xiaomi update source is selected.
            val cached = if (selectedSource == AppSource.XIAOMI) {
                sources.loadReconciledCachedUpdates()
            } else {
                emptyList()
            }
            if (cached.isNotEmpty()) {
                _raw.update { if (it.all.isEmpty()) it.copy(all = cached) else it }
            }
        }
        viewModelScope.launch {
            packages.changes.collect { change ->
                val installedVersion = change.installedVersionCode ?: return@collect
                _raw.update {
                    it.copy(
                        all = it.all.filterNot { app ->
                            app.packageName == change.packageName && installedVersion >= app.versionCode
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            var activeSource = prefs.updateSource.value
            prefs.updateSource.collect { nextSource ->
                if (nextSource == activeSource) return@collect
                activeSource = nextSource
                // Source-owned IDs, refs and delta metadata cannot survive a source switch.
                _raw.value = RawUpdates(loading = initialCheckStarted)
                if (initialCheckStarted) runCheck()
            }
        }
    }

    fun setShowSystemUpdates(value: Boolean) = persist { prefs.setShowSystemUpdates(value) }
    fun setRemoveSearchAds(value: Boolean) = persist { prefs.setRemoveSearchAds(value) }
    fun setFilterQuickGames(value: Boolean) = persist { prefs.setFilterQuickGames(value) }
    fun setFilterReservationApps(value: Boolean) = persist { prefs.setFilterReservationApps(value) }
    fun setShowAppComments(value: Boolean) = persist { prefs.setShowAppComments(value) }
    fun setShowSameDeveloper(value: Boolean) = persist { prefs.setShowSameDeveloper(value) }
    fun setShowPromotions(value: Boolean) = persist { prefs.setShowPromotions(value) }
    fun setStripAppNameSubtitle(value: Boolean) = persist { prefs.setStripAppNameSubtitle(value) }
    fun setHomePage(value: HomePage) = persist { prefs.setHomePage(value) }
    fun setSearchSource(value: AppSource) = persist { prefs.setSearchSources(setOf(value)) }
    fun setTodaySource(value: AppSource) = persist { prefs.setTodaySource(value) }
    fun setCategorySource(value: AppSource) = persist { prefs.setCategorySource(value) }
    fun setUpdateSource(value: AppSource) = persist { prefs.setUpdateSource(value) }

    fun startInitialCheck() {
        if (initialCheckStarted) return
        initialCheckStarted = true
        runCheck()
    }

    private fun runCheck() {
        checkJob?.cancel()
        _raw.update {
            it.copy(loading = true, checkFailed = false, appListPermissionRequired = false)
        }
        checkJob = viewModelScope.launch {
            try {
                val selectedSource = prefs.updateSource.value
                var latestLiveUpdates: List<MarketAppInfo>? = null
                sources.checkUpdatesFlow(selectedSource).collect { updates ->
                    latestLiveUpdates = updates
                    _raw.update { current ->
                        current.copy(
                            all = preserveKnownDeltaSizes(current.all, updates),
                            appListPermissionRequired = false,
                        )
                    }
                }
                val finalUpdates = latestLiveUpdates ?: _raw.value.all
                _raw.update {
                    it.copy(
                        loading = false,
                        checkFailed = false,
                        appListPermissionRequired = false,
                        all = finalUpdates,
                    )
                }
                // The persisted cache predates source-aware rows and is Xiaomi-only.
                if (selectedSource == AppSource.XIAOMI) prefs.saveCachedUpdates(finalUpdates)
            } catch (_: InstalledPackagesUnavailableException) {
                _raw.update { it.copy(loading = false, checkFailed = false, appListPermissionRequired = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _raw.update { it.copy(loading = false, checkFailed = true, appListPermissionRequired = false) }
            }
        }
    }

    fun download(app: MarketAppInfo) {
        hintStandardSystemUpdate(listOf(app))
        startDownload(app)
    }

    fun downloadAll(apps: List<MarketAppInfo>) {
        // 批量更新只提示一次，避免每个系统应用各弹一条
        hintStandardSystemUpdate(apps)
        apps.forEach(::startDownload)
    }

    /** 标准安装下系统应用的签名常与系统自带版本不一致，会被系统拒绝；提前提示改用 Root / Shizuku。 */
    private fun hintStandardSystemUpdate(apps: List<MarketAppInfo>) {
        if (apps.none { it.isSystemApp }) return
        viewModelScope.launch {
            if (installerPrefs.mode() == InstallerMode.STANDARD) {
                uiPlatform.showToast(getString(Res.string.update_system_app_standard_hint))
            }
        }
    }

    private fun startDownload(app: MarketAppInfo) {
        if (!pendingDownloads.add(app.packageName)) return
        viewModelScope.launch {
            try {
                runCatchingCancellable {
                    sources.downloadUpdateMeta(app.source, app)
                }
                    .onSuccess { downloads.start(it) }
                    .onFailure { uiPlatform.showToast(it.message ?: "Download failed") }
            } finally {
                pendingDownloads.remove(app.packageName)
            }
        }
    }

    fun installDownloaded(packageName: String) = downloads.install(packageName)
    fun cancelDownload(packageName: String) = downloads.cancel(packageName)

    fun ignoreOnce(app: MarketAppInfo) = persist { prefs.ignoreOnce(app) }
    fun ignorePermanently(app: MarketAppInfo) = persist { prefs.ignorePermanently(app) }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun derive(
        raw: RawUpdates,
        showSystem: Boolean,
        removeAds: Boolean,
        filterGames: Boolean,
        filterReservations: Boolean,
        permanent: List<IgnoredUpdate>,
        once: List<IgnoredUpdate>,
    ): UpdatesUiState {
        val visible = raw.all
            .filter { showSystem || !it.isSystemApp }
            .filterNot { app ->
                permanent.any { it.packageName == app.packageName } ||
                        once.any { it.packageName == app.packageName && it.versionCode == app.versionCode }
            }
        return UpdatesUiState(
            loading = raw.loading,
            checkFailed = raw.checkFailed,
            appListPermissionRequired = raw.appListPermissionRequired,
            showSystemUpdates = showSystem,
            removeSearchAds = removeAds,
            filterQuickGames = filterGames,
            filterReservationApps = filterReservations,
            updates = visible,
        )
    }
}

internal fun mergeUpdateSources(
    primary: List<MarketAppInfo>,
    secondary: List<MarketAppInfo>,
): List<MarketAppInfo> {
    val merged = LinkedHashMap<String, MarketAppInfo>(primary.size + secondary.size)
    primary.forEach { merged[it.packageName] = it }
    secondary.forEach { incoming ->
        val current = merged[incoming.packageName]
        if (current == null || incoming.versionCode > current.versionCode) {
            merged[incoming.packageName] = incoming
        }
    }
    return merged.values.toList()
}

internal fun preserveKnownDeltaSizes(
    previous: List<MarketAppInfo>,
    incoming: List<MarketAppInfo>,
): List<MarketAppInfo> {
    if (incoming.isEmpty()) return incoming
    val sourceSafeIncoming = incoming.map { app ->
        // 三星商店的 deltaSize 语义不可信，展示阶段强制清零
        if (!app.source.capabilities.reportsDeltaSize && app.deltaSize != 0L) {
            app.copy(deltaSize = 0L)
        } else {
            app
        }
    }
    if (previous.isEmpty()) return sourceSafeIncoming
    val previousByPackage = previous.associateBy(MarketAppInfo::packageName)
    return sourceSafeIncoming.map { current ->
        val cached = previousByPackage[current.packageName] ?: return@map current
        val cachedDeltaSize = cached.deltaSize.takeIf { deltaSize ->
            current.deltaSize <= 0L &&
                    deltaSize > 0L &&
                    deltaSize < current.apkSize &&
                    cached.source == current.source &&
                    cached.versionCode == current.versionCode &&
                    cached.installedVersionCode == current.installedVersionCode &&
                    cached.apkSize == current.apkSize &&
                    cached.installedBaseApkPath == current.installedBaseApkPath &&
                    cached.installedSplits == current.installedSplits
        }
        if (cachedDeltaSize == null) current else current.copy(deltaSize = cachedDeltaSize)
    }
}
