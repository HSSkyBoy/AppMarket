package top.app.market.data.remote.xiaomi

import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.remote.xiaomi.preferences.UpdateInfoPreferenceKeys
import kotlinx.serialization.json.JsonObject

internal class UpdateInfoCache(private val preferences: PreferencesDataSource) {
    private companion object {
        const val DEFAULT_INVALID_SYSTEM_PACKAGE_HASH = "null"
    }

    suspend fun invalidSystemPackageHash(): String =
        preferences.read(UpdateInfoPreferenceKeys.InvalidSystemPackageHash)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_INVALID_SYSTEM_PACKAGE_HASH

    suspend fun updateInvalidSystemPackageHash(json: JsonObject) {
        val hash = json.str("invalidSystemPackageHash").takeIf { it.isNotBlank() } ?: return
        preferences.put(UpdateInfoPreferenceKeys.InvalidSystemPackageHash, hash)
    }

}
