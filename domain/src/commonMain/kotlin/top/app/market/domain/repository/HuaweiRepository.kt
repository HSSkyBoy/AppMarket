package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult

/** Huawei AppGallery anonymous source: search, detail, package delivery, updates and recommendations. */
interface HuaweiRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(appId: Long, packageName: String): AppDetail
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
    suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta
    suspend fun checkUpdates(): List<MarketAppInfo>
    suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult
}
