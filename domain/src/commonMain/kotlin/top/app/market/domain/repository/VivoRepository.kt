package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult

/** vivo 应用商店的匿名接入，覆盖搜索、详情、下载、更新和极光奖内容。 */
interface VivoRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage

    /** [vivoAppId] 是站内数字 id，即搜索结果的 [MarketAppInfo.appId]。 */
    suspend fun appDetail(vivoAppId: Long, packageName: String = ""): AppDetail

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta

    suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta

    suspend fun checkUpdates(): List<MarketAppInfo>

    suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult

    suspend fun auroraFeed(page: Int = 0, pageSize: Int = 6): TodayFeedPage

    suspend fun auroraArticle(rId: String): TodayArticle
}
