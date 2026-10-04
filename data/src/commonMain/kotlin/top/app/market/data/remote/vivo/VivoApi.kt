package top.app.market.data.remote.vivo

import top.app.market.data.platform.debugLog
import top.app.market.data.remote.kibToBytes
import top.app.market.data.remote.urlEncodeParameters
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppScreenshot
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.ScreenshotOrientation
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.profile.MarketProfile
import top.app.market.domain.repository.ProfileRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject

internal data class VivoPackageSnapshot(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val publisherName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: String,
    val downloadUrl: String,
    val sizeKiB: Long,
    val score: Double,
    val introduction: String,
    val screenshots: List<String>,
    val downloadCount: Long,
    val commentCount: Long,
    val uploadTime: Long,
    val isAd: Boolean,
)

private const val API = "https://main.appstore.vivo.com.cn"

// apk 与截图都是相对路径，主机与 icon_url 同源
private const val ASSET_BASE = "https://appstoreimg-ipv6.vivo.com.cn/appstore"
private const val APP_VERSION = "2100"
private const val PAGE_SIZE = 20

// upload_time 不带时区，服务端按北京时间下发
private val VIVO_ZONE = TimeZone.of("Asia/Shanghai")

private val htmlBr = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val htmlTag = Regex("<[^>]+>")

