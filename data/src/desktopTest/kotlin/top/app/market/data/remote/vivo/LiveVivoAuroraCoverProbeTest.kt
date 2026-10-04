package top.app.market.data.remote.vivo

import top.app.market.di.dataModules
import top.app.market.domain.model.today.TodayArticleBlock
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test

/** 临时探测：打印 vivo 极光奖封面实际宽高比。环境变量开关，不进入常规测试。 */
class LiveVivoAuroraCoverProbeTest {
    @Test
    fun printCoverAspectRatios() = runBlocking {
        if (System.getenv(ENABLE_ENV) != "1") return@runBlocking
        val koin = startKoin { modules(dataModules) }.koin
        try {
            val api = koin.get<VivoAuroraApi>()
            val client = koin.get<HttpClient>()
            val page = api.feed(page = 0, pageSize = 5)
            println("[vivo-aurora] feed items=${page.items.size} hasMore=${page.hasMore}")
            page.items.forEach { item ->
                probe(client, "feed:${item.title}", item.coverImage)
            }
            page.items.firstOrNull()?.let { first ->
                val article = runCatching { api.article(first.rId) }.getOrNull()
                article?.let {
                    probe(client, "article-header:${it.title}", it.headerImage)
                    it.blocks.filterIsInstance<TodayArticleBlock.Image>().forEach { block ->
                        probe(client, "article-image", block.imageUrl)
                    }
                }
            }
        } finally {
            stopKoin()
        }
    }

    private suspend fun probe(client: HttpClient, label: String, url: String) {
        if (url.isBlank()) {
            println("[vivo-aurora] $label: <blank>")
            return
        }
        println("[vivo-aurora] $label url=$url")
        val bytes = runCatching { client.get(url).bodyAsBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            println("[vivo-aurora] $label: <download failed>")
            return
        }
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        if (image == null) {
            println("[vivo-aurora] $label: <decode failed, bytes=${bytes.size}>")
            return
        }
        val ratio = (image.width.toDouble() / image.height * 100).roundToInt() / 100.0
        println(
            "[vivo-aurora] $label size=${image.width}x${image.height} ratio=$ratio bytes=${bytes.size}",
        )
    }

    private companion object {
        const val ENABLE_ENV = "APPMARKET_LIVE_VIVO_AURORA_COVER"
    }
}
