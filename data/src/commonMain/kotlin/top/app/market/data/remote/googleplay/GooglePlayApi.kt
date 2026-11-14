package top.app.market.data.remote.googleplay

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import top.app.market.data.platform.gunzip
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val Host = "https://android.clients.google.com/fdfe"
private const val ApiHost = "https://play.googleapis.com/fdfe"
private const val SearchPageSize = 20
private const val BulkBatchSize = 40
private const val DeliveryOk = 1L
private const val ImageTypeScreenshot = 1L
private const val ImageTypeIcon = 4L
private val PackagePattern = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
private val HtmlTag = Regex("<[^>]+>")

/** 从 Play 响应里提取出的应用文档。 */
internal data class GooglePlayDoc(
    val packageName: String,
    val title: String,
    val developer: String,
    val description: String,
    val versionCode: Long,
    val versionName: String,
    val icon: String,
    val screenshots: List<String>,
    val size: Long,
    val rating: Double,
    val downloadCount: Long,
    val changeLog: String,
)

internal class GooglePlayDelivery(
    val baseUrl: String,
    val baseSize: Long,
    val sha256: String,
    val splits: List<Split>,
    val cookies: List<Pair<String, String>>,
) {
    class Split(val name: String, val url: String, val size: Long)
}

/**
 * Google Play（匿名会话）数据层：搜索、详情、批量查版本、下载地址。
 * 协议字段编号由实现者按 Play 公开的 protobuf 定义编写，尚未经真实服务验证。
 */
