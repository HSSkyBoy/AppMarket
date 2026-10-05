package top.app.market.data.repository

import top.app.market.data.remote.oppo.OppoApi
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.CategoryOption
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.hasInstalledSplits
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult
import top.app.market.domain.repository.InstalledApkHashRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.InstallerPreferencesRepository
import top.app.market.domain.repository.OppoRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class OppoRepositoryImpl(
    private val api: OppoApi,
    private val installedPackages: InstalledPackagesRepository,
    private val updatePreferences: UpdatePreferencesRepository,
    private val installedApkHash: InstalledApkHashRepository,
    private val installerPreferences: InstallerPreferencesRepository,
) : OppoRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        var result = api.search(keyword, page)
        if (updatePreferences.currentRemoveSearchAds()) {
            result = result.copy(items = result.items.filterNot(MarketAppInfo::isAd))
        }
        result.copy(
            items = result.items.distinctBy { it.packageName.lowercase() },
        )
    }

    override suspend fun categories(games: Boolean): List<CategoryOption> = withContext(Dispatchers.Default) {
        api.categories(games).map { CategoryOption(id = it.id.toString(), name = it.name) }
    }

    override suspend fun categoryApps(categoryId: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        val id = categoryId.toLongOrNull() ?: throw MarketException("OPPO 分类 id 无效")
        val result = api.categoryApps(id, page)
        // 浏览分类时不展示推广位（与是否开启「去除搜索广告」无关）
        result.copy(items = result.items.filterNot(MarketAppInfo::isAd).distinctBy { it.packageName.lowercase() })
    }

    override suspend fun appDetail(appId: Long, packageName: String, externalQuery: String?): AppDetail =
        withContext(Dispatchers.Default) { api.appDetail(appId, packageName, externalQuery) }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    override suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        val deltaEnabled = installerPreferences.deltaUpdateEnabled()
        val preparedApp = app.withOppoOldApkHash(deltaEnabled)
        val currentInstalled = installedPackages.installedPackage(app.packageName)
            ?.let { resolveOldApkHash(it, deltaEnabled) }
        api.downloadUpdateMeta(preparedApp, currentInstalled)
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        // The hash only negotiates an incremental package; it is not needed to discover whether a
        // newer version exists. Hash the selected app later in downloadUpdateMeta instead of reading
        // every installed base APK whenever the updates page is refreshed.
        api.checkUpdates(installedPackages.installed().withoutOldApkHashes())
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult =
        withContext(Dispatchers.Default) { api.checkManualUpdate(request) }

    override suspend fun beautyFeed(page: Int, pageSize: Int): TodayFeedPage =
        withContext(Dispatchers.Default) { api.beautyFeed(page, pageSize) }

    override suspend fun beautyArticle(snippetId: String): TodayArticle =
        withContext(Dispatchers.Default) { api.beautyArticle(snippetId) }

    private suspend fun resolveOldApkHash(app: InstalledPackage, deltaEnabled: Boolean): InstalledPackage = when {
        !deltaEnabled || hasSplits(app.splits) || app.baseApkPath.isBlank() -> app.copy(oldApkHash = "0")
        app.oldApkHash.isNotBlank() && app.oldApkHash != "0" -> app
        else -> app.copy(oldApkHash = installedApkHash.md5(app.baseApkPath))
    }

    private suspend fun MarketAppInfo.withOppoOldApkHash(deltaEnabled: Boolean): MarketAppInfo = when {
        !deltaEnabled || hasInstalledSplits() -> copy(installedOldApkHash = "0", deltaSize = 0L)
        installedOldApkHash.isNotBlank() && installedOldApkHash != "0" -> this
        installedBaseApkPath.isBlank() -> copy(installedOldApkHash = "0", deltaSize = 0L)
        else -> copy(installedOldApkHash = installedApkHash.md5(installedBaseApkPath))
    }

    private fun hasSplits(value: String): Boolean = value.isNotBlank() && value != "0"

    private fun List<InstalledPackage>.withoutOldApkHashes(): List<InstalledPackage> =
        map { app -> if (app.oldApkHash == "0") app else app.copy(oldApkHash = "0") }

}
