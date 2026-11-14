package top.app.market.data.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateVersionCompareTest {

    @Test
    fun testIsNewerVersion() {
        // 遠端更新
        assertTrue(AppUpdateRepositoryImpl.isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(AppUpdateRepositoryImpl.isNewerVersion("1.1.0", "1.0.9"))
        assertTrue(AppUpdateRepositoryImpl.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(AppUpdateRepositoryImpl.isNewerVersion("v1.2.0", "1.1.0"))
        assertTrue(AppUpdateRepositoryImpl.isNewerVersion("1.2.0", "v1.1.0"))
        assertTrue(AppUpdateRepositoryImpl.isNewerVersion("v1.2.0", "v1.1.0"))
        assertTrue(AppUpdateRepositoryImpl.isNewerVersion("1.2.3.4", "1.2.3"))

        // 相同版本
        assertFalse(AppUpdateRepositoryImpl.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(AppUpdateRepositoryImpl.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AppUpdateRepositoryImpl.isNewerVersion("1.0", "1.0.0"))

        // 本地更新 (例如本地開發版)
        assertFalse(AppUpdateRepositoryImpl.isNewerVersion("1.0.0", "1.0.1"))
        assertFalse(AppUpdateRepositoryImpl.isNewerVersion("1.0.0", "2.0.0"))
    }
}
