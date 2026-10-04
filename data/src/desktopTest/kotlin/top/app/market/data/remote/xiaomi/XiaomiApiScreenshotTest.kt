package top.app.market.data.remote.xiaomi

import top.app.market.domain.model.market.AppScreenshot
import top.app.market.domain.model.market.AppVideo
import top.app.market.domain.model.market.ScreenshotOrientation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class XiaomiApiScreenshotTest {

    @Test
    fun detailScreenshotItemsPreserveIndividualOrientation() {
        val response = json(
            """
            {
              "detailTabList": [{
                "data": {
                  "detailVideoAndScreenshotList": [
                    {"screenshot":"portrait.png","orientation":0},
                    {"screenshot":"landscape.png","orientation":1}
                  ]
                }
              }]
            }
            """.trimIndent()
        )

        val screenshots = parseScreenshots(response) { path -> "resolved/$path" }

        assertEquals(
            listOf(
                AppScreenshot("resolved/portrait.png", ScreenshotOrientation.PORTRAIT),
                AppScreenshot("resolved/landscape.png", ScreenshotOrientation.LANDSCAPE),
            ),
            screenshots,
        )
    }

    @Test
    fun legacyScreenshotListUsesAppLevelOrientation() {
        val response = json(
            """
            {
              "appInfo": {
                "screenshot":"first.png, second.png",
                "screenshotType":"horizontal"
              }
            }
            """.trimIndent()
        )

        val screenshots = parseScreenshots(response, resolveUrl = { it })

        assertEquals(2, screenshots.size)
        assertEquals(List(2) { ScreenshotOrientation.LANDSCAPE }, screenshots.map(AppScreenshot::orientation))
    }

    @Test
    fun missingOrientationDefaultsToPortraitWithoutImageInspection() {
        val response = json(
            """
            {
              "detailTabList": [{
                "data": {
                  "detailVideoAndScreenshotList": [
                    {"screenshot":"unknown.png"}
                  ]
                }
              }]
            }
            """.trimIndent()
        )

        val screenshot = parseScreenshots(response, resolveUrl = { it }).single()

        assertEquals(ScreenshotOrientation.PORTRAIT, screenshot.orientation)
    }

    @Test
    fun promotionUsesFullSizeResolverForExpandedImage() {
        val response = json(
            """
            {
              "detailTabList": [{
                "data": {
                  "detailVideoAndScreenshotList": [{
                    "type": 8,
                    "screenshot": "preview.png",
                    "appActivityConfig": {
                      "expandScreenshot": "expanded.png"
                    }
                  }]
                }
              }]
            }
            """.trimIndent()
        )

        val promotion = parseDetailMedia(
            json = response,
            resolveUrl = { path -> "preview/$path" },
            resolveExpandedUrl = { path -> "full-size/$path" },
        ).promotions.single()

        assertEquals("preview/preview.png", promotion.previewImageUrl)
        assertEquals("full-size/expanded.png", promotion.expandedImageUrl)
    }

    @Test
    fun communityTabFirstStillResolvesAppInfoTabMedia() {
        val response = json(
            """
            {
              "detailTabList": [
                {
                  "type": "detailGameCommunity",
                  "data": {"communityUrl":"https://example.com"}
                },
                {
                  "type": "detailTabAppInfo",
                  "data": {
                    "detailVideoAndScreenshotList": [
                      {"screenshot":"shot.png","orientation":1}
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val screenshot = parseScreenshots(response, resolveUrl = { it }).single()

        assertEquals("shot.png", screenshot.url)
        assertEquals(ScreenshotOrientation.LANDSCAPE, screenshot.orientation)
    }

    @Test
    fun videoItemsParseUrlCoverOrientationAndTitle() {
        val response = json(
            """
            {
              "detailTabList": [{
                "data": {
                  "detailVideoAndScreenshotList": [
                    {
                      "detailVideoAndScreenshotId": "DETAIL_TRIAL_MATERIALS_VIDEO_1",
                      "appVideoInfoWithCover": {
                        "videoUrl": "https://vd0.market.xiaomi.com/video/trial",
                        "showVideo": true,
                        "coverUrl": "AppStore/trial-cover"
                      },
                      "orientation": 1,
                      "type": 10
                    },
                    {
                      "detailVideoAndScreenshotId": "DETAIL_VIDEO_1",
                      "displayName": "英雄没有闪",
                      "appVideoInfoWithCover": {
                        "videoUrl": "https://vd0.market.xiaomi.com/video/promo",
                        "showVideo": true,
                        "coverUrl": "AppStore/promo-cover"
                      },
                      "videoId": 896690,
                      "orientation": 1,
                      "type": 1
                    },
                    {"screenshot":"shot.png","orientation":0,"type":0}
                  ]
                }
              }]
            }
            """.trimIndent()
        )

        val media = parseDetailMedia(response, resolveUrl = { path -> "resolved/$path" })

        assertEquals(
            listOf(
                AppVideo(
                    url = "https://vd0.market.xiaomi.com/video/trial",
                    coverUrl = "resolved/AppStore/trial-cover",
                    orientation = ScreenshotOrientation.LANDSCAPE,
                ),
                AppVideo(
                    url = "https://vd0.market.xiaomi.com/video/promo",
                    coverUrl = "resolved/AppStore/promo-cover",
                    orientation = ScreenshotOrientation.LANDSCAPE,
                    title = "英雄没有闪",
                ),
            ),
            media.videos,
        )
        assertEquals(listOf("resolved/shot.png"), media.screenshots.map(AppScreenshot::url))
    }

    @Test
    fun hiddenOrBlankVideoItemsAreSkipped() {
        val response = json(
            """
            {
              "detailTabList": [{
                "data": {
                  "detailVideoAndScreenshotList": [
                    {
                      "appVideoInfoWithCover": {"videoUrl": "https://example.com/v", "showVideo": false, "coverUrl": "c"},
                      "orientation": 1,
                      "type": 1
                    },
                    {
                      "appVideoInfoWithCover": {"videoUrl": "", "showVideo": true, "coverUrl": "c"},
                      "orientation": 1,
                      "type": 1
                    }
                  ]
                }
              }]
            }
            """.trimIndent()
        )

        assertEquals(emptyList(), parseDetailMedia(response, resolveUrl = { it }).videos)
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
