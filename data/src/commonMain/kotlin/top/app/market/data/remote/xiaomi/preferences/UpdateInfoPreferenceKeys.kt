package top.app.market.data.remote.xiaomi.preferences

import top.app.market.data.local.StringPreferenceKey

object UpdateInfoPreferenceKeys {
    private const val NS = "market_update_info"
    val InvalidSystemPackageHash = StringPreferenceKey(NS, "invalid_system_package_hash")
}
