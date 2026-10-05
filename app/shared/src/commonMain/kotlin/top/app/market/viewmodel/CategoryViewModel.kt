package top.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.model.market.AppCategory
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.AppSubCategory
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.isDownloadBlocked
import top.app.market.domain.model.market.isReservation
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.MarketSourceRepository
import top.app.market.domain.repository.PackageRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import top.app.market.platform.UiPlatform
import top.app.market.ui.model.AppActionKind
import top.app.market.ui.model.SearchResultItem
import top.app.market.ui.model.resolveActionKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class CategoryUiState(
    val items: List<SearchResultItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String = "",
    val epoch: Int = 0,
)

/** 分区列表的加载键：游戏无子分类，应用按子分类各自独立翻页与缓存。 */
data class CategoryKey(val category: AppCategory, val subCategory: AppSubCategory?)

private fun sectionKey(category: AppCategory, subCategory: AppSubCategory): CategoryKey =
    CategoryKey(category, subCategory.takeIf { category == AppCategory.APPS })

/** 取指定分区状态，尚未加载时为空状态。 */
fun Map<CategoryKey, CategoryUiState>.of(category: AppCategory, subCategory: AppSubCategory): CategoryUiState =
    this[sectionKey(category, subCategory)] ?: CategoryUiState()

class CategoryViewModel(
    private val sources: MarketSourceRepository,
    private val prefs: UpdatePreferencesRepository,
    private val packages: PackageRepository,
    private val downloads: DownloadRepository,
    private val uiPlatform: UiPlatform,
) : ViewModel() {
    private val _apps = MutableStateFlow(AppSubCategory.TOOLS)
    val appSubCategory: StateFlow<AppSubCategory> = _apps.asStateFlow()

    private val sections = MutableStateFlow<Map<CategoryKey, CategoryUiState>>(emptyMap())
    val sectionStates: StateFlow<Map<CategoryKey, CategoryUiState>> = sections.asStateFlow()
    private val nextPage = mutableMapOf<CategoryKey, Int>()
    private val jobs = mutableMapOf<CategoryKey, Job>()
    private var activeSource = prefs.categorySource.value
    private var generation = 0L
    private val pendingDownloads = mutableSetOf<String>()

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    init {
        viewModelScope.launch {
            // 来源切换后旧列表全部作废
            prefs.categorySource.collectLatest { source ->
                if (source == activeSource) return@collectLatest
                activeSource = source
                generation++
                jobs.values.forEach(Job::cancel)
                jobs.clear()
                nextPage.clear()
                sections.value = emptyMap()
            }
        }
        viewModelScope.launch {
            packages.changes.collect { change ->
                sections.update { all ->
                    all.mapValues { (_, state) ->
                        state.copy(items = state.items.map { item ->
                            if (item.app.packageName != change.packageName) item
                            else {
                                val code = change.installedVersionCode ?: 0L
                                val app = item.app.copy(
                                    installedVersionCode = code,
                                    installedVersionName = change.installedVersionName,
                                )
                                item.copy(app = app, actionKind = actionKind(app, code))
                            }
                        })
                    }
                }
            }
        }
    }

    fun selectSubCategory(value: AppSubCategory) {
        _apps.value = value
    }

    /** 首次进入分区时加载第一页；已有数据或正在加载则忽略。 */
    fun ensureLoaded(category: AppCategory, subCategory: AppSubCategory) {
        val key = sectionKey(category, subCategory)
        val state = sections.value[key]
        if (state != null && (state.items.isNotEmpty() || state.loading)) return
        load(key, replace = true)
    }

    fun retry(category: AppCategory, subCategory: AppSubCategory) =
        load(sectionKey(category, subCategory), replace = true)

    fun loadMore(category: AppCategory, subCategory: AppSubCategory) {
        val key = sectionKey(category, subCategory)
        val state = sections.value[key] ?: return
        if (state.loading || state.loadingMore || !state.hasMore || state.items.isEmpty()) return
        load(key, replace = false)
    }

    private fun load(key: CategoryKey, replace: Boolean) {
        if (jobs[key]?.isActive == true) return
        val source = activeSource
        val gen = generation
        if (replace) nextPage[key] = 0
        val page = nextPage[key] ?: 0
        update(key) { it.copy(loading = replace, loadingMore = !replace, errorMessage = "") }
        jobs[key] = viewModelScope.launch {
            val result = runCatchingCancellable {
                sources.categoryApps(source, key.category, key.subCategory ?: AppSubCategory.TOOLS, page)
            }
            if (gen != generation) return@launch
            val fetched = result.getOrNull()
            if (fetched == null) {
                update(key) {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Load failed",
                    )
                }
                return@launch
            }
            nextPage[key] = page + 1
            val existing = if (replace) emptyList() else sections.value[key]?.items.orEmpty()
            val known = existing.mapTo(HashSet()) { it.app.packageName }
            val incoming = resolveItems(fetched.items.filter { known.add(it.packageName) })
            if (gen != generation) return@launch
            update(key) {
                it.copy(
                    items = existing + incoming,
                    loading = false,
                    loadingMore = false,
                    hasMore = fetched.hasMore && fetched.items.isNotEmpty(),
                    epoch = if (replace) it.epoch + 1 else it.epoch,
                )
            }
        }
    }

    private fun update(key: CategoryKey, transform: (CategoryUiState) -> CategoryUiState) {
        sections.update { it + (key to transform(it[key] ?: CategoryUiState())) }
    }

    private suspend fun resolveItems(apps: List<MarketAppInfo>): List<SearchResultItem> {
        if (apps.isEmpty()) return emptyList()
        val installed = packages.installedVersionCodes(apps.map { it.packageName })
        return apps.map { app ->
            val code = installed[app.packageName] ?: 0L
            val resolved = app.copy(installedVersionCode = code)
            SearchResultItem(resolved, actionKind(resolved, code))
        }
    }

    private fun actionKind(app: MarketAppInfo, installedVersionCode: Long): AppActionKind =
        if (app.isReservation()) AppActionKind.RESERVE
        else if (installedVersionCode > 0L && app.isDownloadBlocked()) AppActionKind.OPEN
        else resolveActionKind(installedVersionCode, app.versionCode)

    fun onAction(item: SearchResultItem) {
        when (item.actionKind) {
            AppActionKind.RESERVE -> Unit
            AppActionKind.OPEN -> {
                val app = item.app
                val opened = if (app.source.capabilities.prefersOpenLinkLaunch) {
                    packages.openLink(app.openLink) || packages.openApp(app.packageName)
                } else {
                    packages.openApp(app.packageName) || packages.openLink(app.openLink)
                }
                if (!opened) uiPlatform.showToast(app.displayName)
            }

            else -> download(item.app, item.actionKind == AppActionKind.UPDATE)
        }
    }

    fun download(app: MarketAppInfo, update: Boolean = false) {
        if (app.isDownloadBlocked()) return
        if (!pendingDownloads.add(app.packageName)) return
        viewModelScope.launch {
            try {
                runCatchingCancellable {
                    if (update) sources.downloadUpdateMeta(app.source, app)
                    else sources.downloadMeta(app.source, app)
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
}
