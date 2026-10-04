package top.app.market.data.remote.xiaomi.platform

internal class DesktopXiaomiDeviceIdentityDataSource : XiaomiDeviceIdentityDataSource {
    override suspend fun identity(): XiaomiDeviceIdentity = XiaomiDeviceIdentity()
}
