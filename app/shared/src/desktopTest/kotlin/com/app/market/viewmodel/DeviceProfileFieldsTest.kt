package com.app.market.viewmodel

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.DefaultSamsungRequestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceProfileFieldsTest {
    @Test
    fun oppoProfileHidesXiaomiOnlyProtocolFields() {
        val fields = DeviceProfileViewModel.fieldsFor(AppSource.OPPO)

        listOf(
            "la", "lo", "cpuArchitecture", "densityDpi", "densityScaleFactor",
            "miuiBigVersionCode", "miuiBigVersionName", "osBigVersionCode", "marketVersion",
            "pageConfigVersion", "webResVersion", "hybridFrameworkVersion", "hasGMSCore",
            "supportedIslandVersion",
        ).forEach { assertFalse(it in fields, "$it must not be shown for OPPO") }
        assertEquals(
            listOf(
                "co", "device", "model", "os", "osV2", "androidVersion", "sdk", "resolution",
                "osBigVersionName", "buildId", "instanceId",
            ),
            fields,
        )
        assertEquals(DeviceProfileViewModel.FIELDS, DeviceProfileViewModel.fieldsFor(AppSource.XIAOMI))
    }

    @Test
    fun vivoProfileShowsOnlyVivoRelevantValues() {
        val fields = DeviceProfileViewModel.fieldsFor(AppSource.VIVO)

        listOf(
            "miuiBigVersionCode", "miuiBigVersionName", "osBigVersionCode", "osBigVersionName",
            "pageConfigVersion", "webResVersion", "hybridFrameworkVersion", "supportedIslandVersion",
        ).forEach { assertFalse(it in fields, "$it must not be shown for vivo") }
        assertEquals(
            listOf(
                "co", "la", "lo", "cpuArchitecture", "device", "model", "os", "osV2",
                "androidVersion", "sdk", "resolution", "densityDpi", "densityScaleFactor",
                "marketVersion", "buildId", "instanceId", "hasGMSCore",
            ),
            fields,
        )
    }

    @Test
    fun samsungProfileShowsOnlyOdcDeviceValues() {
        assertEquals(
            listOf(
                "cpuArchitecture", "model", "sdk", "instanceId",
            ),
            DeviceProfileViewModel.fieldsFor(AppSource.SAMSUNG),
        )
    }

    @Test
    fun samsungRequestValuesChangeProfileSourceToCustom() {
        val defaults = DefaultSamsungRequestContext

        assertFalse(DeviceProfileViewModel.hasCustomSamsungRequestContext(defaults))
        listOf(
            defaults.copy(countryCode = "TST"),
            defaults.copy(language = "test_LANG"),
            defaults.copy(mcc = "999"),
            defaults.copy(mnc = "88"),
            defaults.copy(csc = "TSC"),
        ).forEach { context ->
            assertTrue(DeviceProfileViewModel.hasCustomSamsungRequestContext(context))
        }
    }
}
