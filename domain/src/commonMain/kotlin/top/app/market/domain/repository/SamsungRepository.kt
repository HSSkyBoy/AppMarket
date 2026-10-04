package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult

interface SamsungRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(appId: Long, packageName: String, externalQuery: String? = null): AppDetail
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
    suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta
    suspend fun checkUpdates(): List<MarketAppInfo>
    suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult
}
