package top.app.market.data.remote.xiaomi

import top.app.market.domain.model.market.MarketAppInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class XiaomiApiDeltaSizeTest {
    @Test
    fun requestFieldsIncludeUniqueSessionAndCandidateIdentity() {
        val fields = deltaSizeRequestFields(
            common = mapOf(
                "device" to "popsicle",
                "tzNonce" to "nonce",
                "tzSign" to "sign",
            ),
            instanceId = "instance-",
            candidates = listOf(
                candidate("one.package", versionCode = 10L, hash = "hash-one"),
                candidate("two.package", versionCode = 20L, hash = "hash-two"),
            ),
            timestamp = 123L,
        )

        assertEquals("instance-123", fields["session_id"])
        assertEquals("one.package,two.package", fields["packageName"])
        assertEquals("10,20", fields["versionCode"])
        assertEquals("hash-one,hash-two", fields["oldApkHash"])
        assertEquals("popsicle", fields["device"])
        assertFalse("tzNonce" in fields)
        assertFalse("tzSign" in fields)
    }

    @Test
    fun missingDeltaListRequestsRetry() {
        assertNull(parseDeltaSizeResponse(JsonObject(emptyMap())))
    }

    @Test
    fun explicitEmptyDeltaListIsACompletedLookup() {
        assertEquals(emptyMap(), parseDeltaSizeResponse(json("""{"apkDiffInfoList":[]}""")))
    }

    @Test
    fun responseKeepsOnlyPositiveNamedDeltaSizes() {
        val response = json(
            """
            {
              "apkDiffInfoList": [
                {"packageName":"one.package","diffFileSize":1024},
                {"packageName":"","diffFileSize":2048},
                {"packageName":"two.package","diffFileSize":0}
              ]
            }
            """.trimIndent()
        )

        assertEquals(mapOf("one.package" to 1024L), parseDeltaSizeResponse(response))
    }

    private fun candidate(packageName: String, versionCode: Long, hash: String) = MarketAppInfo(
        appId = versionCode,
        packageName = packageName,
        displayName = packageName,
        publisherName = "",
        versionName = "2.0",
        versionCode = versionCode + 1L,
        icon = "",
        apkSize = 4096L,
        ratingScore = 0.0,
        installedVersionCode = versionCode,
        installedOldApkHash = hash,
    )

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
