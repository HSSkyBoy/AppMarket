package com.app.market.data.remote.oppo

import com.app.market.domain.model.profile.MarketProfile
import com.app.market.domain.model.profile.OppoRequestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OppoSignerTest {
    @Test
    fun anonymousOpenIdHasOfficialShapeAndIsStablePerInstance() {
        val firstProfile = profile("3f70e9e4-a1df-4b75-9914-8e6d59856069")
        val secondProfile = profile("8d8bb226-339f-4855-b8b7-183f2052f9fc")

        val first = OppoSigner.openId(firstProfile)

        assertTrue(first.matches(Regex("^/[0-9a-f]{64}/[0-9a-f]{64}/[0-9a-f]{64}$")))
        assertEquals(first, OppoSigner.openId(firstProfile))
        assertNotEquals(first, OppoSigner.openId(secondProfile))
        assertEquals(first, OppoSigner.headers("GET", "https://example.com/detail", firstProfile).values["id"])
    }

    @Test
    fun nativeSignAndSecondarySignVectorsRemainStable() {
        val input =
            "ocs%2Fsome%3Fq%3Dfoo+barapplication/x2-protostuff; charset=UTF-8" +
                    "post111111111111111///1700000000000"

        val sign = OppoSigner.sign(input)

        assertEquals("bf5f67d2d1ee95bf29f3eb9dcacb7d37", sign)
        assertEquals(
            "2c783561af9e0b3c5e54022597566c5188b0a4eb",
            OppoSigner.secondarySign(input.lowercase(), sign),
        )
    }

    @Test
    fun realmeProfileKeepsDisplayRomAndFirmwareRomSeparate() {
        val headers = OppoSigner.headers(
            "POST",
            "https://api-store-gl.heytapmobile.com/update/global/v1/check",
            profile("3f70e9e4-a1df-4b75-9914-8e6d59856069"),
        ).values

        assertEquals(
            "realme%2FRMX5062%2F36%2F16%2FV16.1.0%2F210%2F4101%2F122280%2F12.22.80",
            headers["User-Agent"],
        )
        assertEquals(
            "realme%2FRMX5062%2F36%2F16%2FV16.1.0%2F210%2FRMX5062_16.0.8.301%28CN01%29%2F122280",
            headers["ocs"],
        )
    }

    @Test
    fun defaultRequestContextAddsTheOfficialChinaRegionContext() {
        val profile = profile("3f70e9e4-a1df-4b75-9914-8e6d59856069")
        val china = OppoSigner.headers("GET", "https://example.com/detail", profile).values

        assertEquals("CN", china["user-region"])
        assertEquals("zh-CN", china["system-locale"])
        assertEquals("zh-CN", china["supported-locales"])
        assertEquals("zh-CN;CN", china["locale"])
    }

    @Test
    fun editableRequestContextOverridesTheDefaults() {
        val custom = OppoRequestContext(
            userRegion = "SG",
            systemLocale = "en-SG",
            supportedLocales = "en-SG,zh-CN",
            locale = "en-SG;SG",
        )
        val headers = OppoSigner.headers(
            "GET",
            "https://example.com/detail",
            profile("3f70e9e4-a1df-4b75-9914-8e6d59856069"),
            custom,
        ).values

        assertEquals(custom.userRegion, headers["user-region"])
        assertEquals(custom.systemLocale, headers["system-locale"])
        assertEquals(custom.supportedLocales, headers["supported-locales"])
        assertEquals(custom.locale, headers["locale"])
    }

    private fun profile(instanceId: String) = MarketProfile(
        co = "MO", la = "zh", lo = "MO", cpuArchitecture = "arm64-v8a",
        device = "RMX5062", model = "RMX5062", os = "16.0.8.301", osV2 = "V16.1.0",
        androidVersion = "16", sdk = "36", resolution = "1272*2800", densityDpi = "568",
        densityScaleFactor = "3.55", miuiBigVersionCode = "", miuiBigVersionName = "",
        osBigVersionCode = "16", osBigVersionName = "V16.1.0", marketVersion = "122280",
        pageConfigVersion = "", webResVersion = "", hybridFrameworkVersion = "",
        buildId = "BP2A.250605.015", instanceId = instanceId, hasGMSCore = "true",
        supportedIslandVersion = "1",
    )
}
