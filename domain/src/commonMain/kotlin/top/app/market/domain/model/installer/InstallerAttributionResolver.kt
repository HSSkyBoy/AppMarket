package top.app.market.domain.model.installer

import top.app.market.domain.model.market.AppSource

object StorePackageNames {
    const val GOOGLE_PLAY = "com.android.vending"
    const val XIAOMI = "com.xiaomi.market"
    const val VIVO = "com.bbk.appstore"
    const val OPPO = "com.heytap.market"
    const val HUAWEI = "com.huawei.appmarket"
    const val HONOR = "com.hihonor.appmarket"
    const val SAMSUNG = "com.sec.android.app.samsungapps"

    /**
     * 根据应用来源推断对应的官方商店包名。
     */
    fun fromAppSource(source: AppSource?): String = when (source) {
        AppSource.XIAOMI -> XIAOMI
        AppSource.VIVO -> VIVO
        AppSource.OPPO -> OPPO
        AppSource.HUAWEI -> HUAWEI
        AppSource.HONOR -> HONOR
        AppSource.SAMSUNG -> SAMSUNG
        AppSource.TAPTAP,
        AppSource.WANDOUJIA,
        AppSource.BOX7723,
        null -> GOOGLE_PLAY
    }
}

object InstallerAttributionResolver {
    fun resolve(
        mode: InstallerAttributionMode,
        marketSource: AppSource?,
        customPackage: String = "",
        fallbackPackageName: String = "",
    ): String = when (mode) {
        InstallerAttributionMode.AUTO_BY_SOURCE -> StorePackageNames.fromAppSource(marketSource)
        InstallerAttributionMode.GOOGLE_PLAY -> StorePackageNames.GOOGLE_PLAY
        InstallerAttributionMode.CUSTOM -> customPackage.trim().ifBlank { fallbackPackageName }
        InstallerAttributionMode.NONE -> fallbackPackageName
    }
}
