package com.app.market.data.remote.oppo

import com.app.market.di.dataModules
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LiveOppoBeautyTest {
    @Test
    fun fetchBeautyFeedAndFirstArticle() = runBlocking {
        if (System.getenv(ENABLE_ENV) != "1") return@runBlocking
        val koin = startKoin { modules(dataModules) }.koin
        try {
            val api = koin.get<OppoApi>()
            val first = api.beautyFeed(page = 0, pageSize = 10)
            val second = api.beautyFeed(page = 1, pageSize = 10)
            val item = first.items.first()
            val article = api.beautyArticle(item.rId)
            val feedApp = requireNotNull(item.app)
            val articleApp = article.apps.first()
            val detail = api.appDetail(feedApp.appId, feedApp.packageName, null)

            assertTrue(first.items.isNotEmpty())
            assertEquals(first.items.size, first.items.map { it.rId }.distinct().size)
            assertTrue(first.items.all { it.app?.packageName?.isNotBlank() == true })
            assertTrue(feedApp.category.isNotBlank())
            assertTrue(feedApp.downloadCount > 0L)
            assertNotEquals(first.items.first().rId, second.items.firstOrNull()?.rId)
            assertTrue(article.blocks.isNotEmpty())
            assertTrue(article.apps.isNotEmpty())
            assertTrue(articleApp.category.isNotBlank())
            assertTrue(articleApp.downloadCount > 0L)
            assertEquals("至美奖", article.awardName)
            assertEquals(feedApp.packageName, detail.app.packageName)
            assertTrue(detail.category.isNotBlank())
            assertTrue(detail.downloadCount > 0L)
            println(
                "[oppo-beauty] first=${first.items.size}/${first.hasMore} " +
                        "second=${second.items.size}/${second.hasMore} " +
                        "article=${article.title} award=${article.awardName} " +
                        "blocks=${article.blocks.size} apps=${article.apps.size} " +
                        "feed=${feedApp.category}/${feedApp.downloadCount} " +
                        "articleApp=${articleApp.category}/${articleApp.downloadCount} " +
                        "detail=${detail.category}/${detail.downloadCount}",
            )
        } finally {
            stopKoin()
        }
    }

    private companion object {
        const val ENABLE_ENV = "APPMARKET_LIVE_OPPO_BEAUTY"
    }
}