internal class GooglePlayApi(
    private val client: HttpClient,
    private val sessions: GooglePlaySessionProvider,
    private val config: GooglePlayConfig,
) {
    suspend fun search(keyword: String, page: Int): SearchPage {
        val query = keyword.trim()
        if (query.isEmpty()) return SearchPage(emptyList(), hasMore = false)
        val root = fdfe("$Host/search") {
            parameter("c", 3)
            parameter("q", query)
            parameter("n", SearchPageSize)
            parameter("o", page * SearchPageSize)
        }
        val docs = LinkedHashMap<String, GooglePlayDoc>()
        collectDocs(root, docs)
        if (docs.isEmpty()) return SearchPage(emptyList(), hasMore = false)

        // 搜索结果里的图标常缺失，用批量详情补全
        val detailed = runCatching { bulkDetails(docs.keys.toList()) }.getOrDefault(emptyList())
            .associateBy { it.packageName }
        val items = docs.values.map { (detailed[it.packageName] ?: it).toAppInfo() }
        return SearchPage(items = items, hasMore = docs.size >= SearchPageSize)
    }

    suspend fun appDetail(packageName: String): AppDetail {
        val pkg = packageName.trim()
        val root = fdfe("$Host/details") { parameter("doc", pkg) }
        val doc = root.msg(2)?.msg(4)?.let(::toDoc)
            ?: throw MarketException("Google Play 未找到该应用：$pkg")
        val description = cleanHtml(doc.description)
        return AppDetail(
            app = doc.toAppInfo(),
            brief = description.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
            introduction = description,
            changeLog = doc.changeLog,
            category = "",
            ageClassification = "",
            downloadCount = doc.downloadCount,
            registrationNum = "",
            privacyUrl = "",
            screenshots = doc.screenshots.map { AppScreenshot(url = it, orientation = ScreenshotOrientation.PORTRAIT) },
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val versionCode = app.versionCode.takeIf { it > 0 }
            ?: bulkDetails(listOf(app.packageName)).firstOrNull()?.versionCode
            ?: throw MarketException("Google Play 未找到该应用：${app.packageName}")
        val delivery = delivery(app.packageName, versionCode)

        val headers = delivery.cookies.takeIf { it.isNotEmpty() }
            ?.let { mapOf(HttpHeaders.Cookie to it.joinToString("; ") { (name, value) -> "$name=$value" }) }
            .orEmpty()
        val parts = buildList {
            add(DownloadPart("base", "base", delivery.baseUrl, delivery.baseSize, delivery.sha256))
            delivery.splits.forEach { add(DownloadPart(it.name, "split", it.url, it.size)) }
        }
        return DownloadMeta(
            appId = app.appId,
            packageName = app.packageName,
            displayName = app.displayName,
            versionName = app.versionName,
            versionCode = versionCode,
            url = delivery.baseUrl,
            size = parts.sumOf(DownloadPart::size),
            parts = parts,
            icon = app.icon,
            changeLog = app.changeLog,
            requestHeaders = headers,
            source = AppSource.GOOGLE_PLAY,
        )
    }

    /** 已装应用里 Play 上版本号更高的，按批并行查询。 */
    suspend fun checkUpdates(installed: List<InstalledPackage>): List<MarketAppInfo> {
        if (installed.isEmpty()) return emptyList()
        val localByPackage = installed.associateBy(InstalledPackage::packageName)
        val remote = coroutineScope {
            localByPackage.keys.chunked(BulkBatchSize)
                .map { batch -> async { runCatching { bulkDetails(batch) }.getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
        }
        return remote.mapNotNull { doc ->
            val local = localByPackage[doc.packageName] ?: return@mapNotNull null
            if (doc.versionCode <= local.versionCode) return@mapNotNull null
            doc.toAppInfo()
        }
    }

    private suspend fun bulkDetails(packages: List<String>): List<GooglePlayDoc> {
        if (packages.isEmpty()) return emptyList()
        val body = PbWriter().apply {
            packages.forEach { string(1, it) }
            bool(3, true)
        }.toByteArray()
        val root = fdfe("$ApiHost/bulkDetails", body)
        val response = root.msg(1)?.msg(19) ?: return emptyList()
        return response.msgs(1).mapNotNull { entry -> entry.msg(1)?.let(::toDoc) }
    }

    private suspend fun delivery(packageName: String, versionCode: Long): GooglePlayDelivery {
        val root = fdfe("$ApiHost/delivery") {
            parameter("doc", packageName)
            parameter("vc", versionCode)
            parameter("ot", 1)
        }
        val response = root.msg(1)?.msg(21) ?: throw MarketException("Google Play 未返回下载信息")
        val status = response.long(1)
        if (status != DeliveryOk) throw MarketException("Google Play 未提供下载（status=$status）")
        val data = response.msg(2) ?: throw MarketException("Google Play 未返回下载信息")
        val baseUrl = data.str(3).ifBlank { throw MarketException("Google Play 未返回下载地址") }
        return GooglePlayDelivery(
            baseUrl = baseUrl,
            baseSize = data.long(1),
            sha256 = toHex(data.str(19)),
            splits = data.msgs(15).mapNotNull { split ->
                val url = split.str(4).ifBlank { split.str(5) }
                if (url.isBlank()) null else GooglePlayDelivery.Split(split.str(1), url, split.long(2))
            },
            cookies = data.msgs(5).map { it.str(1) to it.str(2) }.filter { it.first.isNotBlank() },
        )
    }

    /** 请求 fdfe 接口并解出 ResponseWrapper；令牌失效（401）时刷新会话重试一次。 */
    private suspend fun fdfe(
        url: String,
        body: ByteArray? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): PbMessage {
        var response = send(url, body, sessions.get(), configure)
        if (response.status == HttpStatusCode.Unauthorized) {
            response = send(url, body, sessions.get(forceRefresh = true), configure)
        }
        if (!response.status.isSuccess()) throw MarketException("Google Play 请求失败（HTTP ${response.status.value}）")
        var bytes = response.readRawBytes()
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) bytes = gunzip(bytes)
        val root = PbMessage.tryParse(bytes) ?: throw MarketException("Google Play 返回了无法解析的响应")
        if (root.msg(1) == null) {
            val message = root.msg(2)?.str(2).orEmpty()
            throw MarketException(message.ifBlank { "Google Play 返回了空响应" })
        }
        return root
    }

    private suspend fun send(
        url: String,
        body: ByteArray?,
        session: GooglePlaySession,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        val build: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, "Bearer ${session.authToken}")
            header(HttpHeaders.UserAgent, userAgent())
            header(HttpHeaders.AcceptLanguage, config.language)
            header("X-DFE-Device-Id", session.gsfId)
            header("X-DFE-Client-Id", "am-android-google")
            header("X-DFE-Network-Type", "4")
            header("X-DFE-Request-Params", "timeoutMs=4000")
            header("X-DFE-MCCMNC", GooglePlayDevice.Mccmnc)
            header("X-DFE-UserLanguages", config.language.replace('-', '_'))
            if (session.dfeCookie.isNotBlank()) header("X-DFE-Cookie", session.dfeCookie)
            if (session.deviceConfigToken.isNotBlank()) header("X-DFE-Device-Config-Token", session.deviceConfigToken)
            if (session.checkInConsistencyToken.isNotBlank()) {
                header("X-DFE-Device-Checkin-Consistency-Token", session.checkInConsistencyToken)
            }
            configure()
        }
        return if (body == null) {
            client.get(url, build)
        } else {
            client.post(url) {
                build()
                header(HttpHeaders.ContentType, "application/x-protobuf")
                setBody(body)
            }
        }
    }

    private fun userAgent(): String =
        "Android-Finsky/${GooglePlayDevice.VendingVersionString} " +
            "(api=3,versionCode=${GooglePlayDevice.VendingVersionCode},sdk=${GooglePlayDevice.Sdk}," +
            "isWideScreen=0,supportedAbis=${GooglePlayDevice.Abi})"

    /** 递归遍历结构未知的响应，收集所有应用文档（按包名去重，保持出现顺序）。 */
    private fun collectDocs(node: PbMessage, out: MutableMap<String, GooglePlayDoc>) {
        for (child in node.children()) {
            val doc = if (isAppDoc(child)) toDoc(child) else null
            if (doc != null) {
                out.putIfAbsent(doc.packageName, doc)
            } else {
                collectDocs(child, out)
            }
        }
    }

    private fun isAppDoc(message: PbMessage): Boolean =
        PackagePattern.matches(message.str(1)) && message.str(5).isNotBlank() && message.msg(13)?.msg(1) != null

    private fun toDoc(doc: PbMessage): GooglePlayDoc? {
        val app = doc.msg(13)?.msg(1)
        val packageName = doc.str(1).ifBlank { app?.str(14).orEmpty() }
        if (packageName.isBlank()) return null
        val images = doc.msgs(10)
        val rating = doc.msg(14)?.float(2)?.toDouble() ?: 0.0
        return GooglePlayDoc(
            packageName = packageName,
            title = doc.str(5),
            developer = doc.str(6).ifBlank { app?.str(1).orEmpty() },
            description = doc.str(7),
            versionCode = app?.long(3) ?: 0L,
            versionName = app?.str(4).orEmpty(),
            icon = images.firstOrNull { it.long(1) == ImageTypeIcon }?.str(5).orEmpty(),
            screenshots = images.filter { it.long(1) == ImageTypeScreenshot }.map { it.str(5) }.filter(String::isNotBlank),
            size = app?.msgs(17)?.firstOrNull()?.long(3) ?: 0L,
            rating = rating,
            downloadCount = app?.str(13)?.filter(Char::isDigit)?.toLongOrNull() ?: 0L,
            changeLog = cleanHtml(app?.str(15).orEmpty()),
        )
    }

    private fun GooglePlayDoc.toAppInfo() = MarketAppInfo(
        appId = packageName.hashCode().toLong(),
        packageName = packageName,
        displayName = title.ifBlank { packageName },
        publisherName = developer,
        versionName = versionName,
        versionCode = versionCode,
        icon = icon,
        apkSize = size,
        ratingScore = rating.coerceIn(0.0, 5.0),
        changeLog = changeLog,
        openLink = "https://play.google.com/store/apps/details?id=$packageName",
        source = AppSource.GOOGLE_PLAY,
        downloadCount = downloadCount,
    )

    private fun cleanHtml(raw: String): String =
        raw.replace("<br>", "\n").replace("<br/>", "\n").replace("</li>", "\n").replace("<li>", "• ")
            .let { HtmlTag.replace(it, "") }
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .trim()

    /** Play 的校验值是 base64 编码；下载器按十六进制长度识别算法，所以统一转成十六进制。 */
    @OptIn(ExperimentalEncodingApi::class)
    private fun toHex(value: String): String {
        if (value.isBlank()) return ""
        val trimmed = value.trim()
        if (trimmed.length == 64 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return trimmed.lowercase()
        }
        val bytes = runCatching {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
                .decode(trimmed.replace('+', '-').replace('/', '_'))
        }.getOrNull() ?: return ""
        if (bytes.size != 32) return ""
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
