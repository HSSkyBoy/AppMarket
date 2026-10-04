package top.app.market.data.remote.huawei

import top.app.market.data.platform.gunzip
import top.app.market.data.remote.xiaomi.LenientJson
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HuaweiProtocolTest {
    @Test
    fun anonymousUuidUsesOfficialSha256WireIdentity() {
        val deviceId = huaweiAnonymousDeviceId("5c2fd952-0418-4e64-b249-6dbf38055b90")

        assertEquals("f6dee89eaae71f32cbc49d3644243f75a3cb417479432a93a011c07aef78b13f", deviceId)
        assertEquals(64, deviceId.length)
        assertTrue(deviceId.all { it in "0123456789abcdef" })
    }

    @Test
    fun clientApiBodyIsSortedUrlEncodingInsideGzip() {
        val decoded = gunzip(
            huaweiGzipForm(
                linkedMapOf(
                    "z" to "智慧 搜索",
                    "a" to "first",
                ),
            ),
        ).decodeToString()

        assertEquals("a=first&z=%E6%99%BA%E6%85%A7+%E6%90%9C%E7%B4%A2", decoded)
    }

    @Test
    fun chinaGatewayUsesCapturedAppGalleryV05Route() {
        assertEquals(
            "https://store-drcn.hispace.dbankcloud.com/hwmarket/api/clientApi",
            huaweiGateway("cn"),
        )
    }

    @Test
    fun fullUpgradeRecordRemainsAFullPackage() {
        val response = parseHuaweiUpdateResponse(
            json(
                """
                {
                  "rtnCode": 0,
                  "list": [
                    {
                      "id": "C42",
                      "package": "com.example.system",
                      "name": "System component",
                      "version": "2.0.0",
                      "versionCode": 200,
                      "size": 4096,
                      "fullSize": 4096,
                      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "downurl": "https://cdn.example/system.apk?token=dynamic",
                      "fullDownUrl": "https://cdn.example/system.apk?token=dynamic",
                      "isDiff": 0,
                      "packingType": 0,
                      "bundleSize": 0,
                      "btnDisable": 0
                    }
                  ],
                  "notRcmList": []
                }
                """.trimIndent(),
            ),
            setOf("com.example.system"),
        )

        val update = response.updates.single()
        assertEquals("com.example.system", update.app.packageName)
        assertEquals(200, update.app.versionCode)
        assertEquals(4096, update.app.fullSize)
        assertEquals("https://cdn.example/system.apk?token=dynamic", update.fullDownloadUrl)
        assertFalse(update.isDiff)
        assertTrue("com.example.system" in response.recognizedPackages)
    }

    @Test
    fun mainSearchParsesNestedAppGalleryCards() {
        val records = parseHuaweiSearchRecords(
            json(
                """
                {
                  "rtnCode": 0,
                  "layoutData": [
                    {
                      "layoutName": "safeappcard",
                      "dataList": [
                        {
                          "list": [
                            {
                              "appid": "C102554223",
                              "package": "com.huawei.calculator",
                              "name": "计算器",
                              "appVersionName": "10.1.0.520",
                              "versionCode": "100100520",
                              "size": 380030,
                              "fullSize": 380030,
                              "icon": "https://cdn.example/calculator.png",
                              "downurl": "https://cdn.example/calculator.apk?token=dynamic",
                              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                              "packingType": 0,
                              "bundleSize": 0,
                              "btnDisable": 0
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val app = records.single()
        assertEquals("C102554223", app.rawId)
        assertEquals("com.huawei.calculator", app.packageName)
        assertEquals("计算器", app.name)
        assertEquals(100100520L, app.versionCode)
        assertEquals("https://cdn.example/calculator.apk?token=dynamic", app.downloadUrl)
    }

    private fun json(value: String) = LenientJson.parseToJsonElement(value) as JsonObject
}
