package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult

/** TapTap anonymous source used for search, application details, downloads and updates. */
interface TapTapRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(appId: Long, packageName: String): AppDetail
    suspend fun todayFeed(page: Int = 0, pageSize: Int = 9): TodayFeedPage
    suspend fun recommendedGames(page: Int = 0, pageSize: Int = 15): SearchPage
    suspend fun todayArticle(rId: String): TodayArticle
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
    suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta
    suspend fun checkUpdates(): List<MarketAppInfo>
    suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult
}
