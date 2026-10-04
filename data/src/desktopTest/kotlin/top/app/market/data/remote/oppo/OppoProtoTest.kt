package top.app.market.data.remote.oppo

import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.today.TodayArticleBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OppoProtoTest {
    @Test
    fun recognizesChinaAndGlobalDownloadMetadataRoutes() {
        assertTrue(isOppoDownloadMetadataUrl("https://api-cn.store.heytapmobi.com/download/v2/25925951"))
        assertTrue(isOppoDownloadMetadataUrl("https://api-store-gl.heytapmobile.com/download/v2/25925951"))
        assertTrue(
            isOppoDownloadMetadataUrl(
                "https://api-store-gl.heytapmobile.com/download/overseas/v2/com.example.app?version=1",
            ),
        )
        assertFalse(isOppoDownloadMetadataUrl("https://cdn.example.com/download/v2/base.apk"))
    }

    @Test
    fun resourceMessagesAreFoundInsideNestedResponses() {
        val resource = encodeOppoMessage(
            encodeOppoLongField(1, 2222071L),
            encodeOppoLongField(2, 25760403L),
            encodeOppoStringField(3, "王者荣耀"),
            encodeOppoStringField(7, "com.tencent.tmgp.sgame"),
            encodeOppoStringField(8, "11.4.1.1"),
            encodeOppoLongField(9, 1104010103L),
            encodeOppoLongField(10, 1918398307L),
            encodeOppoStringField(12, "md5"),
            encodeOppoStringField(14, "https://example/icon.webp"),
            encodeOppoStringField(22, "https://example/game.apk"),
            encodeOppoStringField(26, "简介"),
            encodeOppoStringField(64, "详细介绍"),
            encodeOppoStringField(102, "修复问题"),
        )
        val response = encodeOppoMessage(
            encodeOppoBytesField(1, encodeOppoMessage(encodeOppoBytesField(3, resource))),
        )

        val result = parseOppoResources(response)

        assertEquals(1, result.size)
        assertEquals(2222071L, result.single().appId)
        assertEquals("com.tencent.tmgp.sgame", result.single().packageName)
        assertEquals(1104010103L, result.single().versionCode)
        assertEquals("https://example/game.apk", result.single().url)
        assertEquals("详细介绍", result.single().description)
        assertTrue(result.single().changeLog.contains("修复"))
    }

    @Test
    fun varintsRoundTripAcrossWireBoundaries() {
        val values = listOf(0L, 127L, 128L, 16384L, Long.MAX_VALUE)

        values.forEach { value ->
            val message = parseOppoProto(encodeOppoLongField(9, value))
            assertEquals(value, message.long(9))
        }
    }

    @Test
    fun detailStatsAndScreenshotsUseOfficialResourceFields() {
        val resource = encodeOppoMessage(
            encodeOppoLongField(1, 69015L),
            encodeOppoStringField(3, "微信"),
            encodeOppoStringField(7, "com.tencent.mm"),
            encodeOppoStringField(8, "8.0.64"),
            encodeOppoLongField(9, 2600L),
            encodeOppoLongField(15, Int.MAX_VALUE.toLong()),
            encodeOppoStringField(16, "58.8 亿次安装"),
            encodeOppoLongField(17, 1_568_555L),
            encodeOppoStringField(32, "https://example.com/preview.jpg"),
            encodeOppoStringField(102, "https://example.com/hd.jpg"),
        )

        val parsed = parseOppoResources(encodeOppoBytesField(1, resource)).single()

        assertEquals(5_880_000_000L, parsed.resolvedDownloadCount())
        assertEquals(1_568_555L, parsed.commentCount)
        assertEquals(listOf("https://example.com/preview.jpg"), parsed.screenshots)
        assertEquals(listOf("https://example.com/hd.jpg"), parsed.hdScreenshots)
        assertEquals("", parsed.changeLog)
    }

    @Test
    fun v4ScreenshotDtoListIsReadFromBaseDetailField107() {
        fun screenshot(url: String) = encodeOppoMessage(
            encodeOppoLongField(1, 1080L),
            encodeOppoLongField(2, 2400L),
            encodeOppoStringField(3, url),
        )

        val resource = encodeOppoMessage(
            encodeOppoLongField(1, 1204068782L),
            encodeOppoLongField(2, 1225478062L),
            encodeOppoStringField(3, "X"),
            encodeOppoStringField(7, "com.twitter.android"),
            encodeOppoStringField(8, "1.0"),
            encodeOppoLongField(9, 1L),
            encodeOppoBytesField(
                107,
                encodeOppoMessage(
                    encodeOppoBytesField(1, screenshot("https://example.com/screenshot/one")),
                    encodeOppoBytesField(1, screenshot("https://example.com/screenshot/two")),
                ),
            ),
        )

        val parsed = parseOppoResources(encodeOppoBytesField(1, resource)).single()

        assertEquals(
            listOf(
                "https://example.com/screenshot/one",
                "https://example.com/screenshot/two",
            ),
            parsed.screenshots,
        )
    }

    @Test
    fun globalDownloadFileWrapProvidesBaseAndRequiredSplits() {
        fun file(
            id: String,
            splitName: String,
            type: Long,
            url: String,
            size: Long,
            md5: String,
        ) = encodeOppoMessage(
            encodeOppoStringField(127, "com.heytap.cdo.download.domain.dto.DownloadFileInfoDto"),
            encodeOppoStringField(1, id),
            encodeOppoStringField(2, splitName),
            encodeOppoLongField(3, 0L),
            encodeOppoLongField(4, type),
            encodeOppoStringField(5, md5),
            encodeOppoStringField(6, "header-$md5"),
            encodeOppoStringField(7, url),
            encodeOppoStringField(8, "https://api.example/fallback/$id"),
            encodeOppoLongField(9, size),
            encodeOppoLongField(10, size),
            encodeOppoLongField(11, 1L),
        )

        val response = encodeOppoMessage(
            encodeOppoLongField(1, 200L),
            encodeOppoLongField(2, 123L),
            encodeOppoBytesField(3, file("123", "base", 0L, "https://cdn.example/base.apk", 80L, "base-md5")),
            encodeOppoBytesField(3, file("123-1", "config.arm64_v8a", 4L, "https://cdn.example/arm64.apk", 20L, "split-md5")),
            encodeOppoLongField(4, 100L),
        )

        val parsed = parseOppoDownloadFileWrap(response)

        assertEquals(200, parsed.code)
        assertEquals(123L, parsed.versionCode)
        assertEquals(100L, parsed.size)
        assertEquals(2, parsed.files.size)
        assertEquals("base", parsed.files.first().splitName)
        assertEquals("https://cdn.example/base.apk", parsed.files.first().url)
        assertEquals(80L, parsed.files.first().size)
        assertTrue(parsed.files.first().essential)
        assertEquals("config.arm64_v8a", parsed.files.last().splitName)
    }

    @Test
    fun exactOrganicResultIsPrioritizedAndBannerIsMarkedAsAd() {
        fun resource(appId: Long, name: String, packageName: String) = encodeOppoMessage(
            encodeOppoLongField(1, appId),
            encodeOppoStringField(3, name),
            encodeOppoStringField(7, packageName),
            encodeOppoStringField(8, "1.0"),
            encodeOppoLongField(9, 1L),
        )

        val banner = encodeOppoMessage(
            encodeOppoStringField(127, "com.heytap.cdo.card.domain.dto.BannerCardDto"),
            encodeOppoBytesField(104, resource(1L, "推广应用", "com.example.ad")),
        )
        val list = encodeOppoMessage(
            encodeOppoStringField(127, "com.heytap.cdo.card.domain.dto.ListCardDto"),
            encodeOppoBytesField(103, resource(2L, "QQ", "com.tencent.mobileqq")),
        )
        val response = encodeOppoMessage(
            encodeOppoBytesField(3, banner),
            encodeOppoBytesField(3, list),
        )

        val parsed = parseOppoResources(response)
        val ordered = prioritizeOppoSearchResults(parsed, "QQ")

        assertTrue(parsed.first { it.packageName == "com.example.ad" }.isAd)
        assertFalse(parsed.first { it.packageName == "com.tencent.mobileqq" }.isAd)
        assertEquals("com.tencent.mobileqq", ordered.first().packageName)
    }

    @Test
    fun advertisedCopyDoesNotHideLaterOrganicCopy() {
        fun resource() = encodeOppoMessage(
            encodeOppoLongField(1, 69015L),
            encodeOppoLongField(2, 100L),
            encodeOppoStringField(3, "微信"),
            encodeOppoStringField(7, "com.tencent.mm"),
            encodeOppoStringField(8, "8.0"),
            encodeOppoLongField(9, 100L),
        )

        val response = encodeOppoMessage(
            encodeOppoBytesField(
                3,
                encodeOppoMessage(
                    encodeOppoStringField(127, "com.heytap.cdo.card.domain.dto.BannerCardDto"),
                    encodeOppoBytesField(104, resource()),
                ),
            ),
            encodeOppoBytesField(
                3,
                encodeOppoMessage(
                    encodeOppoStringField(127, "com.heytap.cdo.card.domain.dto.ListCardDto"),
                    encodeOppoBytesField(103, resource()),
                ),
            ),
        )

        val copies = parseOppoResources(response).filter { it.packageName == "com.tencent.mm" }

        assertEquals(2, copies.size)
        assertEquals(1, copies.count(OppoResource::isAd))
        assertEquals(1, copies.count { !it.isAd })
    }

    @Test
    fun transAdMetadataMarksOnlyPromotedResource() {
        fun resource(appId: Long, name: String, packageName: String, ad: Boolean) = encodeOppoMessage(
            encodeOppoLongField(1, appId),
            encodeOppoStringField(3, name),
            encodeOppoStringField(7, packageName),
            encodeOppoStringField(8, "1.0"),
            encodeOppoLongField(9, 1L),
            *if (ad) arrayOf(
                encodeOppoBytesField(
                    80,
                    encodeOppoMessage(
                        encodeOppoStringField(127, "com.heytap.cdo.common.domain.dto.TransAdInfoDto"),
                    ),
                ),
            ) else emptyArray(),
        )

        val response = encodeOppoMessage(
            encodeOppoBytesField(1, resource(1L, "微信", "com.tencent.mm", false)),
            encodeOppoBytesField(1, resource(2L, "推广推荐", "com.example.ad", true)),
            encodeOppoBytesField(1, resource(3L, "微信输入法", "com.tencent.wetype", false)),
        )

        val parsed = parseOppoResources(response)

        assertEquals(3, parsed.size)
        assertTrue(parsed.first { it.packageName == "com.example.ad" }.isAd)
        assertFalse(parsed.first { it.packageName == "com.tencent.mm" }.isAd)
        assertFalse(parsed.first { it.packageName == "com.tencent.wetype" }.isAd)
    }

    @Test
    fun upgradePatchListIsAttachedToItsResource() {
        val patch = encodeOppoMessage(
            encodeOppoLongField(1, 2_379_086L),
            encodeOppoStringField(2, "https://example.com/update.patch"),
            encodeOppoStringField(3, "43e9d40c8c5612abb0e82500d17ac2eb"),
            encodeOppoLongField(4, 5L),
        )
        val resource = encodeOppoMessage(
            encodeOppoLongField(1, 69015L),
            encodeOppoStringField(3, "微信"),
            encodeOppoStringField(7, "com.tencent.mm"),
            encodeOppoStringField(8, "8.0.64"),
            encodeOppoLongField(9, 2600L),
            encodeOppoLongField(10, 300_000_000L),
            encodeOppoStringField(12, "a30d38c9b5931bfdaa603c02369ff084"),
            encodeOppoStringField(22, "https://example.com/full.apk"),
            encodeOppoBytesField(109, patch),
        )

        val parsed = parseOppoResources(encodeOppoBytesField(1, resource)).single()

        assertEquals(1, parsed.patches.size)
        assertEquals(5, parsed.patches.single().obitVersion)
        assertEquals(2_379_086L, parsed.patches.single().size)
        assertEquals("https://example.com/update.patch", parsed.patches.single().url)
    }

    @Test
    fun beautyWeeklyCardsBecomeTodayFeedItems() {
        val resource = encodeOppoMessage(
            encodeOppoStringField(127, "com.heytap.cdo.card.domain.dto.beautyapp.BeautyAppDetailDto"),
            encodeOppoLongField(1, 30755227L),
            encodeOppoStringField(3, "奥比岛：梦想国度"),
            encodeOppoStringField(7, "com.example.beauty"),
            encodeOppoStringField(8, "3.0"),
            encodeOppoLongField(9, 300L),
            encodeOppoLongField(15, 21_920_000L),
            encodeOppoStringField(16, "2192 万次安装"),
            encodeOppoStringField(14, "https://example.com/icon.png"),
            encodeOppoStringField(22, "https://example.com/app.apk"),
            encodeOppoStringField(30, "经营策略"),
            encodeOppoStringField(103, "什么？！我变成了一只毛茸茸的小熊！"),
            encodeOppoStringField(107, "第 404 期"),
            encodeOppoBytesField(
                108,
                encodeOppoMessage(
                    encodeOppoStringField(127, "com.heytap.cdo.card.domain.dto.BannerDto"),
                    encodeOppoStringField(2, "https://example.com/cover.jpg"),
                    encodeOppoStringField(4, "oap://mk/snippet?pk=70004914&p=/card/store/v5/snippet/4914"),
                ),
            ),
            encodeOppoLongField(109, 4914L),
            encodeOppoBytesField(
                41,
                encodeOppoMessage(
                    encodeOppoBytesField(
                        1,
                        encodeOppoMessage(
                            encodeOppoStringField(1, "columnType"),
                            encodeOppoStringField(2, "至美奖"),
                        ),
                    ),
                ),
            ),
        )
        val response = encodeOppoMessage(
            encodeOppoStringField(2, "每周至美"),
            encodeOppoBytesField(
                3,
                encodeOppoMessage(
                    encodeOppoStringField(127, "com.heytap.cdo.card.domain.dto.AppCardDto"),
                    encodeOppoBytesField(103, resource),
                ),
            ),
        )

        val page = parseOppoBeautyFeed(response, pageSize = 10)
        val item = page.items.single()

        assertEquals("4914", item.rId)
        assertEquals("第 404 期", item.title)
        assertEquals("什么？！我变成了一只毛茸茸的小熊！", item.summary)
        assertEquals("https://example.com/cover.jpg", item.coverImage)
        assertEquals("至美奖", item.awardName)
        assertEquals("com.example.beauty", item.app?.packageName)
        assertEquals(AppSource.OPPO, item.app?.source)
        assertEquals("经营策略", item.app?.category)
        assertEquals(21_920_000L, item.app?.downloadCount)
        assertFalse(page.hasMore)
    }

    @Test
    fun snippetComponentsPreserveArticleOrder() {
        val resource = encodeOppoMessage(
            encodeOppoStringField(127, "com.heytap.cdo.common.domain.dto.ResourceDto"),
            encodeOppoLongField(1, 30755227L),
            encodeOppoStringField(3, "奥比岛：梦想国度"),
            encodeOppoStringField(7, "com.example.beauty"),
            encodeOppoStringField(8, "3.0"),
            encodeOppoLongField(9, 300L),
            encodeOppoLongField(15, 21_920_000L),
            encodeOppoStringField(14, "https://example.com/icon.png"),
            encodeOppoStringField(22, "https://example.com/app.apk"),
            encodeOppoStringField(30, "经营策略"),
        )

        fun image(url: String, width: Long, height: Long) = encodeOppoMessage(
            encodeOppoStringField(127, "com.heytap.cdo.osnippet.domain.dto.component.image.ImageComponent"),
            encodeOppoBytesField(
                2,
                encodeOppoMessage(
                    encodeOppoStringField(127, "com.heytap.cdo.osnippet.domain.dto.component.image.ImageCompProps"),
                    encodeOppoStringField(101, url),
                    encodeOppoLongField(102, width),
                    encodeOppoLongField(103, height),
                ),
            ),
        )

        val text = encodeOppoMessage(
            encodeOppoStringField(127, "com.heytap.cdo.osnippet.domain.dto.component.text.TextComponent"),
            encodeOppoBytesField(
                2,
                encodeOppoMessage(
                    encodeOppoStringField(127, "com.heytap.cdo.osnippet.domain.dto.component.text.TextCompProps"),
                    encodeOppoStringField(101, "正文内容"),
                ),
            ),
        )
        val card = encodeOppoMessage(
            encodeOppoStringField(127, "com.heytap.cdo.osnippet.domain.dto.component.card.CardComponent"),
            encodeOppoBytesField(
                101,
                encodeOppoMessage(
                    encodeOppoStringField(127, "com.heytap.cdo.card.domain.dto.AppListCardDto"),
                    encodeOppoBytesField(103, resource),
                ),
            ),
        )
        val headerUrl = "https://example.com/header.jpg"
        val response = encodeOppoMessage(
            encodeOppoBytesField(
                1,
                encodeOppoMessage(
                    encodeOppoStringField(127, "com.heytap.cdo.osnippet.domain.dto.Header"),
                    encodeOppoStringField(1, "文章标题"),
                    encodeOppoStringField(5, headerUrl),
                ),
            ),
            encodeOppoBytesField(
                2,
                encodeOppoMessage(
                    encodeOppoStringField(127, "com.heytap.cdo.osnippet.domain.dto.Body"),
                    encodeOppoBytesField(1, image(headerUrl, 1080L, 1704L)),
                    encodeOppoBytesField(1, text),
                    encodeOppoBytesField(1, image("https://example.com/body.jpg", 1920L, 1080L)),
                    encodeOppoBytesField(1, card),
                ),
            ),
            encodeOppoBytesField(
                99,
                encodeOppoMessage(
                    encodeOppoBytesField(
                        1,
                        encodeOppoMessage(
                            encodeOppoStringField(1, "columnType"),
                            encodeOppoBytesField(2, encodeOppoStringField(9, "至美奖")),
                        ),
                    ),
                ),
            ),
        )

        val article = parseOppoSnippetArticle("4914", response)

        assertEquals("文章标题", article.title)
        assertEquals("至美奖", article.awardName)
        assertEquals(headerUrl, article.headerImage)
        assertEquals("正文内容", article.richTextHtml)
        assertEquals("com.example.beauty", article.apps.single().packageName)
        assertEquals("经营策略", article.apps.single().category)
        assertEquals(21_920_000L, article.apps.single().downloadCount)
        assertEquals(4, article.blocks.size)
        assertTrue(article.blocks[0] is TodayArticleBlock.Banner)
        assertTrue(article.blocks[1] is TodayArticleBlock.RichText)
        assertTrue(article.blocks[2] is TodayArticleBlock.Image)
        assertTrue(article.blocks[3] is TodayArticleBlock.App)
    }

    @Test
    fun updateRequestUsesOfficialNegotiationOnlyWhenAUsableBaseExists() {
        val capable = InstalledPackage(
            packageName = "com.example.capable",
            versionCode = 10L,
            isSystemApp = false,
            oldApkHash = "0123456789abcdef0123456789abcdef",
            splits = "0",
            baseApkPath = "/data/app/base.apk",
            targetSdkVersion = 35,
            signature = "0123456789ABCDEF0123456789ABCDEF",
            signatureList = listOf(
                "11111111111111111111111111111111",
                "22222222222222222222222222222222",
            ),
            installOrigin = "com.example.installer",
        )
        val fullOnly = InstalledPackage(
            packageName = "com.example.split",
            versionCode = 20L,
            isSystemApp = false,
            oldApkHash = "fedcba9876543210fedcba9876543210",
            splits = "config.arm64_v8a",
            baseApkPath = "/data/app/base.apk",
        )

        val wrapper = parseOppoProto(encodeOppoUpdateRequest(listOf(capable, fullOnly)))
        val requests = wrapper
            .all(1)
            .mapNotNull { it.raw?.let(::parseOppoProto) }

        assertEquals(listOf(1L, 5L), wrapper.all(2).mapNotNull(OppoProtoField::varint))
        assertEquals(0L, wrapper.long(3))
        assertEquals(
            "com.heytap.cdo.global.update.domain.UpgradeReqV2",
            requests[0].string(127),
        )
        assertEquals(listOf(1L, 5L), requests[0].all(5).mapNotNull(OppoProtoField::varint))
        assertEquals(capable.oldApkHash, requests[0].string(3))
        assertEquals(35L, requests[0].long(6))
        assertEquals(capable.signature, requests[0].string(7))
        assertEquals(capable.signatureList, requests[0].all(9).mapNotNull { it.raw?.decodeToString() })
        assertEquals(capable.installOrigin, requests[0].string(11))
        assertTrue(requests[1].all(5).isEmpty())
        assertEquals("0", requests[1].string(3))

        val fullOnlyWrapper = parseOppoProto(encodeOppoUpdateRequest(listOf(fullOnly)))
        val fullOnlyRequest = parseOppoProto(requireNotNull(fullOnlyWrapper.first(1)?.raw))
        assertTrue(fullOnlyWrapper.all(2).isEmpty())
        assertTrue(fullOnlyRequest.all(5).isEmpty())
    }

}
