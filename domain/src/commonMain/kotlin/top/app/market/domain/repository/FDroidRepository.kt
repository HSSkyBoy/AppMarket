package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.preference.FDroidMirror

interface FDroidRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(packageName: String): AppDetail
    suspend fun downloadMeta(app: MarketAppInfo, mirror: FDroidMirror? = null): DownloadMeta
    suspend fun checkUpdates(): List<MarketAppInfo>
}
