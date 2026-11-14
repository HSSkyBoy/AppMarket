package top.app.market.data.remote.tencent

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppScreenshot
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.ScreenshotOrientation
import top.app.market.domain.model.market.SearchPage
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val SearchUrl = "https://sj.qq.com/search"
private const val ApkInfoUrl = "https://upage.html5.qq.com/wechat-apkinfo"
private const val BrowserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
private const val UpdateParallelism = 4
private const val CacheLimit = 64
private val CacheTtl = 3.minutes

/** 搜索解析网页，详情与下载走 H5 apkinfo 接口，均无需登录或签名。 */
internal class TencentApi(
    private val client: HttpClient,
) {
    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<String, Pair<Instant, TencentDetail?>>()

    /** 搜索页一次返回全部结果，没有分页。 */
    suspend fun search(keyword: String, page: Int): SearchPage {
        val query = keyword.trim()
        if (query.isEmpty() || page > 0) return SearchPage(emptyList(), hasMore = false)
        val response = client.get("$SearchUrl?q=${query.encodeURLParameter()}") {
            header(HttpHeaders.UserAgent, BrowserAgent)
        }
        if (!response.status.isSuccess()) throw MarketException("应用宝搜索失败：HTTP ${response.status.value}")
        return SearchPage(items = parseSearchPage(response.bodyAsText()), hasMore = false)
    }

    suspend fun appDetail(packageName: String): AppDetail {
        val detail = lookup(packageName) ?: throw MarketException("应用宝未收录该应用：$packageName")
        return AppDetail(
            app = detail.info,
            brief = detail.brief,
            introduction = detail.description.ifBlank { detail.brief },
            changeLog = detail.info.changeLog,
            category = detail.info.category,
            ageClassification = detail.age,
            downloadCount = detail.info.downloadCount,
            registrationNum = detail.registrationNum,
            updateTime = detail.updateTimeMillis,
            privacyUrl = detail.privacyUrl,
            screenshots = detail.screenshots.map { AppScreenshot(url = it, orientation = ScreenshotOrientation.PORTRAIT) },
            commentCount = detail.ratingCount,
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val detail = lookup(app.packageName) ?: throw MarketException("应用宝未收录该应用：${app.packageName}")
        if (!detail.downloadable) throw MarketException("应用宝暂不提供该应用的下载")
        val info = detail.info
        return DownloadMeta(
            appId = info.appId,
            packageName = info.packageName,
            displayName = info.displayName,
            versionName = info.versionName,
            versionCode = info.versionCode,
            url = detail.downloadUrl,
            size = info.apkSize,
            parts = listOf(
                DownloadPart("", "base", detail.downloadUrl, info.apkSize, detail.sha256.ifBlank { detail.md5 }),
            ),
            icon = info.icon,
            changeLog = info.changeLog,
            source = AppSource.TENCENT,
        )
    }

    /** 逐个查询已装应用；应用宝的版本与本机签名不一致时不推荐，避免覆盖安装失败。 */
    suspend fun checkUpdates(installed: List<InstalledPackage>): List<MarketAppInfo> {
        val candidates = installed.filter { it.versionCode > 0 }
        val permits = Semaphore(UpdateParallelism)
        return coroutineScope {
            candidates.map { local ->
                async {
                    val remote = runCatching { permits.withPermit { lookup(local.packageName) } }.getOrNull()
                    remote?.info?.takeIf { remote.isUpdateFor(local) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun TencentDetail.isUpdateFor(local: InstalledPackage): Boolean {
        val signerConflict = signerSha256.isNotBlank() && local.signerSha256.isNotBlank() &&
            !signerSha256.equals(local.signerSha256, ignoreCase = true)
        return downloadable && !signerConflict && info.versionCode > local.versionCode
    }

    /** 详情页与下载常在几秒内连续请求同一个包，短时缓存省掉重复请求。 */
    private suspend fun lookup(packageName: String): TencentDetail? {
        val key = packageName.trim()
        val now = Clock.System.now()
        cacheLock.withLock {
            cache[key]?.takeIf { now - it.first < CacheTtl }?.let { return it.second }
        }
        val response = client.post(ApkInfoUrl) {
            header(HttpHeaders.UserAgent, BrowserAgent)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("packagename", key) }.toString())
        }
        if (!response.status.isSuccess()) throw MarketException("应用宝详情失败：HTTP ${response.status.value}")
        val detail = parseDetail(key, response.bodyAsText())
        cacheLock.withLock {
            if (cache.size >= CacheLimit) cache.remove(cache.keys.first())
            cache[key] = now to detail
        }
        return detail
    }
}
