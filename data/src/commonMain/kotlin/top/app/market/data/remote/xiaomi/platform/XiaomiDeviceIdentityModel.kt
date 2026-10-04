package top.app.market.data.remote.xiaomi.platform

data class XiaomiDeviceIdentity(
    val oaId: String = "",
    val activeTimeInterval: String = "",
    val installDay: String = "",
    val launchDay: String = "",
    val dctx: String = "",
    val xmsfVersion: String = "",
    val tzNonce: String = "",
    val tzSign: String = "",
    val debug: String = "",
)
