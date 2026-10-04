package top.app.market.data.remote.xiaomi.platform

import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.remote.xiaomi.preferences.XiaomiIdentityPreferenceKeys

internal class XiaomiIdentityStore(private val preferences: PreferencesDataSource) {

    /** Raw hardware-backed FID. It is input to TrustZone signing and must never be sent as dctx. */
    suspend fun cachedSecurityDeviceId(): String =
        preferences.read(XiaomiIdentityPreferenceKeys.SystemDeviceContext).orEmpty()

    /** Encrypted device context returned by /expId (legacy DeviceContext stores the same value). */
    suspend fun cachedServerDctx(): String =
        preferences.read(XiaomiIdentityPreferenceKeys.ServerDeviceContext)?.takeIf { it.isNotBlank() }
            ?: preferences.read(XiaomiIdentityPreferenceKeys.DeviceContext).orEmpty()

    suspend fun saveSystemDctx(value: String) {
        if (value.isNotBlank()) preferences.put(XiaomiIdentityPreferenceKeys.SystemDeviceContext, value)
    }
}
