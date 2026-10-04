package top.app.market.data.remote.honor

import top.app.market.data.remote.xiaomi.LenientJson
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.ScreenshotOrientation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HonorModelsTest {
    @Test
    fun relativeImageFieldsResolveToOfficialHttpsCdn() {
        val record = record(
            """
            {
              "id": 1,
              "pName": "com.example.app",
              "verCode": 2,
              "verName": "1.0",
              "imgUrl": "/honor/images/icon.webp",
              "shotImg": "/honor/images/one.webp,https://cdn.example/two.webp",
              "isLandScape": true
            }
            """.trimIndent(),
        )

        assertEquals(
            "https://appimg-drcn.hihonorcdn.com/honor/images/icon.webp",
            record.toApp().icon,
        )
        assertEquals(
            listOf(
                "https://appimg-drcn.hihonorcdn.com/honor/images/one.webp",
                "https://cdn.example/two.webp",
            ),
            record.toDetail().screenshots.map { it.url },
        )
        assertEquals(ScreenshotOrientation.LANDSCAPE, record.toDetail().screenshots.first().orientation)
    }

    @Test
    fun protocolRelativeAndHttpImagesAreUpgradedToHttps() {
        assertEquals("https://cdn.example/icon.webp", honorImageUrl("//cdn.example/icon.webp"))
        assertEquals("https://cdn.example/icon.webp", honorImageUrl("http://cdn.example/icon.webp"))
    }

    @Test
    fun promotionalArtworkIsNotUsedAsAnAppScreenshot() {
        val detail = record(
            """
            {
              "id": 7,
              "pName": "com.example.office",
              "name": "Office",
              "verCode": 10,
              "verName": "1.0",
              "fileSize": 27182047,
              "stars": 3.1,
              "downTm": 75892248,
              "nonStandardThemeUniversalMaterial": {
                "listShadingDiagram": "https://cdn.example/promotional.webp"
              }
            }
            """.trimIndent(),
        ).toDetail()

        assertEquals(3.1, detail.app.ratingScore)
        assertEquals(27182047L, detail.app.apkSize)
        assertEquals(75892248L, detail.downloadCount)
        assertTrue(detail.screenshots.isEmpty())
    }

    @Test
    fun shareDetailProvidesNormalDetailMetadataAndScreenshots() {
        val detail = record(
            """
            {
              "id": 42,
              "pName": "com.example.directory",
              "name": "Directory",
              "verCode": 200,
              "verName": "2.0",
              "imgUrl": "https://cdn.example/icon.webp",
              "stars": 4.3,
              "scoreNum": "36",
              "displayCommentNum": 36,
              "downNum": 42042375,
              "desc": "Full description",
              "shotImg": "https://cdn.example/one.webp,https://cdn.example/two.webp",
              "classifyInfo": [{"classifyName": "Tools"}]
            }
            """.trimIndent(),
        ).toDetail()

        assertEquals("Directory", detail.app.displayName)
        assertEquals("2.0", detail.app.versionName)
        assertEquals(4.3, detail.app.ratingScore)
        assertEquals(42042375L, detail.downloadCount)
        assertEquals(36L, detail.commentCount)
        assertEquals(2, detail.screenshots.size)
        assertTrue(detail.screenshots.all { it.orientation == ScreenshotOrientation.PORTRAIT })
    }

    @Test
    fun sameVersionMergeKeepsDownloadAndPatchDataWhileAddingScreenshots() {
        val downloadable = record(
            """
            {
              "id": 42,
              "pName": "com.example.directory",
              "name": "Directory",
              "verCode": 200,
              "verName": "2.0",
              "fileSize": 26000000,
              "downUrl": "https://cdn.example/app.apk",
              "downloadUrlMetaPath": "/app.apk",
              "apkIdentifier": "${"a".repeat(64)}",
              "apks": [{"fileSize": 26000000, "downUrl": "https://cdn.example/app.apk"}],
              "diffApk": {"fileSize": 8000000, "diffType": "diffx"}
            }
            """.trimIndent(),
        )
        val richDetail = record(
            """
            {
              "id": 42,
              "pName": "com.example.directory",
              "name": "Directory",
              "verCode": 200,
              "verName": "2.0",
              "stars": 4.3,
              "downNum": 42042375,
              "desc": "Full description",
              "shotImg": "https://cdn.example/one.webp,https://cdn.example/two.webp"
            }
            """.trimIndent(),
        )

        val merged = mergeHonorRecords(listOf(downloadable, richDetail)).single()

        assertEquals(26000000L, merged.toApp().apkSize)
        assertEquals(8000000L, merged.toApp().deltaSize)
        assertEquals("https://cdn.example/app.apk", merged.value["downUrl"]?.toString()?.trim('"'))
        assertEquals(2, merged.toDetail().screenshots.size)
        assertEquals("Full description", merged.toDetail().introduction)
    }

    @Test
    fun downloadPayloadRequiresAResponseProvidedLocation() {
        val displayOnly = record(
            """{"pName":"com.example.system","verCode":2,"verName":"2.0","fileSize":10}""",
        )
        val rootDownload = record(
            """{"pName":"com.example.system","verCode":2,"verName":"2.0","downUrl":"https://cdn.example/app.apk"}""",
        )
        val splitDownload = record(
            """{"pName":"com.example.system","verCode":2,"verName":"2.0","apks":[{"downloadUrlMetaPath":"/apk/base.apk"}]}""",
        )

        assertFalse(displayOnly.hasDownloadPayload())
        assertTrue(rootDownload.hasDownloadPayload())
        assertTrue(splitDownload.hasDownloadPayload())
    }

    @Test
    fun exactPackageSelectionRejectsSimilarSearchResults() {
        val result = listOf(
            record("""{"pName":"com.example.system.plus","verCode":9,"verName":"9.0","downUrl":"https://cdn.example/wrong.apk"}"""),
            record("""{"pName":"COM.EXAMPLE.SYSTEM","verCode":2,"verName":"2.0","downUrl":"https://cdn.example/right.apk"}"""),
        ).honorRecordForPackage("com.example.system")

        assertEquals("COM.EXAMPLE.SYSTEM", result?.packageName)
        assertEquals(2L, result?.versionCode)
    }

    @Test
    fun blockedV2SearchDoesNotPreventV1DownloadMetadataFallback() = runBlocking {
        var v1Called = false
        val failures = mutableListOf<String>()

        val records = mergeHonorSearchSources(
            appList = { error("search risk forbidden") },
            merged = {
                v1Called = true
                listOf(
                    record(
                        """{"pName":"com.example.system","verCode":2,"verName":"2.0","downUrl":"https://cdn.example/app.apk"}""",
                    ),
                )
            },
            onFailure = { source, _ -> failures += source },
        )

        assertTrue(v1Called)
        assertEquals(listOf("v2"), failures)
        assertTrue(records.single().hasDownloadPayload())
    }

    @Test
    fun incrementalUpdateSizesAreParsed() {
        val apps = parseApps(
            """
            [
              {"id":1,"pName":"com.example.one","name":"One","verCode":2,"verName":"2.0","fileSize":26000000,"diffApk":{"fileSize":8000000,"diffType":"diffx"}},
              {"id":2,"pName":"com.example.two","name":"Two","verCode":3,"verName":"3.0","fileSize":200000000,"diffApk":{"fileSize":74000000,"diffType":"diffx"}}
            ]
            """.trimIndent(),
        ).map(HonorAppRecord::toApp)

        assertEquals(listOf(8000000L, 74000000L), apps.map { it.deltaSize })
        assertTrue(apps.zip(listOf(26000000L, 200000000L)).all { (app, fullSize) ->
            app.deltaSize in 1 until fullSize
        })
    }

    @Test
    fun installedDetailUsesOfficialWireNamesAndChannelDefault() {
        val target = "com.example.system"
        val hash = "a".repeat(64)
        val payload = honorDetailPayload(
            target,
            InstalledPackage(
                packageName = target,
                versionCode = 42,
                versionName = "4.2",
                isSystemApp = true,
                oldApkHash = hash,
            ),
        )

        assertEquals(target, payload["pName"]?.toString()?.trim('"'))
        assertEquals("42", payload["ver"]?.toString())
        assertEquals(hash, payload["identifier"]?.toString()?.trim('"'))
        assertEquals("-1", payload["pkgChannel"]?.toString())
        assertEquals("0", payload["isInternal"]?.toString())
        assertEquals("0", payload["adIntegrationScene"]?.toString())
        assertFalse("from" in payload)
        assertFalse("marketId" in payload)
        assertFalse("detailType" in payload)
        assertFalse("packageVersion" in payload)
        assertFalse("apkSha256" in payload)
    }

    @Test
    fun genericDetailDoesNotInventAnInstalledPackage() {
        val payload = honorDetailPayload("com.example.system", installed = null)

        assertEquals("-1", payload["pkgChannel"]?.toString())
        assertEquals("0", payload["isInternal"]?.toString())
        assertFalse("ver" in payload)
        assertFalse("identifier" in payload)
        assertFalse("bundleList" in payload)
    }

    private fun record(value: String): HonorAppRecord = HonorAppRecord(
        LenientJson.parseToJsonElement(value) as JsonObject,
    )

    private fun parseApps(value: String) =
        (LenientJson.parseToJsonElement(value) as JsonArray).honorApps()
}
