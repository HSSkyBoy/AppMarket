package top.app.market.data.remote.tencent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo

private const val SearchCardId = "YYB_HOME_SEARCH_NORMAL_GAME"
private const val DetailPageUrl = "https://sj.qq.com/appdetail/"
private val CloudOrDesktopGameTypes = setOf("7", "8")
private val NextData = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
private val json = Json { ignoreUnknownKeys = true }

internal class TencentDetail(
    val info: MarketAppInfo,
    val sha256: String,
    val md5: String,
    val downloadUrl: String,
    val signerSha256: String,
    val brief: String,
    val description: String,
    val age: String,
    val registrationNum: String,
    val privacyUrl: String,
    val updateTimeMillis: Long,
    val ratingCount: Long,
    val screenshots: List<String>,
    val downloadable: Boolean,
)

internal fun parseSearchPage(html: String): List<MarketAppInfo> {
    val data = NextData.find(html)?.groupValues?.get(1)
        ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
    if (data?.text("page") != "/search") throw MarketException("应用宝搜索页结构已变化")

    val card = data.child("props")?.child("pageProps")?.child("dynamicCardResponse")
    if (card?.text("ret") != "0") throw MarketException("应用宝搜索失败")

    val items = card.child("data")?.list("components").orEmpty()
        .filterIsInstance<JsonObject>()
        .firstOrNull { it.text("cardId") == SearchCardId }
        ?.child("data")?.list("itemData").orEmpty()

    val seen = HashSet<String>()
    return items.filterIsInstance<JsonObject>()
        .mapNotNull(::searchResult)
        .filter { seen.add(it.packageName.lowercase()) }
}

/** 解析 apkinfo 响应；应用宝未收录该包名时返回 null。 */
internal fun parseDetail(packageName: String, body: String): TencentDetail? {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw MarketException("应用宝详情解析失败")
    if (root.text("ret") != "0") throw MarketException(root.text("err_msg").ifBlank { "应用宝详情失败" })

    val record = root.child("app_detail_records")?.child(packageName) ?: return null
    val apk = record.child("apk_all_data")?.takeIf { it.text("package_name") == packageName } ?: return null
    val info = record.child("app_info")
    val rating = record.child("app_rating_info")

    val url = httpsOf(apk.text("url"))
    val downloadable = url.isNotBlank() && info?.text("online_status") != "0"
    val category = info?.text("category_name_new").orEmpty().ifBlank { info?.text("category_name").orEmpty() }

    val app = MarketAppInfo(
        appId = apk.text("app_id").toLongOrNull() ?: packageName.hashCode().toLong(),
        packageName = packageName,
        displayName = apk.text("name").ifBlank { info?.text("name").orEmpty() }.ifBlank { packageName },
        publisherName = info?.text("developer").orEmpty().ifBlank { info?.text("author").orEmpty() },
        versionName = apk.text("version_name"),
        versionCode = apk.text("version_code").toLongOrNull() ?: 0L,
        icon = httpsOf(apk.text("logo256").ifBlank { apk.text("logo_big") }),
        apkSize = apk.text("size_byte").toLongOrNull() ?: 0L,
        ratingScore = rating.score("average_rating"),
        changeLog = apk.text("feature").trim(),
        openLink = DetailPageUrl + packageName,
        source = AppSource.TENCENT,
        category = category,
        downloadCount = info?.text("download_cnt_total")?.toLongOrNull() ?: 0L,
        downloadBlockReason = if (downloadable) "" else "应用宝暂不提供下载",
    )
    return TencentDetail(
        info = app,
        sha256 = apk.text("sha256").lowercase(),
        md5 = apk.text("apk_md5").lowercase(),
        downloadUrl = url,
        signerSha256 = apk.text("signatureSha256").lowercase(),
        brief = info?.text("editor_intro").orEmpty(),
        description = info?.text("desc").orEmpty(),
        age = info?.text("suitable_age").orEmpty(),
        registrationNum = info?.text("icp_number").orEmpty(),
        privacyUrl = info?.text("privacy_agreement").orEmpty(),
        updateTimeMillis = (apk.text("update_time").toLongOrNull() ?: 0L) * 1000L,
        ratingCount = rating?.text("rating_count")?.toLongOrNull() ?: 0L,
        screenshots = apk.list("snapshot_bigs").orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map(::httpsOf),
        downloadable = downloadable,
    )
}

private fun searchResult(item: JsonObject): MarketAppInfo? {
    val pkg = item.text("pkg_name").ifBlank { return null }
    if (item.text("game_type") in CloudOrDesktopGameTypes || item.text("is_booking") == "1") return null
    return MarketAppInfo(
        appId = item.text("app_id").toLongOrNull() ?: pkg.hashCode().toLong(),
        packageName = pkg,
        displayName = item.text("name").ifBlank { pkg },
        publisherName = item.text("developer"),
        versionName = item.text("version_name"),
        versionCode = 0L,
        icon = httpsOf(item.text("icon")),
        apkSize = item.text("apk_size").toLongOrNull() ?: 0L,
        ratingScore = item.score("average_rating"),
        openLink = DetailPageUrl + pkg,
        source = AppSource.TENCENT,
        category = item.text("cate_name_new"),
    )
}

private fun httpsOf(url: String): String = if (url.startsWith("http://")) "https://" + url.substring(7) else url

private fun JsonObject?.score(key: String): Double =
    this?.text(key)?.toDoubleOrNull()?.coerceIn(0.0, 5.0) ?: 0.0

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.list(key: String): JsonArray? = this[key] as? JsonArray
