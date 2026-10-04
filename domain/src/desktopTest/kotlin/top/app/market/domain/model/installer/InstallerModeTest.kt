package top.app.market.domain.model.installer

import kotlin.test.Test
import kotlin.test.assertEquals

class InstallerModeTest {
    @Test
    fun migratesLegacyNonPrivilegedModesToStandard() {
        listOf(null, "", "default", "system", "unknown").forEach { key ->
            assertEquals(InstallerMode.STANDARD, InstallerMode.fromKey(key))
        }
    }

    @Test
    fun restoresPrivilegedModes() {
        assertEquals(InstallerMode.ROOT, InstallerMode.fromKey("root"))
        assertEquals(InstallerMode.SHIZUKU, InstallerMode.fromKey("shizuku"))
        assertEquals(InstallerMode.STANDARD, InstallerMode.fromKey("standard"))
    }

    @Test
    fun restoresThirdPartyAndLegacyCustomModes() {
        assertEquals(InstallerMode.THIRD_PARTY, InstallerMode.fromKey("third_party"))
        assertEquals(InstallerMode.THIRD_PARTY, InstallerMode.fromKey("custom"))
    }
}
