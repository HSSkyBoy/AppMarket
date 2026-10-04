package top.app.market.data.remote.vivo

import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.ScreenshotOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VivoApiParseTest {

    @Test
    fun appFieldsMapToMarketAppInfoWithSizeInBytes() {
        val app = parseVivoApp(
            parseVivoObject(
                """
                {
                  "id": 40413,
                  "title_zh": "微信",
                  "package_name": "com.tencent.mm",
                  "developer": "腾讯科技（北京）有限公司",
                  "version_name": "8.0.76",
                  "version_code": 3141,
                  "size": 259865,
                  "score": 3.99,
                  "icon_url": "https://appstoreimg-ipv6.vivo.com.cn/appstore/developer/icon/a.png"
                }
                """.trimIndent()
            )
        )

        requireNotNull(app)
        assertEquals(40413L, app.appId)
        assertEquals("com.tencent.mm", app.packageName)
        assertEquals("微信", app.displayName)
        assertEquals(3141L, app.versionCode)
        // 服务端给 KB，apkSize 用字节
        assertEquals(259865L * 1024, app.apkSize)
        assertEquals(AppSource.VIVO, app.source)
    }

    @Test
    fun appWithoutPackageNameIsDropped() {
        assertNull(parseVivoApp(parseVivoObject("""{"id": 1, "title_zh": "x"}""")))
    }

    @Test
    fun nativeSearchUsesVivoAdLabelField() {
        val promoted = parseVivoApp(
            parseVivoObject(
                """{"id":1,"package_name":"a.b","version_code":2,"ad":1}"""
            )
        )
        val organic = parseVivoApp(
            parseVivoObject(
                """{"id":2,"package_name":"c.d","version_code":3,"ad":0}"""
            )
        )
        assertEquals(true, promoted?.isAd)
        assertEquals(false, organic?.isAd)
    }

    @Test
    fun searchReportsMorePagesUntilMaxPage() {
        val json = parseVivoObject(
            """
            {
              "result": true,
              "pageIndex": 0,
              "maxPage": 3,
              "value": [{"id": 1, "package_name": "a.b", "version_code": 2}]
            }
            """.trimIndent()
        )

        // page 0-based，发出去的 page_index 是 page + 1
        assertTrue(parseVivoSearch(json, page = 0).hasMore)
        assertTrue(parseVivoSearch(json, page = 1).hasMore)
        assertEquals(false, parseVivoSearch(json, page = 2).hasMore)
    }

    @Test
    fun nonZeroCodeYieldsEmptyPage() {
        val page = parseVivoSearch(parseVivoObject("""{"code": 20001, "msg": "rejected"}"""), page = 0)

        assertEquals(emptyList(), page.items)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun nativeHiddenPackageRequiresServerRecordAndParsesPackageId() {
        val snapshot = parseVivoPackageSnapshot(
            """
            <?xml version="1.0" encoding="utf-8" ?>
            <Package info="1" comment="Y">
              <id><![CDATA[3446198]]></id>
              <package_name><![CDATA[com.vivo.gallery]]></package_name>
              <title_zh><![CDATA[相册]]></title_zh>
              <developer><![CDATA[维沃移动通信有限公司]]></developer>
              <version_name><![CDATA[8.5.6.0]]></version_name>
              <version_code><![CDATA[8050600]]></version_code>
              <icon_url><![CDATA[http://img.wsdl.vivo.com.cn/appstore/icon.png]]></icon_url>
              <download_url><![CDATA[http://appstore.vivo.com.cn/appinfo/downloadApkFile?id=3446198&app_version=100.0]]></download_url>
              <upload_time><![CDATA[2022-07-15 11:20:03]]></upload_time>
              <size><![CDATA[164988]]></size>
              <score><![CDATA[4.5]]></score>
              <introduction><![CDATA[第一行<br>第二行]]></introduction>
              <ScreenshotList><screenshot><![CDATA[http://img.wsdl.vivo.com.cn/appstore/a.png]]></screenshot></ScreenshotList>
              <raters_count><![CDATA[3]]></raters_count>
              <download_count><![CDATA[20]]></download_count>
            </Package>
            """.trimIndent(),
            packageName = "vendor.example.gallery",
        )

        requireNotNull(snapshot)
        assertEquals(3446198L, snapshot.appId)
        assertEquals("com.vivo.gallery", snapshot.packageName)
        assertEquals("相册", snapshot.displayName)
        assertEquals("8.5.6.0", snapshot.versionName)
        assertEquals(8050600L, snapshot.versionCode)
        assertEquals("第一行\n第二行", snapshot.introduction)
        assertEquals(164988L, snapshot.sizeKiB)
        assertEquals("https://imgwsdl.vivo.com.cn/appstore/a.png", snapshot.screenshots.single())
        assertNull(parseVivoPackageSnapshot("<Package info=\"0\"></Package>", "local.only"))
    }

    @Test
    fun screenshotsResolveRelativePathsAndFallBackToPlainUrl() {
        val screenshots = parseVivoScreenshots(
            parseVivoObject(
                """
                {
                  "screenshot_type": 1,
                  "screenshotList": ["/developer/screenshot/a.png", "https://cdn.example/b.png"],
                  "zoomScreenshotList": ["/developer/zoom/a.png"]
                }
                """.trimIndent()
            )
        )

        assertEquals(2, screenshots.size)
        assertEquals("https://appstoreimg-ipv6.vivo.com.cn/appstore/developer/screenshot/a.png", screenshots[0].url)
        assertEquals("https://appstoreimg-ipv6.vivo.com.cn/appstore/developer/zoom/a.png", screenshots[0].expandedUrl)
        assertEquals(ScreenshotOrientation.LANDSCAPE, screenshots[0].orientation)
        // 没有 zoom 项时退回原图
        assertEquals("https://cdn.example/b.png", screenshots[1].url)
        assertEquals("https://cdn.example/b.png", screenshots[1].expandedUrl)
    }

    @Test
    fun uploadTimeIsReadAsBeijingTime() {
        // 2026-07-12 17:58:02 +08:00 == 2026-07-12 09:58:02 UTC
        assertEquals(1783850282000L, vivoUploadTimeMillis("2026-07-12 17:58:02"))
        assertEquals(1783850282000L, vivoUploadTimeMillis("2026/07/12 17:58:02"))
        assertEquals(0L, vivoUploadTimeMillis(""))
        assertEquals(0L, vivoUploadTimeMillis("not a date"))
    }

    @Test
    fun introductionHtmlBecomesPlainText() {
        assertEquals(
            "第一行\n第二行\nA & B",
            vivoPlainText("第一行<br>第二行<br/><b>A &amp; B</b>"),
        )
    }

    @Test
    fun assetUrlsAreAlwaysHttps() {
        assertEquals(
            "https://img.example/a.apk",
            vivoAssetUrl("http://img.example/a.apk"),
        )
        assertEquals(
            "https://imgwsdl.vivo.com.cn/appstore/image/a.jpg",
            vivoHttpsUrl("http://img.wsdl.vivo.com.cn/appstore/image/a.jpg"),
        )
    }

    @Test
    fun problemLevelMarksAppDownloadBlocked() {
        val blocked = parseVivoApp(
            parseVivoObject(
                """{"id":1,"package_name":"a.b","version_code":2,"problemLevel":2,"problemSearchTips":"该游戏资源准备中，敬请期待"}"""
            )
        )
        val normal = parseVivoApp(
            parseVivoObject(
                """{"id":2,"package_name":"c.d","version_code":3,"problemLevel":0}"""
            )
        )
        assertEquals("该游戏资源准备中，敬请期待", blocked?.downloadBlockReason)
        assertEquals("", normal?.downloadBlockReason)
    }
}
