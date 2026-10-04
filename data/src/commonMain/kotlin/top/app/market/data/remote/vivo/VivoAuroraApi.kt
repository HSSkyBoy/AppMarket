package top.app.market.data.remote.vivo

import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayArticleBlock
import top.app.market.domain.model.today.TodayFeaturedItem
import top.app.market.domain.model.today.TodayFeedPage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal class VivoAuroraApi(private val client: HttpClient) {
    suspend fun feed(page: Int, pageSize: Int): TodayFeedPage {
        require(page >= 0) { "page must be non-negative" }
        require(pageSize > 0) { "pageSize must be positive" }
        val periods = parseVivoAuroraList(getJson("/aurora/list"))
        if (periods.isEmpty() || page * pageSize >= periods.size) return TodayFeedPage(emptyList(), false)

        val startId = if (page == 0) 0L else periods[page * pageSize].numberId
        val direction = if (page == 0) 0 else 2
        val root = getJson("/aurora/page?id=$startId&direction=$direction")
        val pagePeriods = parseVivoAuroraPage(root).take(pageSize)
        val items = pagePeriods.flatMap { period ->
            period.apps.map { entry ->
                val app = entry.app
                TodayFeaturedItem(
                    rId = "${period.numberId}:${app.appId}",
                    title = app.displayName,
                    summary = app.changeLog.ifBlank { app.displayName },
                    coverImage = entry.coverImage,
                    app = app,
                    apps = listOf(app),
                    awardName = "极光奖 · 第${period.numberId}期",
                )
            }
        }
        return TodayFeedPage(
            items = items,
            hasMore = (page + 1) * pageSize < periods.size,
        )
    }

    suspend fun article(rId: String): TodayArticle {
        val parts = rId.split(':')
        val numberId = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0L }
            ?: throw MarketException("vivo 极光奖期数无效")
        val appId = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
            ?: throw MarketException("vivo 极光奖应用 ID 无效")
        val root = getJson("/aurora/single?appId=$appId&flag=1")
        if (!root.boolLike("result")) throw MarketException("vivo 未找到该应用的极光奖内容")
        val value = root.obj("value") ?: throw MarketException("vivo 极光奖详情为空")
        val detail = value.arr("detail") ?: JsonArray(emptyList())
        val app = value.obj("data")?.obj("app")?.let(::parseVivoAuroraApp)
        val blocks = buildList {
            detail.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val title = item.str("detailTitle")
                val text = item.str("detailText")
                if (title.isNotBlank()) add(TodayArticleBlock.RichText("<h3>${escapeHtml(title)}</h3>"))
                if (text.isNotBlank()) add(TodayArticleBlock.RichText(escapeHtml(text).replace("\n", "<br>")))
                val image = vivoHttpsUrl(item.str("detailPic"))
                if (image.isNotBlank()) add(TodayArticleBlock.Image(image))
            }
            app?.let { add(TodayArticleBlock.App(it)) }
        }
        val richText = blocks.filterIsInstance<TodayArticleBlock.RichText>().joinToString("\n") { it.html }
        val header = value.obj("data")?.arr("backgroundPic")?.strAt(0).orEmpty().let(::vivoHttpsUrl)
        val title = app?.displayName?.ifBlank { value.str("numberName") } ?: value.str("numberName")
        return TodayArticle(
            rId = "$numberId:$appId",
            title = title,
            awardName = "极光奖 · 第${value.long("numberId", numberId)}期",
            headerImage = header,
            richTextHtml = richText,
            app = app,
            apps = app?.let(::listOf).orEmpty(),
            blocks = blocks,
        )
    }

    private suspend fun getJson(path: String): JsonObject {
        val response = client.get(AURORA_API + path)
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw MarketException("vivo 极光奖接口异常 HTTP ${response.status.value}")
        return parseVivoObject(text)
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {
        const val AURORA_API = "https://aurora.appstore.vivo.com.cn"
    }
}

internal data class VivoAuroraPeriod(
    val numberId: Long,
    val apps: List<VivoAuroraEntry>,
)

internal data class VivoAuroraEntry(
    val app: MarketAppInfo,
    val coverImage: String,
)

internal fun parseVivoAuroraList(json: JsonObject): List<VivoAuroraPeriod> {
    val value = json.arr("value") ?: return emptyList()
    return (0 until value.size).mapNotNull { index ->
        val item = value.objAt(index) ?: return@mapNotNull null
        val numberId = item.long("numberId")
        if (numberId <= 0L) return@mapNotNull null
        VivoAuroraPeriod(numberId, emptyList())
    }
}

internal fun parseVivoAuroraPage(json: JsonObject): List<VivoAuroraPeriod> {
    val value = json.obj("value") ?: return emptyList()
    val periods = value.arr("list") ?: return emptyList()
    return (0 until periods.size).mapNotNull { index ->
        val period = periods.objAt(index) ?: return@mapNotNull null
        val numberId = period.long("numberId")
        if (numberId <= 0L) return@mapNotNull null
        val data = period.arr("data") ?: JsonArray(emptyList())
        val entries = (0 until data.size).mapNotNull { dataIndex ->
            val item = data.objAt(dataIndex) ?: return@mapNotNull null
            val app = item.obj("app")?.let(::parseVivoAuroraApp) ?: return@mapNotNull null
            VivoAuroraEntry(
                app = app,
                coverImage = item.arr("backgroundPic")?.strAt(0).orEmpty().let(::vivoHttpsUrl),
            )
        }
        VivoAuroraPeriod(numberId, entries)
    }
}

internal fun parseVivoAuroraApp(json: JsonObject): MarketAppInfo? {
    val app = parseVivoApp(json) ?: return null
    return app.copy(
        displayName = json.str("detailTitleZh", app.displayName),
        icon = vivoHttpsUrl(app.icon),
        apkSize = json.long("size").let { if (it > 0L) it * 1024L else app.apkSize },
        changeLog = json.str("detailAppRemark", json.str("app_remark")),
        openLink = vivoHttpsUrl(json.str("download_url")),
        downloadCount = json.long("download_count"),
        source = AppSource.VIVO,
    )
}
