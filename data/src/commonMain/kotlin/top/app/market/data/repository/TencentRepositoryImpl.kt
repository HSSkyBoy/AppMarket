package top.app.market.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.app.market.data.remote.tencent.TencentApi
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.TencentRepository

internal class TencentRepositoryImpl(
    private val api: TencentApi,
    private val installedPackages: InstalledPackagesRepository,
) : TencentRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(packageName: String): AppDetail = withContext(Dispatchers.Default) {
        api.appDetail(packageName)
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        api.checkUpdates(installedPackages.installed())
    }
}
