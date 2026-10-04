package top.app.market.data.remote.wandoujia

import top.app.market.data.platform.gunzip
import top.app.market.domain.model.market.AppSource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WandoujiaProtocolTest {
    @Test
    fun historySignatureMatchesCapturedVector() {
        val data = buildJsonObject {
            put("offset", 0)
            put("appId", 281291)
            put("count", 20)
            put("packageName", "tv.danmaku.bili")
            put("page", 1)
        }

        assertEquals("bac714e36540ff0822faa403d66f9cf6", wandoujiaDirectSign(data))
    }

    @Test
    fun detailCombineSignatureMatchesCapturedVector() {
        val data = buildJsonArray {
            add(buildJsonObject {
                put("service", "resource.app.getDetail")
                put("data", buildJsonObject {
                    put("screenWidth", 1644)
                    put("appId", 281291)
                })
            })
            add(buildJsonObject {
                put("service", "op.app.getDetailOps")
                put("data", buildJsonObject {
                    put("types", buildJsonArray {
                        listOf(2, 3, 11, 18, 19, 20).forEach { add(JsonPrimitive(it)) }
                    })
                    put("appId", 281291)
                })
            })
        }

        assertEquals(
            "c6340052f3b5d0001d202c9854484e27",
            wandoujiaCombineSign(3375042285424495978L, data),
        )
    }

    @Test
    fun searchResponseMapsToMarketModel() {
        val root = parseWandoujiaObject(
            """
            {
              "data": [{
                "service": "search.app.list",
                "state": {"code": 2000000},
                "data": {"content": [{
                  "id": 596157,
                  "packageName": "com.tencent.mm",
                  "name": "微信",
                  "versionName": "8.0.76",
                  "versionCode": 3140,
                  "size": 266098600,
                  "score": 6,
                  "iconUrl": "https://example/icon.png"
                }]}
              }]
            }
            """.trimIndent()
        )

        val page = parseWandoujiaSearch(root, page = 0)
        assertTrue(page.hasMore)
        assertEquals(1, page.items.size)
        assertEquals(AppSource.WANDOUJIA, page.items.single().source)
        assertEquals(266098600L, page.items.single().apkSize)
        assertEquals(3.0, page.items.single().ratingScore)
    }

    @Test
    fun downloadUrlKeepsParametersAndAddsSequenceOnce() {
        val original = "https://android-apps.pp.cn/a.apk?size=142994318&md5=abc123&pkgType=2"
        val appended = appendWandoujiaSequence(original, 1234L)

        assertEquals("$original&seq=1234", appended)
        assertEquals(appended, appendWandoujiaSequence(appended, 5678L))
        assertEquals("142994318", wandoujiaUrlParameter(appended, "size"))
        assertEquals("abc123", wandoujiaUrlParameter(appended, "md5"))
    }

    @Test
    fun m90RequestMatchesFixedVector() {
        val encoded = wandoujiaM90Encode(
            type = 31,
            data = "hello".encodeToByteArray(),
            key = "d861a5214f1849a1".encodeToByteArray(),
            seed = 0x12345678,
        )

        assertEquals("6d39301f12345678c2b7d18d91c8b0", encoded.toHex())
    }

    @Test
    fun capturedM90ResponseDecryptsAndInflates() {
        val encrypted = Base64.getDecoder().decode(
            "bTkwHkd5XfwSncmUCPue+rf0U02WzoqeeVY0ytmOW91yODJfhx/rz/1eaCSr4dLHaKAhr8kCW9R1hIsOlnsw8YsaieNVhdvWnrJhRST3NDBq8nuTrxhylKxxxXtXEU+tapw1GPzgkcKjIpVP82hBSe+YhB7ZMEa1"
        )
        val json = gunzip(wandoujiaM90DecodeResponse(encrypted)).decodeToString()

        assertTrue(json.contains("\"isNeedUpdate\":0"))
        assertTrue(json.contains("\"code\":2000000"))
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
