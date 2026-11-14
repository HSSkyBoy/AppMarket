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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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

private const val SearchUrl = "https://sj.qq.com/search"
private const val DetailUrl = "https://upage.html5.qq.com/wechat-apkinfo"
private const val SearchCardId = "YYB_HOME_SEARCH_NORMAL_GAME"
private const val UpdateConcurrency = 4
private const val UserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

// 云游戏 / PC 软件：无法安装到手机
private val UnsupportedGameTypes = setOf("7", "8")
private val NextData = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

/** 应用详情接口返回的单个应用记录。 */
internal class TencentDetail(
    val info: MarketAppInfo,
    val sha256: String,
    val md5: String,
    val downloadUrl: String,
    val signerSha256: String,
    val brief: String,
    val description: String,
    val category: String,
    val age: String,
    val registrationNum: String,
    val privacyUrl: String,
    val updateTime: Long,
    val ratingCount: Long,
    val screenshots: List<String>,
    val blocked: Boolean,
)

/**
 * 应用宝数据层：搜索解析网页内嵌的 `__NEXT_DATA__`，详情与下载走 H5 的 apkinfo 接口。
 * 两个接口都无需签名或登录。
 */
internal class TencentApi(
    private val client: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 搜索页一次返回全部结果，没有分页。 */
    suspend fun search(keyword: String, page: Int): SearchPage {
        val query = keyword.trim()
        if (query.isEmpty() || page > 0) return SearchPage(emptyList(), hasMore = false)

        val response = client.get("$SearchUrl?q=${query.encodeURLParameter()}") {
            header(HttpHeaders.UserAgent, UserAgent)
        }
        if (!response.status.isSuccess()) throw MarketException("应用宝搜索失败：HTTP ${response.status.value}")

        val html = response.bodyAsText()
        val data = NextData.find(html)?.groupValues?.get(1)
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: throw MarketException("应用宝搜索页结构已变化")
        if (data.str("page") != "/search") throw MarketException("应用宝搜索页结构已变化")

        val card = data.obj("props")?.obj("pageProps")?.obj("dynamicCardResponse")
        if (card?.str("ret") != "0") throw MarketException("应用宝搜索失败")
        val items = card.obj("data")?.arr("components").orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("cardId") == SearchCardId }
            ?.obj("data")?.arr("itemData").orEmpty()

        val seen = HashSet<String>()
        val apps = items.mapNotNull { (it as? JsonObject)?.let(::searchItem) }
            .filter { seen.add(it.packageName.lowercase()) }
        return SearchPage(items = apps, hasMore = false)
    }

    /** 查询单个应用；应用宝未收录时返回 null。 */
    suspend fun detail(packageName: String): TencentDetail? {
        val pkg = packageName.trim()
        val response = client.post(DetailUrl) {
            header(HttpHeaders.UserAgent, UserAgent)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("packagename", pkg) }.toString())
        }
        if (!response.status.isSuccess()) throw MarketException("应用宝详情失败：HTTP ${response.status.value}")

        val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            ?: throw MarketException("应用宝详情解析失败")
        if (root.str("ret") != "0") throw MarketException(root.str("err_msg").ifBlank { "应用宝详情失败" })
        val record = root.obj("app_detail_records")?.obj(pkg) ?: return null
        return parseDetail(pkg, record)
    }

    suspend fun appDetail(packageName: String): AppDetail {
        val detail = detail(packageName) ?: throw MarketException("应用宝未收录该应用：$packageName")
        return AppDetail(
            app = detail.info,
            brief = detail.brief,
            introduction = detail.description.ifBlank { detail.brief },
            changeLog = detail.info.changeLog,
            category = detail.category,
            ageClassification = detail.age,
            downloadCount = detail.info.downloadCount,
            registrationNum = detail.registrationNum,
            updateTime = detail.updateTime,
            privacyUrl = detail.privacyUrl,
            screenshots = detail.screenshots.map { AppScreenshot(url = it, orientation = ScreenshotOrientation.PORTRAIT) },
            commentCount = detail.ratingCount,
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val detail = detail(app.packageName) ?: throw MarketException("应用宝未收录该应用：${app.packageName}")
        if (detail.blocked) throw MarketException("应用宝暂不提供该应用的下载")
        val hash = detail.sha256.ifBlank { detail.md5 }
        return DownloadMeta(
            appId = detail.info.appId,
            packageName = detail.info.packageName,
            displayName = detail.info.displayName,
            versionName = detail.info.versionName,
            versionCode = detail.info.versionCode,
            url = detail.downloadUrl,
            size = detail.info.apkSize,
            parts = listOf(DownloadPart("", "base", detail.downloadUrl, detail.info.apkSize, hash)),
            icon = detail.info.icon,
            changeLog = detail.info.changeLog,
            source = AppSource.TENCENT,
        )
    }

    /** 逐个查询已装应用（有限并发）；签名与本机不一致时跳过，避免覆盖安装失败。 */
    suspend fun checkUpdates(installed: List<InstalledPackage>): List<MarketAppInfo> {
        val candidates = installed.filter { it.versionCode > 0 }
        if (candidates.isEmpty()) return emptyList()
        val gate = Semaphore(UpdateConcurrency)
        return coroutineScope {
            candidates.map { local ->
                async {
                    val remote = runCatching { gate.withPermit { detail(local.packageName) } }.getOrNull()
                        ?: return@async null
                    val signerMismatch = remote.signerSha256.isNotBlank() && local.signerSha256.isNotBlank() &&
                        !remote.signerSha256.equals(local.signerSha256, ignoreCase = true)
                    if (remote.blocked || signerMismatch || remote.info.versionCode <= local.versionCode) null else remote.info
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun searchItem(item: JsonObject): MarketAppInfo? {
        val pkg = item.str("pkg_name").ifBlank { return null }
        if (item.str("game_type") in UnsupportedGameTypes || item.str("is_booking") == "1") return null
        return MarketAppInfo(
            appId = item.str("app_id").toLongOrNull() ?: pkg.hashCode().toLong(),
            packageName = pkg,
            displayName = item.str("name").ifBlank { pkg },
            publisherName = item.str("developer"),
            versionName = item.str("version_name"),
            versionCode = 0L,
            icon = https(item.str("icon")),
            apkSize = item.str("apk_size").toLongOrNull() ?: 0L,
            ratingScore = item.str("average_rating").toDoubleOrNull()?.coerceIn(0.0, 5.0) ?: 0.0,
            openLink = "https://sj.qq.com/appdetail/$pkg",
            source = AppSource.TENCENT,
            category = item.str("cate_name_new"),
        )
    }

    private fun parseDetail(pkg: String, record: JsonObject): TencentDetail? {
        val apk = record.obj("apk_all_data") ?: return null
        if (apk.str("package_name") != pkg) return null
        val info = record.obj("app_info")
        val rating = record.obj("app_rating_info")
        val url = https(apk.str("url"))
        val blocked = url.isBlank() || info?.str("online_status") == "0"
        val app = MarketAppInfo(
            appId = apk.str("app_id").toLongOrNull() ?: pkg.hashCode().toLong(),
            packageName = pkg,
            displayName = apk.str("name").ifBlank { info?.str("name").orEmpty() }.ifBlank { pkg },
            publisherName = info?.str("developer").orEmpty().ifBlank { info?.str("author").orEmpty() },
            versionName = apk.str("version_name"),
            versionCode = apk.str("version_code").toLongOrNull() ?: 0L,
            icon = https(apk.str("logo256").ifBlank { apk.str("logo_big") }),
            apkSize = apk.str("size_byte").toLongOrNull() ?: 0L,
            ratingScore = rating?.str("average_rating")?.toDoubleOrNull()?.coerceIn(0.0, 5.0) ?: 0.0,
            changeLog = apk.str("feature").trim(),
            openLink = "https://sj.qq.com/appdetail/$pkg",
            source = AppSource.TENCENT,
            category = info?.str("category_name_new").orEmpty().ifBlank { info?.str("category_name").orEmpty() },
            downloadCount = info?.str("download_cnt_total")?.toLongOrNull() ?: 0L,
            downloadBlockReason = if (blocked) "应用宝暂不提供下载" else "",
        )
        return TencentDetail(
            info = app,
            sha256 = apk.str("sha256").lowercase(),
            md5 = apk.str("apk_md5").lowercase(),
            downloadUrl = url,
            signerSha256 = apk.str("signatureSha256").lowercase(),
            brief = info?.str("editor_intro").orEmpty(),
            description = info?.str("desc").orEmpty(),
            category = app.category,
            age = info?.str("suitable_age").orEmpty(),
            registrationNum = info?.str("icp_number").orEmpty(),
            privacyUrl = info?.str("privacy_agreement").orEmpty(),
            updateTime = (apk.str("update_time").toLongOrNull() ?: 0L) * 1000L,
            ratingCount = rating?.str("rating_count")?.toLongOrNull() ?: 0L,
            screenshots = apk.arr("snapshot_bigs").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(::https),
            blocked = blocked,
        )
    }

    private fun https(url: String): String = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
}
