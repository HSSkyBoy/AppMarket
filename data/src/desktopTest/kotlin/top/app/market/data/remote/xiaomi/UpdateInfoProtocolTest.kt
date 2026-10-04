package top.app.market.data.remote.xiaomi

import top.app.market.domain.model.installed.InstalledPackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UpdateInfoProtocolTest {
    @Test
    fun missingCoreAddsZeroVersionAnchor() {
        val packages = withMiuiUpdateAnchor(listOf(pkg("target.app", versionCode = 42L)))

        assertEquals(listOf("target.app", MIUI_UPDATE_ANCHOR_PACKAGE), packages.map { it.packageName })
        assertEquals(0L, packages.last().versionCode)
        assertEquals(true, packages.last().isSystemApp)
    }

    @Test
    fun realCoreMetadataWinsOverAnchor() {
        val core = pkg(MIUI_UPDATE_ANCHOR_PACKAGE, versionCode = 1_202_532L, installedBy = "1")

        val packages = withMiuiUpdateAnchor(listOf(pkg("target.app", 42L), core))

        assertEquals(2, packages.size)
        assertEquals(core, packages.last())
    }

    @Test
    fun requestFieldsKeepPackageColumnsAligned() {
        val packages = withMiuiUpdateAnchor(
            listOf(
                pkg(
                    packageName = "target.app",
                    versionCode = 42L,
                    installedBy = "1",
                    splits = "1:[feature]",
                    oldApkHash = "abc",
                    apkSource = "store",
                )
            )
        )

        val fields = updateInfoRequestFields(
            common = mapOf("device" to "popsicle", "tzNonce" to "nonce", "tzSign" to "sign"),
            androidVersion = "16",
            instanceId = "instance-",
            packages = packages,
            invalidSystemPackageHash = "",
            timestamp = 123L,
        )

        assertEquals("target.app,com.miui.core", fields["packageName"])
        assertEquals("42,0", fields["versionCode"])
        assertEquals("1,0", fields["installedByMarket"])
        assertEquals("1:[feature],0", fields["splits"])
        assertEquals("abc,0", fields["oldApkHash"])
        assertEquals("store,0", fields["apkSource"])
        assertEquals("instance-123", fields["session_id"])
        assertEquals("null", fields["invalidSystemPackageHash"])
        assertEquals("update", fields["ref"])
        assertEquals("popsicle", fields["device"])
        assertFalse("tzNonce" in fields)
        assertFalse("tzSign" in fields)
    }

    private fun pkg(
        packageName: String,
        versionCode: Long,
        installedBy: String = "0",
        splits: String = "0",
        oldApkHash: String = "0",
        apkSource: String = "0",
    ) = InstalledPackage(
        packageName = packageName,
        versionCode = versionCode,
        isSystemApp = false,
        installedBy = installedBy,
        splits = splits,
        oldApkHash = oldApkHash,
        apkSource = apkSource,
    )
}
