package top.app.market.data.remote.samsung

import top.app.market.domain.model.profile.MarketProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SamsungProtocolTest {
    @Test
    fun writesEditableRequestValuesIntoEnvelope() {
        val body = SamsungProtocol.requestBody(
            profile = MarketProfile(
                co = "US",
                la = "en",
                lo = "US",
                cpuArchitecture = "arm64-v8a",
                device = "e3q",
                model = "SM-S9280",
                os = "Android",
                osV2 = "15",
                androidVersion = "15",
                sdk = "35",
                resolution = "1440*3120",
                densityDpi = "560",
                densityScaleFactor = "3.5",
                miuiBigVersionCode = "",
                miuiBigVersionName = "",
                osBigVersionCode = "",
                osBigVersionName = "",
                marketVersion = "",
                pageConfigVersion = "",
                webResVersion = "",
                hybridFrameworkVersion = "",
                buildId = "AP3A.240905.015",
                instanceId = "test-installation",
                hasGMSCore = "true",
                supportedIslandVersion = "",
            ),
            region = SamsungRegionContext(
                generation = 1,
                countryUrl = "https://example.com/ods.as",
                mcc = "999",
                mnc = "88",
                csc = "TST",
                countryCode = "TST",
                lang = "test_LANG",
            ),
            id = "2040",
            name = "searchProductListEx2Notc",
            params = emptyMap(),
        )

        assertTrue("lang=\"test_LANG\"" in body)
        assertTrue("mcc=\"999\"" in body)
        assertTrue("mnc=\"88\"" in body)
        assertTrue("csc=\"TST\"" in body)
    }

    @Test
    fun parsesCountryDiscoveryAndUpstreamEntities() {
        val response = SamsungXml.parseResponse(
            """<?xml version="1.0" encoding="UTF-8"?>
                <SamsungProtocol><response id="2300" returnCode="0" startNum="1" endNum="1">
                  <errorInfo><errorString errorCode="0"/></errorInfo>
                  <list numValue="4">
                    <value name="countryURL">http://us-odc.samsungapps.com/ods.as</value>
                    <value name="MCC">310</value><value name="countryCode">USA</value>
                    <value name="description">A &amp; B</value>
                  </list>
                </response></SamsungProtocol>""".trimIndent()
        )

        assertTrue(response.isSuccess)
        assertEquals("http://us-odc.samsungapps.com/ods.as", response.lists.single()["countryURL"])
        assertEquals("A & B", response.lists.single()["description"])
    }

    @Test
    fun decodesDoubleEncodedNumericLineBreaks() {
        val response = SamsungXml.parseResponse(
            """<SamsungProtocol><response returnCode="0"><list>
              <value name="description">First&amp;amp;#13;&amp;amp;#10;Second</value>
            </list></response></SamsungProtocol>"""
        )

        assertEquals("First\r\nSecond", response.lists.single()["description"])
    }

    @Test
    fun keepsNestedTencentAttributionInItsProductRecord() {
        val response = SamsungXml.parseResponse(
            """<SamsungProtocol><response returnCode="0" endOfList="0">
              <list><value name="productID">000002181561</value><value name="GUID">com.tencent.wework</value>
                <value name="linkProductYn">1</value>
                <extList name="tencentReportField"><value name="appId">42270467</value>
                  <value name="usedApi">getRecommendADList</value></extList>
              </list></response></SamsungProtocol>"""
        )

        assertFalse(response.endOfList)
        assertEquals("getRecommendADList", response.lists.single()["usedApi"])
        assertEquals("1", response.lists.single()["linkProductYn"])
    }

    @Test
    fun productReferenceRoundTripsGenerationAndAttributes() {
        val original = SamsungProductRef(
            productId = "000002181561",
            guid = "com.tencent.wework",
            generation = 7,
            linkProduct = true,
            tencentLastInterface = "getRecommendADList",
        )

        assertEquals(original, SamsungProductRef.parse(original.toLink()))
    }

}
