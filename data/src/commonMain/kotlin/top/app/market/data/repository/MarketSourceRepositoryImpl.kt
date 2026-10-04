package top.app.market.data.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppCategory
import top.app.market.domain.model.market.AppComments
import top.app.market.domain.model.market.AppSubCategory
import top.app.market.domain.model.market.XIAOMI_GAME_CATEGORY_ID
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult
import top.app.market.domain.repository.HonorRepository
import top.app.market.domain.repository.HuaweiRepository
import top.app.market.domain.repository.MarketRepository
import top.app.market.domain.repository.MarketSourceRepository
import top.app.market.domain.repository.OppoRepository
import top.app.market.domain.repository.SamsungRepository
import top.app.market.domain.repository.TodayRepository
import top.app.market.domain.repository.TapTapRepository
import top.app.market.domain.repository.VivoRepository
import top.app.market.domain.repository.WandoujiaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Central source router. Capabilities are declared on [AppSource.capabilities]. */
internal class MarketSourceRepositoryImpl(
    private val market: MarketRepository,
    private val today: TodayRepository,
    private val vivo: VivoRepository,
    private val wandoujia: WandoujiaRepository,
    private val oppo: OppoRepository,
    private val samsung: SamsungRepository,
    private val honor: HonorRepository,
    private val huawei: HuaweiRepository,
    private val tapTap: TapTapRepository,
) : MarketSourceRepository {
    override suspend fun search(source: AppSource, keyword: String, page: Int): SearchPage = when (source) {
        AppSource.XIAOMI -> market.search(keyword, page)
        AppSource.VIVO -> vivo.search(keyword, page)
        AppSource.WANDOUJIA -> wandoujia.search(keyword, page)
        AppSource.OPPO -> oppo.search(keyword, page)
        AppSource.SAMSUNG -> samsung.search(keyword, page)
        AppSource.HONOR -> honor.search(keyword, page)
        AppSource.HUAWEI -> huawei.search(keyword, page)
        AppSource.TAPTAP -> tapTap.search(keyword, page)
    }

    override suspend fun categoryApps(
        source: AppSource,
        category: AppCategory,
        subCategory: AppSubCategory,
        page: Int,
    ): SearchPage = when {
        category == AppCategory.GAMES && source == AppSource.TAPTAP -> tapTap.recommendedGames(page)
        category == AppCategory.GAMES -> market.categoryApps(XIAOMI_GAME_CATEGORY_ID, page)
        else -> market.categoryApps(subCategory.xiaomiCategoryId, page)
    }

    override suspend fun appDetail(
        source: AppSource,
        appId: Long,
        packageName: String,
        externalQuery: String?,
    ): AppDetail = when (source) {
        AppSource.XIAOMI -> market.appDetail(appId, packageName, externalQuery)
        AppSource.VIVO -> vivo.appDetail(
            resolveStoreAppId(source, packageName, appId) { vivo.search(it, 0) },
            packageName,
        )

        AppSource.WANDOUJIA -> wandoujia.appDetail(
            resolveStoreAppId(source, packageName, appId) { wandoujia.search(it, 0) },
        )

        AppSource.OPPO -> oppo.appDetail(appId, packageName, externalQuery)
        AppSource.SAMSUNG -> samsung.appDetail(appId, packageName, externalQuery)
        AppSource.HONOR -> honor.appDetail(appId, packageName)
        AppSource.HUAWEI -> huawei.appDetail(appId, packageName)
        AppSource.TAPTAP -> tapTap.appDetail(appId, packageName)
    }

    override suspend fun appComments(source: AppSource, app: MarketAppInfo): AppComments {
        if (!source.capabilities.supportsComments) return AppComments(emptyList(), 0L)
        val xiaomi = app.onXiaomi()
        return market.appComments(xiaomi.appId, xiaomi.versionCode)
    }

    override suspend fun sameDeveloperApps(source: AppSource, app: MarketAppInfo): List<MarketAppInfo> {
        if (!source.capabilities.supportsSameDeveloperApps) return emptyList()
        val xiaomi = app.onXiaomi()
        return filterSameDeveloperCandidates(app.packageName, market.sameDeveloperApps(xiaomi.appId))
    }

    override suspend fun downloadMeta(source: AppSource, app: MarketAppInfo, keyword: String): DownloadMeta = when (source) {
        AppSource.XIAOMI -> market.downloadMeta(app, keyword)
        AppSource.VIVO -> vivo.downloadMeta(app)
        AppSource.WANDOUJIA -> wandoujia.downloadMeta(app)
        AppSource.OPPO -> oppo.downloadMeta(app)
        AppSource.SAMSUNG -> samsung.downloadMeta(app)
        AppSource.HONOR -> honor.downloadMeta(app)
        AppSource.HUAWEI -> huawei.downloadMeta(app)
        AppSource.TAPTAP -> tapTap.downloadMeta(app)
    }

    override suspend fun downloadUpdateMeta(source: AppSource, app: MarketAppInfo): DownloadMeta = when (source) {
        AppSource.XIAOMI -> market.downloadUpdateMeta(app)
        AppSource.VIVO -> vivo.downloadUpdateMeta(app)
        // 豌豆荚无原生更新元数据协议，回退小米商店解析
        AppSource.WANDOUJIA -> market.downloadUpdateMeta(app.onXiaomi())
        AppSource.OPPO -> oppo.downloadUpdateMeta(app)
        AppSource.SAMSUNG -> samsung.downloadUpdateMeta(app)
        AppSource.HONOR -> honor.downloadUpdateMeta(app)
        AppSource.HUAWEI -> huawei.downloadUpdateMeta(app)
        AppSource.TAPTAP -> tapTap.downloadUpdateMeta(app)
    }

    override suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo> =
        market.loadReconciledCachedUpdates()

    override fun checkUpdatesFlow(source: AppSource): Flow<List<MarketAppInfo>> = when (source) {
        AppSource.XIAOMI -> market.checkUpdatesFlow()
        AppSource.VIVO -> flow { emit(vivo.checkUpdates()) }
        AppSource.WANDOUJIA -> flow { emit(wandoujia.checkUpdates()) }
        AppSource.OPPO -> flow { emit(oppo.checkUpdates()) }
        AppSource.SAMSUNG -> flow { emit(samsung.checkUpdates()) }
        AppSource.HONOR -> flow { emit(honor.checkUpdates()) }
        AppSource.HUAWEI -> flow { emit(huawei.checkUpdates()) }
        AppSource.TAPTAP -> flow { emit(tapTap.checkUpdates()) }
    }

    override suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult =
        when (source) {
            AppSource.XIAOMI -> market.checkManualUpdate(request)
            AppSource.VIVO -> vivo.checkManualUpdate(request)
            // 豌豆荚无原生手动更新协议，回退小米商店解析
            AppSource.WANDOUJIA -> market.checkManualUpdate(request)
            AppSource.OPPO -> oppo.checkManualUpdate(request)
            AppSource.SAMSUNG -> samsung.checkManualUpdate(request)
            AppSource.HONOR -> honor.checkManualUpdate(request)
            AppSource.HUAWEI -> huawei.checkManualUpdate(request)
            AppSource.TAPTAP -> tapTap.checkManualUpdate(request)
        }

    override suspend fun goldMiFeed(source: AppSource, page: Int, pageSize: Int): TodayFeedPage =
        when (source) {
            AppSource.OPPO -> oppo.beautyFeed(page, pageSize)
            // 豌豆荚 / 三星 / 华为 / 荣耀无独立今日内容，回退小米商店今日
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.SAMSUNG, AppSource.HUAWEI, AppSource.HONOR ->
                today.goldMiFeed(page, pageSize)

            AppSource.VIVO -> vivo.auroraFeed(page, pageSize)
            AppSource.TAPTAP -> tapTap.todayFeed(page, pageSize)
        }

    override suspend fun todayArticle(source: AppSource, rId: String): TodayArticle =
        when (source) {
            AppSource.OPPO -> oppo.beautyArticle(rId)
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.SAMSUNG, AppSource.HUAWEI, AppSource.HONOR ->
                today.todayArticle(rId)

            AppSource.VIVO -> vivo.auroraArticle(rId)
            AppSource.TAPTAP -> tapTap.todayArticle(rId)
        }

    private suspend fun MarketAppInfo.onXiaomi(): MarketAppInfo {
        if (source == AppSource.XIAOMI) return this
        return market.appDetail(
            appId = 0L,
            packageName = packageName,
            externalQuery = "id=$packageName",
        ).app.copy(
            installedVersionName = installedVersionName,
            installedVersionCode = installedVersionCode,
            installedOldApkHash = installedOldApkHash,
            installedBaseApkPath = installedBaseApkPath,
            installedSplits = installedSplits,
        )
    }
}

private suspend fun resolveStoreAppId(
    source: AppSource,
    packageName: String,
    appId: Long,
    search: suspend (String) -> SearchPage,
): Long = appId.takeIf { it > 0L } ?: search(packageName).items
    .firstOrNull { it.packageName.equals(packageName, true) }
    ?.appId
?: throw IllegalStateException("${source.token} 未收录该应用")

internal fun filterSameDeveloperCandidates(
    currentPackageName: String,
    candidates: List<MarketAppInfo>,
): List<MarketAppInfo> = candidates
    .filterNot { it.packageName.equals(currentPackageName, ignoreCase = true) }
    .distinctBy { it.packageName.lowercase() }
