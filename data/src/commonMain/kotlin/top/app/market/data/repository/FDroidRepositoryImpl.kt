package top.app.market.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.app.market.data.remote.fdroid.FDroidApi
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.preference.FDroidMirror
import top.app.market.domain.repository.FDroidRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.UpdatePreferencesRepository

internal class FDroidRepositoryImpl(
    private val api: FDroidApi,
    private val preferences: UpdatePreferencesRepository,
    private val installedPackages: InstalledPackagesRepository,
) : FDroidRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage {
        val mirror = preferences.fdroidMirror.value
        return api.search(keyword, page, mirror)
    }

    override suspend fun appDetail(packageName: String): AppDetail {
        val mirror = preferences.fdroidMirror.value
        return api.appDetail(packageName, mirror)
    }

    override suspend fun downloadMeta(app: MarketAppInfo, mirror: FDroidMirror?): DownloadMeta {
        val targetMirror = mirror ?: preferences.fdroidMirror.value
        return api.downloadMeta(app, targetMirror)
    }
    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        api.checkUpdates(installedPackages.installed(), preferences.fdroidMirror.value)
    }
}
