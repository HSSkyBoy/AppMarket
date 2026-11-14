package top.app.market.data.remote.tencent

import top.app.market.domain.exception.MarketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TencentParserTest {
    @Test
    fun detailMapsFieldsAndUpgradesUrlsToHttps() {
        val detail = assertNotNull(parseDetail("demo.app", detailBody()))
        assertEquals(120L, detail.info.versionCode)
        assertEquals("1.2.0", detail.info.versionName)
        assertEquals(4.3, detail.info.ratingScore)
        assertEquals("https://dl.example.com/demo.apk", detail.downloadUrl)
        assertEquals("https://img.example.com/icon", detail.info.icon)
        assertEquals(listOf("https://img.example.com/s1"), detail.screenshots)
        assertEquals("a".repeat(64), detail.sha256)
        assertTrue(detail.downloadable)
    }

    @Test
    fun detailWithoutDownloadUrlIsNotDownloadable() {
        val detail = assertNotNull(parseDetail("demo.app", detailBody(url = "")))
        assertFalse(detail.downloadable)
        assertTrue(detail.info.downloadBlockReason.isNotBlank())
    }

    @Test
    fun detailReturnsNullForUnlistedPackage() {
        assertNull(parseDetail("other.app", detailBody()))
    }

    @Test
    fun detailSurfacesServerError() {
        val error = assertFailsWith<MarketException> { parseDetail("demo.app", """{"ret":1,"err_msg":"boom"}""") }
        assertEquals("boom", error.message)
    }

    @Test
    fun searchSkipsCloudGamesBookingsAndDuplicates() {
        val items = parseSearchPage(searchPage())
        assertEquals(listOf("demo.app"), items.map { it.packageName })
        assertEquals("https://img.example.com/icon", items.single().icon)
    }

    @Test
    fun searchRejectsUnexpectedPage() {
        assertFailsWith<MarketException> { parseSearchPage("""<script id="__NEXT_DATA__">{"page":"/"}</script>""") }
    }

    private fun detailBody(url: String = "http://dl.example.com/demo.apk") = """
        {"ret":0,"err_msg":"","app_detail_records":{"demo.app":{
          "app_info":{"name":"Demo","developer":"Dev","online_status":1,"download_cnt_total":"42"},
          "apk_all_data":{"package_name":"demo.app","app_id":"7","name":"Demo","version_code":120,
            "version_name":"1.2.0","size_byte":"1000","url":"$url","sha256":"${"A".repeat(64)}",
            "logo256":"http://img.example.com/icon","snapshot_bigs":["http://img.example.com/s1"],
            "update_time":100},
          "app_rating_info":{"average_rating":4.3,"rating_count":"9"}}}}
    """.trimIndent()

    private fun searchPage(): String {
        fun item(pkg: String, type: String = "0", booking: String = "0") =
            """{"pkg_name":"$pkg","app_id":"1","name":"N","icon":"http://img.example.com/icon",""" +
                """"game_type":"$type","is_booking":"$booking","apk_size":"10"}"""
        val items = listOf(item("demo.app"), item("DEMO.APP"), item("cloud.game", type = "7"), item("soon.game", booking = "1"))
        return """<html><script id="__NEXT_DATA__" type="application/json">{"page":"/search","props":{"pageProps":""" +
            """{"dynamicCardResponse":{"ret":0,"data":{"components":[{"cardId":"YYB_HOME_SEARCH_NORMAL_GAME",""" +
            """"data":{"itemData":[${items.joinToString(",")}]}}]}}}}}</script></html>"""
    }
}
