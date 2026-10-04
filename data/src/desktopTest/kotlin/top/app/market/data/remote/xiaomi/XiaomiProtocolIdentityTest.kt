package top.app.market.data.remote.xiaomi

import top.app.market.data.local.BooleanPreferenceKey
import top.app.market.data.local.PreferenceChanges
import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.local.StringPreferenceKey
import top.app.market.data.remote.xiaomi.platform.XiaomiIdentityStore
import top.app.market.data.remote.xiaomi.preferences.XiaomiIdentityPreferenceKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XiaomiProtocolIdentityTest {

    @Test
    fun protocolAndDownloadHeadersUseTheSameOfficialBaseline() {
        assertEquals("4.121.s.00", XiaomiProtocol.VERSION_NAME)
        assertEquals("40007460", XiaomiProtocol.VERSION_CODE)

        val headers = XiaomiClient().downloadHeaders("detail")
        assertEquals("detail", headers["ref"])
        assertFalse(headers.keys.any { it.startsWith("x-", ignoreCase = true) })
    }

    @Test
    fun rawSecurityIdIsNeverReturnedAsServerDctx() = runBlocking {
        val preferences = MemoryPreferences()
        preferences.put(XiaomiIdentityPreferenceKeys.SystemDeviceContext, "raw-fid")
        preferences.put(XiaomiIdentityPreferenceKeys.ServerDeviceContext, "encrypted-dctx")
        val store = XiaomiIdentityStore(preferences)

        assertEquals("raw-fid", store.cachedSecurityDeviceId())
        assertEquals("encrypted-dctx", store.cachedServerDctx())
    }
}

private class MemoryPreferences : PreferencesDataSource {
    private val strings = mutableMapOf<StringPreferenceKey, MutableStateFlow<String?>>()
    private val booleans = mutableMapOf<BooleanPreferenceKey, MutableStateFlow<Boolean>>()

    override fun observe(key: StringPreferenceKey): Flow<String?> =
        strings.getOrPut(key) { MutableStateFlow(null) }

    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> =
        booleans.getOrPut(key) { MutableStateFlow(key.default) }

    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.requireNamespace(namespace)
        changes.strings.forEach { (key, value) -> strings.getOrPut(key) { MutableStateFlow(null) }.value = value }
        changes.booleans.forEach { (key, value) ->
            booleans.getOrPut(key) { MutableStateFlow(key.default) }.value = value ?: key.default
        }
    }
}
