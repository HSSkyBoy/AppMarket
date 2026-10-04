package top.app.market.ui.model

import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.isDownloadBlocked
import top.app.market.domain.model.market.isReservation

enum class AppActionKind {
    INSTALL,
    UPDATE,
    OPEN,
    RESERVE,
}

/** Primary action for an app given its installed version code (0 = not installed) and the store's. */
fun resolveActionKind(installedVersionCode: Long, storeVersionCode: Long): AppActionKind = when {
    installedVersionCode <= 0L -> AppActionKind.INSTALL
    installedVersionCode < storeVersionCode -> AppActionKind.UPDATE
    else -> AppActionKind.OPEN
}

fun MarketAppInfo.actionKind(): AppActionKind = when {
    isReservation() -> AppActionKind.RESERVE
    installedVersionCode > 0L && isDownloadBlocked() -> AppActionKind.OPEN
    else -> resolveActionKind(installedVersionCode, versionCode)
}
