package top.app.market.data.remote.xiaomi.platform

internal interface XiaomiDeviceIdentityDataSource {
    suspend fun identity(): XiaomiDeviceIdentity
}
