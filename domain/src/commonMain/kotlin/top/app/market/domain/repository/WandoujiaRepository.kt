package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.HistoricalVersion
import top.app.market.domain.model.market.HistoricalVersionPage
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage

/** 豌豆荚匿名只读接入：搜索、详情、当前版本与历史版本下载。 */
interface WandoujiaRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(appId: Long): AppDetail
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
    suspend fun checkUpdates(): List<MarketAppInfo>
    suspend fun historicalVersions(appId: Long, packageName: String, offset: Int = 0): HistoricalVersionPage
    fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta
}
