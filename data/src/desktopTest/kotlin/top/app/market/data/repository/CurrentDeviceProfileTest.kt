package top.app.market.data.repository

import top.app.market.data.remote.xiaomi.platform.DeviceDefaults
import top.app.market.data.remote.xiaomi.platform.isOppoFamilyDevice
import top.app.market.data.remote.xiaomi.platform.isSamsungFamilyDevice
import top.app.market.data.remote.xiaomi.platform.isVivoFamilyDevice
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.profile.ProfileSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrentDeviceProfileTest {
    @Test
    fun compatibleAndroidSourcesDefaultToTheCurrentDevice() {
        assertEquals(ProfileSource.PRESET, defaultProfileSource(AppSource.OPPO, deviceAvailable = true))
        assertEquals(ProfileSource.DEVICE, defaultProfileSource(AppSource.HONOR, deviceAvailable = true))
        assertEquals(ProfileSource.DEVICE, defaultProfileSource(AppSource.XIAOMI, deviceAvailable = true))
        assertEquals(ProfileSource.PRESET, defaultProfileSource(AppSource.XIAOMI, deviceAvailable = false))
    }

    @Test
    fun honorUsesOnlyACompleteHonorRuntime() {
        val android = defaults(
            isHonorFamily = true,
            isHonorComplete = true,
            honorAndroidId = "app-scoped-id",
        )

        assertTrue(canUseCurrentDevice(AppSource.HONOR, android))
        assertFalse(canUseCurrentDevice(AppSource.HONOR, android.copy(honorAndroidId = "")))
        assertFalse(canUseCurrentDevice(AppSource.HONOR, android.copy(isHonorFamily = false)))
    }

    @Test
    fun presetSourceDoesNotLeakDeviceBackedValues() {
        assertEquals(
            "arm64-v8a",
            selectProfileField(
                presetValue = "arm64-v8a",
                deviceValue = "x86_64,arm64-v8a,x86",
                useDevice = false,
                appLevel = false,
                deviceBacked = true,
            ),
        )
        assertEquals(
            "x86_64,arm64-v8a,x86",
            selectProfileField(
                presetValue = "arm64-v8a",
                deviceValue = "x86_64,arm64-v8a,x86",
                useDevice = true,
                appLevel = false,
                deviceBacked = true,
            ),
        )
    }

    @Test
    fun oppoSourceAcceptsOnlyCompleteOppoFamilyDevices() {
        val oppo = defaults(isOppoFamily = true, isOppoComplete = true)

        assertTrue(canUseCurrentDevice(AppSource.OPPO, oppo))
        assertFalse(canUseCurrentDevice(AppSource.XIAOMI, oppo))
        assertFalse(canUseCurrentDevice(AppSource.VIVO, oppo))
        assertFalse(canUseCurrentDevice(AppSource.WANDOUJIA, oppo))
        assertFalse(canUseCurrentDevice(AppSource.OPPO, oppo.copy(isOppoComplete = false)))
    }

    @Test
    fun oppoOnePlusAndRealmeBrandsAreRecognizedCaseInsensitively() {
        assertTrue(isOppoFamilyDevice("OPPO", "OPPO"))
        assertTrue(isOppoFamilyDevice("OnePlus", "OnePlus"))
        assertTrue(isOppoFamilyDevice("realme", "realme"))
        assertTrue(isOppoFamilyDevice("unknown", "REALME"))
        assertFalse(isOppoFamilyDevice("Xiaomi", "Redmi"))
    }

    @Test
    fun vivoAndIqooDevicesCanUseTheVivoProfile() {
        assertTrue(isVivoFamilyDevice("vivo", "vivo", "PD2241"))
        assertTrue(isVivoFamilyDevice("iQOO", "iQOO", "I2401"))
        assertFalse(isVivoFamilyDevice("Xiaomi", "Redmi", "popsicle"))
        val vivo = defaults(isVivoFamily = true, isVivoComplete = true)
        assertTrue(canUseCurrentDevice(AppSource.VIVO, vivo))
        assertFalse(canUseCurrentDevice(AppSource.OPPO, vivo))
    }

    @Test
    fun vivoPresetMatchesCurrentTemplateOneWithoutXiaomiProtocolValues() {
        val profile = vivoPresetProfile(defaults(), "instance-id")

        assertEquals("PD2408", profile.device)
        assertEquals("V2408A", profile.model)
        assertEquals("compiler260710223223", profile.os)
        assertEquals("compiler260710223223", profile.osV2)
        assertEquals("16", profile.androidVersion)
        assertEquals("36", profile.sdk)
        assertEquals("1440*2560", profile.resolution)
        assertEquals("640", profile.densityDpi)
        assertEquals("4.0", profile.densityScaleFactor)
        assertEquals("61510", profile.marketVersion)
        assertEquals("18411801", profile.pageConfigVersion)
        assertEquals("3211", profile.webResVersion)
        assertEquals("V417IR", profile.buildId)
        assertEquals("instance-id", profile.instanceId)
        assertEquals("true", profile.hasGMSCore)
        assertEquals("", profile.miuiBigVersionCode)
        assertEquals("", profile.miuiBigVersionName)
        assertEquals("", profile.osBigVersionCode)
        assertEquals("", profile.osBigVersionName)
        assertEquals("", profile.hybridFrameworkVersion)
        assertEquals("", profile.supportedIslandVersion)
    }

    @Test
    fun samsungSourceAcceptsOnlyCompleteSamsungDevices() {
        assertTrue(isSamsungFamilyDevice("SAMSUNG", "samsung"))
        assertFalse(isSamsungFamilyDevice("Xiaomi", "Redmi"))
        val samsung = defaults(isSamsungFamily = true, isSamsungComplete = true)
        assertTrue(canUseCurrentDevice(AppSource.SAMSUNG, samsung))
        assertFalse(canUseCurrentDevice(AppSource.OPPO, samsung))
        assertFalse(canUseCurrentDevice(AppSource.SAMSUNG, samsung.copy(isSamsungComplete = false)))
    }

    private fun defaults(
        isOppoFamily: Boolean = false,
        isOppoComplete: Boolean = false,
        isVivoFamily: Boolean = false,
        isVivoComplete: Boolean = false,
        isSamsungFamily: Boolean = false,
        isSamsungComplete: Boolean = false,
        isHonorFamily: Boolean = false,
        isHonorComplete: Boolean = false,
        honorAndroidId: String = "",
    ) = DeviceDefaults(
        cpuArchitecture = "arm64-v8a",
        device = "device",
        model = "model",
        androidVersion = "16",
        sdk = "36",
        language = "zh",
        isOppoFamily = isOppoFamily,
        isOppoComplete = isOppoComplete,
        isVivoFamily = isVivoFamily,
        isVivoComplete = isVivoComplete,
        isSamsungFamily = isSamsungFamily,
        isSamsungComplete = isSamsungComplete,
        isHonorFamily = isHonorFamily,
        isHonorComplete = isHonorComplete,
        honorAndroidId = honorAndroidId,
    )
}
