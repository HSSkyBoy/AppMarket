package top.app.market.data.remote.xiaomi.preferences

import top.app.market.data.local.StringPreferenceKey

object XiaomiIdentityPreferenceKeys {
    private const val NS = "xiaomi_identity"
    val DeviceContext = StringPreferenceKey(NS, "dctx")
    val SystemDeviceContext = StringPreferenceKey(NS, "system_dctx")
    val ServerDeviceContext = StringPreferenceKey(NS, "server_dctx")
    val ExperimentId = StringPreferenceKey(NS, "exp_id")
    val TrustZoneSign = StringPreferenceKey(NS, "tz_sign")
    val TrustZoneSignCreateTime = StringPreferenceKey(NS, "tz_sign_create_time")
    val FallbackOaIdSeed = StringPreferenceKey(NS, "fallback_oaid_seed")
}
