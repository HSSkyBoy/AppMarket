package top.app.market.data.remote.honor

import top.app.market.domain.model.profile.MarketProfile
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HonorProtocolTerminalTest {
    @Test
    fun nativeTerminalUsesOfficialNormalUserWireValues() {
        val protocol = HonorProtocol(HttpClient())
        val terminal = protocol.terminal(profile(), 123L)

        assertEquals(-1, terminal.getValue("activationFlag").toString().toInt())
        assertEquals(3, terminal.getValue("ageLimit").toString().toInt())
        assertEquals("\"cn\"", terminal.getValue("deliveryCountry").toString())
        assertEquals(1, terminal.getValue("isParallelSpace").toString().toInt())
        assertEquals(0, terminal.getValue("os").toString().toInt())
        assertEquals("\"10.0.0\"", terminal.getValue("magicVersion").toString())
        assertEquals("\"${honorUdid(profile())}\"", terminal.getValue("udid").toString())
        assertEquals("\"0\"", terminal.getValue("userType").toString())
    }

    @Test
    fun searchTerminalKeepsTheSameNativeDeviceClassification() {
        val protocol = HonorProtocol(HttpClient())
        val terminal = protocol.searchTerminal(profile(), 123L, "/api/market/search/v1/search/result/page")

        assertEquals(-1, terminal.getValue("activationFlag").toString().toInt())
        assertEquals(3, terminal.getValue("ageLimit").toString().toInt())
        assertEquals("\"cn\"", terminal.getValue("deliveryCountry").toString())
        assertEquals(1, terminal.getValue("isParallelSpace").toString().toInt())
        assertEquals(0, terminal.getValue("os").toString().toInt())
        assertEquals("\"10.0.0\"", terminal.getValue("magicVersion").toString())
        assertEquals("\"${honorUdid(profile())}\"", terminal.getValue("udid").toString())
        assertEquals("\"0\"", terminal.getValue("userType").toString())
    }

    @Test
    fun anonymousUdidIsStableAndSeparatedFromAndroidId() {
        val profile = profile()
        val first = honorUdid(profile)

        assertEquals(first, honorUdid(profile))
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertNotEquals(first, honorAndroidId(profile))
    }

    @Test
    fun magicVersionMatchesOfficialThreePartNormalization() {
        assertEquals("10.0.0", honorSysVersion(profile()))
        assertEquals("9.0.0", honorSysVersion(profile().copy(magicVersion = "9")))
        assertEquals("8.1.0", honorSysVersion(profile().copy(magicVersion = "8.1")))
        assertEquals("7.2.3", honorSysVersion(profile().copy(magicVersion = "7.2.3.4")))
    }

    @Test
    fun vendorCountryIsLowercaseWhileAreaIdStaysUppercase() {
        assertEquals("cn", honorDeliveryCountry(profile()))
        assertEquals("CN", honorAreaId(profile()))
        assertEquals("cn", honorDeliveryCountry(profile().copy(deliveryCountry = "cn")))
        assertEquals("CN", honorAreaId(profile().copy(deliveryCountry = "cn")))
        assertEquals("1", honorUserType(profile().copy(honorUserType = "1")))
    }

    private fun profile() = MarketProfile(
        co = "CN",
        la = "zh",
        lo = "CN",
        cpuArchitecture = "arm64-v8a",
        device = "example-device",
        model = "Example model",
        os = "16",
        osV2 = "MagicOS_10.0.0",
        androidVersion = "16",
        sdk = "36",
        resolution = "1264*2800",
        densityDpi = "560",
        densityScaleFactor = "3.5",
        miuiBigVersionCode = "",
        miuiBigVersionName = "",
        osBigVersionCode = "10",
        osBigVersionName = "MagicOS 10.0.0",
        marketVersion = "160107301",
        pageConfigVersion = "",
        webResVersion = "",
        hybridFrameworkVersion = "",
        buildId = "example-build",
        instanceId = "11111111-2222-3333-4444-555555555555",
        hasGMSCore = "false",
        supportedIslandVersion = "",
        hman = "HONOR",
        htype = "example-device",
        spreadModelName = "Example model",
        deliveryCountry = "CN",
        roamingCountry = "CN",
        osVer = "16",
        magicVersion = "MagicOS_10.0.0",
        androidApiVersion = "36",
        apkVer = "160107301",
        apkVerName = "16.1.7.301",
        language = "zh_CN",
        dpi = "560",
        cpu = "arm64-v8a",
        supportGms = "0",
        terminalType = "1",
        honorDeviceMode = "1",
        honorIsParallelSpace = "1",
        honorUserType = "0",
    )
}
