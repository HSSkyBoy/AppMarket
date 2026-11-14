package top.app.market.data.remote.tencent

import kotlinx.coroutines.runBlocking
import top.app.market.data.platform.createHttpClient
import top.app.market.domain.model.installed.InstalledPackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTencentTest {
    @Test
    fun searchesDetailsAndResolvesDownload() = runBlocking {
        if (System.getenv(LIVE_ENV) != "1") return@runBlocking
        val client = createHttpClient()
        try {
            val api = TencentApi(client)
            val page = api.search("微信", 0)
            val app = page.items.firstOrNull { it.packageName == "com.tencent.mm" }
            assertTrue(app != null, "search did not return com.tencent.mm")
            assertTrue(app.icon.startsWith("https://"), "icon was not https: ${app.icon}")

            val detail = api.appDetail("com.tencent.mm")
            assertTrue(detail.app.versionCode > 0, "versionCode missing")
            assertTrue(detail.screenshots.isNotEmpty(), "screenshots missing")

            val meta = api.downloadMeta(app)
            assertTrue(meta.url.startsWith("https://"), "download url was not https: ${meta.url}")
            assertEquals(64, meta.parts.single().hash.length, "expected sha256")
            assertTrue(meta.size > 0, "size missing")

            val installed = InstalledPackage(packageName = "com.tencent.mm", versionCode = 1L, isSystemApp = false)
            val updates = api.checkUpdates(listOf(installed))
            assertEquals(listOf("com.tencent.mm"), updates.map { it.packageName })
            assertTrue(api.checkUpdates(listOf(installed.copy(versionCode = Long.MAX_VALUE))).isEmpty())
        } finally {
            client.close()
        }
    }

    private companion object {
        const val LIVE_ENV = "APPMARKET_LIVE_TENCENT"
    }
}
