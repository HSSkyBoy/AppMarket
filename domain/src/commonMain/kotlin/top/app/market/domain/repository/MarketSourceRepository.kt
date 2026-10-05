package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppCategory
import top.app.market.domain.model.market.AppComments
import top.app.market.domain.model.market.AppSubCategory
import top.app.market.domain.model.market.CategoryOption
import top.app.market.domain.model.market.GameRanking
import top.app.market.domain.model.market.GameSubCategory
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult
import kotlinx.coroutines.flow.Flow

/**
 * Unified market entry point used by the UI.
 *
 * A source is selected once here, rather than in every screen. Implementations may fall back to
 * Xiaomi for an operation that a third-party store does not expose; the returned app keeps the
 * source that actually owns its download URL.
 */
interface MarketSourceRepository {
    suspend fun search(source: AppSource, keyword: String, page: Int = 0): SearchPage

    suspend fun categoryApps(
        source: AppSource,
        category: AppCategory,
        subCategory: AppSubCategory = AppSubCategory.TOOLS,
        ranking: GameRanking = GameRanking.HOT,
        gameSubCategory: GameSubCategory = GameSubCategory.ALL,
        optionId: String? = null,
        page: Int = 0,
    ): SearchPage

    suspend fun categoryOptions(source: AppSource, category: AppCategory): List<CategoryOption>

    suspend fun appDetail(
        source: AppSource,
        appId: Long,
        packageName: String,
        externalQuery: String? = null,
    ): AppDetail

    suspend fun appComments(source: AppSource, app: MarketAppInfo): AppComments

    suspend fun sameDeveloperApps(source: AppSource, app: MarketAppInfo): List<MarketAppInfo>

    suspend fun downloadMeta(source: AppSource, app: MarketAppInfo, keyword: String = app.displayName): DownloadMeta

    suspend fun downloadUpdateMeta(source: AppSource, app: MarketAppInfo): DownloadMeta

    suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo>

    fun checkUpdatesFlow(source: AppSource): Flow<List<MarketAppInfo>>

    suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult

    suspend fun goldMiFeed(source: AppSource, page: Int = 0, pageSize: Int = 9): TodayFeedPage

    suspend fun todayArticle(source: AppSource, rId: String): TodayArticle
}
