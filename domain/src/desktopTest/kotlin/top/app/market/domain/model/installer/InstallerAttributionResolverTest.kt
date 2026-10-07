package top.app.market.domain.model.installer

import top.app.market.domain.model.market.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallerAttributionResolverTest {

    @Test
    fun autoBySourceResolvesVendorStores() {
        assertEquals(
            StorePackageNames.XIAOMI,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, AppSource.XIAOMI),
        )
        assertEquals(
            StorePackageNames.VIVO,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, AppSource.VIVO),
        )
        assertEquals(
            StorePackageNames.OPPO,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, AppSource.OPPO),
        )
        assertEquals(
            StorePackageNames.HUAWEI,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, AppSource.HUAWEI),
        )
        assertEquals(
            StorePackageNames.HONOR,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, AppSource.HONOR),
        )
        assertEquals(
            StorePackageNames.SAMSUNG,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, AppSource.SAMSUNG),
        )
    }

    @Test
    fun autoBySourceResolvesNonVendorAndNullToGooglePlay() {
        assertEquals(
            StorePackageNames.GOOGLE_PLAY,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, AppSource.TAPTAP),
        )
        assertEquals(
            StorePackageNames.GOOGLE_PLAY,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, AppSource.WANDOUJIA),
        )
        assertEquals(
            StorePackageNames.GOOGLE_PLAY,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, AppSource.BOX7723),
        )
        assertEquals(
            StorePackageNames.GOOGLE_PLAY,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.AUTO_BY_SOURCE, null),
        )
    }

    @Test
    fun googlePlayModeAlwaysReturnsVending() {
        assertEquals(
            StorePackageNames.GOOGLE_PLAY,
            InstallerAttributionResolver.resolve(InstallerAttributionMode.GOOGLE_PLAY, AppSource.XIAOMI),
        )
    }

    @Test
    fun customModeReturnsTrimmedOrFallback() {
        assertEquals(
            "com.custom.installer",
            InstallerAttributionResolver.resolve(
                InstallerAttributionMode.CUSTOM,
                AppSource.XIAOMI,
                customPackage = "  com.custom.installer  ",
                fallbackPackageName = "top.app.market",
            ),
        )
        assertEquals(
            "top.app.market",
            InstallerAttributionResolver.resolve(
                InstallerAttributionMode.CUSTOM,
                AppSource.XIAOMI,
                customPackage = "   ",
                fallbackPackageName = "top.app.market",
            ),
        )
    }

    @Test
    fun noneModeReturnsFallback() {
        assertEquals(
            "top.app.market",
            InstallerAttributionResolver.resolve(
                InstallerAttributionMode.NONE,
                AppSource.XIAOMI,
                fallbackPackageName = "top.app.market",
            ),
        )
    }
}