internal class VivoApi(
    private val client: HttpClient,
    private val profileStore: ProfileRepository,
) {

    suspend fun search(keyword: String, page: Int): SearchPage {
        val profile = profileStore.load(AppSource.VIVO)
        val response = client.post("$API/port/packages/") {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.UserAgent, vivoNativeUserAgent(profile))
            setBody(urlEncodeParameters(vivoSearchParams(profile, keyword, page)))
        }
        return parseVivoSearch(parseVivoObject(responseText(response)), page)
    }

    suspend fun appDetail(vivoAppId: Long): AppDetail {
        val json = packageListJson(vivoAppId)
        val app = parseVivoApp(json) ?: throw MarketException("vivo 未返回该应用的有效信息")
        return AppDetail(
            app = app,
            brief = json.str("app_remark"),
            introduction = vivoPlainText(json.str("introduction")),
            changeLog = json.str("updateDes"),
            category = json.str("category"),
            ageClassification = "",
            downloadCount = json.long("download_count"),
            registrationNum = json.str("icpInfo"),
            updateTime = vivoUploadTimeMillis(json.str("upload_time")),
            privacyUrl = json.str("privacyPolicyUrl"),
            screenshots = parseVivoScreenshots(json),
            // 该接口只给评分人数，没有评论正文
            commentCount = json.long("raters_count"),
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val json = packageListJson(app.appId)
        // problemLevel>=1 / problemSearchTips 表示 vivo 侧暂不提供下载,downloadApkFile 入口
        // 会直接返回 416;在进入下载器前按服务端提示拒绝,避免用户看到晦涩的 HTTP 416。
        if (json.long("problemLevel") >= 1L) {
            val serverMessage = json.str("problemSearchTips")
                .ifBlank { "vivo 暂不提供该应用的下载" }
            throw MarketException(serverMessage)
        }
        val apk = json.str("download_url")
        if (apk.isBlank()) throw MarketException("vivo 未提供该应用的下载地址")
        val resolved = resolveVivoDownloadAsset(client, vivoHttpsUrl(apk))
        val size = resolved.size
        val fullHash = json.str("md5").ifBlank { resolved.md5 }.lowercase()
        return DownloadMeta(
            appId = app.appId,
            packageName = json.str("package_name", app.packageName),
            displayName = json.str("title_zh", app.displayName),
            versionName = json.str("version_name", app.versionName),
            versionCode = json.long("version_code", app.versionCode),
            url = resolved.url,
            // size 只精确到 KB，拿它当期望值会被完整性校验判成残包；HEAD 失败时保留 0。
            size = size,
            parts = listOf(DownloadPart("", "base", resolved.url, size, fullHash)),
            icon = vivoHttpsUrl(json.str("icon_url", app.icon)),
            source = AppSource.VIVO,
        )
    }

    suspend fun downloadMeta(snapshot: VivoPackageSnapshot, app: MarketAppInfo): DownloadMeta {
        val rawUrl = snapshot.downloadUrl
        if (rawUrl.isBlank()) throw MarketException("vivo 未提供该应用的下载地址")
        val resolved = resolveVivoDownloadAsset(client, vivoHttpsUrl(rawUrl))
        val size = resolved.size
        return DownloadMeta(
            appId = snapshot.appId,
            packageName = snapshot.packageName,
            displayName = snapshot.displayName.ifBlank { app.displayName },
            versionName = snapshot.versionName.ifBlank { app.versionName },
            versionCode = snapshot.versionCode.takeIf { it > 0L } ?: app.versionCode,
            url = resolved.url,
            size = size,
            parts = listOf(DownloadPart("", "base", resolved.url, size, resolved.md5.lowercase())),
            icon = snapshot.icon.ifBlank { app.icon },
            source = AppSource.VIVO,
        )
    }

    /**
     * vivo keeps some built-in packages out of keyword search and packageList, but its native
     * detail endpoint resolves them by package name. A successful response is also the server-side
     * proof required before an installed-only match may be shown.
     */
    suspend fun packageSnapshot(packageName: String): VivoPackageSnapshot? {
        if (packageName.isBlank()) return null
        val profile = profileStore.load(AppSource.VIVO)
        return packageSnapshot(packageName, profile)
    }

    private suspend fun packageSnapshot(
        packageName: String,
        profile: MarketProfile,
    ): VivoPackageSnapshot? {
        val response = client.post("$API/port/package/") {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.UserAgent, vivoNativeUserAgent(profile))
            setBody(
                urlEncodeParameters(
                    mapOf(
                        "package_name" to packageName,
                        // The native endpoint omits name/version/icon without this flag.
                        "content_complete" to "1",
                    )
                )
            )
        }
        val text = responseText(response)
        return parseVivoPackageSnapshot(text, packageName)
    }

    /** Resolves the device-specific server catalogue through vivo's native package-name endpoint. */
    suspend fun packageSnapshots(packageNames: List<String>): List<VivoPackageSnapshot> {
        val names = packageNames.filter(String::isNotBlank).distinct()
        if (names.isEmpty()) return emptyList()
        val profile = profileStore.load(AppSource.VIVO)
        val slots = Semaphore(MAX_PACKAGE_SNAPSHOT_REQUESTS)
        return coroutineScope {
            names.map { packageName ->
                async {
                    slots.withPermit {
                        runCatching { packageSnapshot(packageName, profile) }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun packageListJson(vivoAppId: Long): JsonObject {
        val profile = profileStore.load(AppSource.VIVO)
        val response = client.post("$API/port/packageList/") {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.UserAgent, vivoNativeUserAgent(profile))
            setBody(
                urlEncodeParameters(
                    mapOf(
                        "idListStr" to vivoAppId.toString(),
                        "content_complete" to "1",
                        "needUpCmt" to "1",
                        "app_version" to profile.marketVersion.ifBlank { APP_VERSION },
                    )
                )
            )
        }
        val root = parseVivoObject(responseText(response))
        if (root.int("code", root.int("retcode", -1)) != 0) {
            throw MarketException("vivo 未收录该应用")
        }
        val item = root.obj("value")?.arr("appInfoList")?.objAt(0)
            ?: throw MarketException("vivo 未收录该应用")
        if (item.long("id") <= 0L) throw MarketException("vivo 未收录该应用")
        return item
    }

    private suspend fun responseText(response: HttpResponse): String {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("VivoApi") { "HTTP ${response.status.value} ${response.request.url.encodedPath}: ${text.take(200)}" }
            throw MarketException("vivo 服务器返回异常状态 HTTP ${response.status.value}")
        }
        return text
    }

}

internal fun vivoNativeUserAgent(profile: MarketProfile): String =
    "Dalvik/2.1.0 (Linux; U; Android ${profile.androidVersion}; ${profile.model} Build/${profile.buildId})"

private const val MAX_PACKAGE_SNAPSHOT_REQUESTS = 4

private val vivoPackageAvailable = Regex("<Package\\s+[^>]*info=\\\"1\\\"", RegexOption.IGNORE_CASE)
private val vivoPackageId = Regex("[?&]id=(\\d+)")
private val vivoScreenshot = Regex(
    "<screenshot>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</screenshot>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

internal fun parseVivoPackageSnapshot(xml: String, packageName: String): VivoPackageSnapshot? {
    if (!vivoPackageAvailable.containsMatchIn(xml)) return null
    fun tag(name: String): String = Regex(
        "<$name>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</$name>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    val downloadUrl = vivoHttpsUrl(tag("download_url"))
    return VivoPackageSnapshot(
        appId = tag("id").toLongOrNull()
            ?: vivoPackageId.find(downloadUrl)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: 0L,
        packageName = tag("package_name").ifBlank { packageName },
        displayName = tag("title_zh").ifBlank { packageName },
        publisherName = tag("developer"),
        versionName = tag("version_name"),
        versionCode = tag("version_code").toLongOrNull() ?: 0L,
        icon = vivoHttpsUrl(tag("icon_url")),
        downloadUrl = downloadUrl,
        sizeKiB = tag("size").toLongOrNull() ?: 0L,
        score = tag("score").toDoubleOrNull() ?: 0.0,
        introduction = vivoPlainText(tag("introduction")),
        screenshots = vivoScreenshot.findAll(xml)
            .map { vivoHttpsUrl(it.groupValues[1].trim()) }
            .filter(String::isNotBlank)
            .toList(),
        downloadCount = tag("download_count").toLongOrNull() ?: 0L,
        commentCount = tag("raters_count").toLongOrNull() ?: 0L,
        uploadTime = vivoUploadTimeMillis(tag("upload_time")),
        isAd = tag("ad").toIntOrNull()?.let { it in 1..2 } == true,
    )
}

private fun vivoSearchParams(profile: MarketProfile, keyword: String, page: Int): Map<String, String> = linkedMapOf(
    "key" to keyword,
    "id" to "0",
    "page_index" to (page + 1).toString(),
    "apps_per_page" to PAGE_SIZE.toString(),
    "target" to "local",
    "cfrom" to "2",
    "app_version" to profile.marketVersion.ifBlank { APP_VERSION },
    "model" to profile.model,
    "deviceType" to profile.device,
    "av" to profile.sdk,
    "an" to profile.androidVersion,
    "mfr" to "vivo",
    "density" to profile.densityScaleFactor,
    "screensize" to profile.resolution.replace('*', '_'),
    "pictype" to "webp",
    "cs" to "0",
)

internal fun parseVivoSearch(json: JsonObject, page: Int): SearchPage {
    val code = json.long("code", json.long("retcode", 0L))
    if (code != 0L) {
        debugLog("VivoApi") { "search rejected: code=$code" }
        return SearchPage(emptyList(), hasMore = false)
    }
    val response = json.obj("data")?.obj("appSearchResponse") ?: json
    val list = response.arr("value") ?: return SearchPage(emptyList(), hasMore = false)
    val items = (0 until list.size).mapNotNull { index -> list.objAt(index)?.let(::parseVivoApp) }
    // page_index 是 1-based
    return SearchPage(items, hasMore = page + 1 < response.int("maxPage"))
}

/** 搜索项与详情字段名相同，两处共用。 */
internal fun parseVivoApp(o: JsonObject): MarketAppInfo? {
    val pkg = o.str("package_name")
    if (pkg.isBlank()) return null
    return MarketAppInfo(
        appId = o.long("id"),
        packageName = pkg,
        displayName = o.str("title_zh", pkg),
        publisherName = o.str("developer"),
        versionName = o.str("version_name"),
        versionCode = o.long("version_code"),
        icon = vivoHttpsUrl(o.str("icon_url")),
        apkSize = o.long("size").kibToBytes(),
        ratingScore = o.double("score"),
        isAd = o.int("ad") in 1..2,
        downloadBlockReason = if (o.long("problemLevel") >= 1L) {
            o.str("problemSearchTips").ifBlank { "vivo 暂不提供该应用的下载" }
        } else {
            ""
        },
        source = AppSource.VIVO,
    )
}

internal fun parseVivoScreenshots(json: JsonObject): List<AppScreenshot> {
    val list = json.arr("screenshotList") ?: return emptyList()
    val zoom = json.arr("zoomScreenshotList")
    // screenshot_type: 0 竖屏、1 横屏
    val orientation = if (json.int("screenshot_type") == 1) {
        ScreenshotOrientation.LANDSCAPE
    } else {
        ScreenshotOrientation.PORTRAIT
    }
    return (0 until list.size).mapNotNull { index ->
        val url = vivoAssetUrl(list.strAt(index)).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val expanded = vivoAssetUrl(zoom.strAt(index)).takeIf { it.isNotBlank() } ?: url
        AppScreenshot(url = url, orientation = orientation, expandedUrl = expanded)
    }
}

internal fun vivoAssetUrl(path: String): String = when {
    path.isBlank() -> ""
    path.startsWith("http", ignoreCase = true) -> vivoHttpsUrl(path)
    path.startsWith("/") -> ASSET_BASE + path
    else -> "$ASSET_BASE/$path"
}

internal fun vivoHttpsUrl(url: String): String = when {
    url.startsWith("http://img.wsdl.vivo.com.cn", ignoreCase = true) ->
        "https://imgwsdl.vivo.com.cn${url.substring("http://img.wsdl.vivo.com.cn".length)}"

    url.startsWith("http://", ignoreCase = true) -> "https://${url.substring(7)}"
    else -> url
}

internal fun vivoPlainText(html: String): String =
    html.replace(htmlBr, "\n")
        .replace(htmlTag, "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .trim()

internal fun vivoUploadTimeMillis(uploadTime: String): Long {
    if (uploadTime.isBlank()) return 0L
    return runCatching {
        LocalDateTime.parse(uploadTime.trim().replace('/', '-').replace(' ', 'T'))
            .toInstant(VIVO_ZONE)
            .toEpochMilliseconds()
    }.getOrDefault(0L)
}

