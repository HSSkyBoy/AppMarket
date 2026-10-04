package top.app.market.ui.navigation

import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.update.IgnoredUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IgnoredDetailRouteTest {
    @Test
    fun sourceSwitchDropsSourceOwnedIdAndUsesCurrentSource() {
        val route = ignoredDetailRoute(ignored(AppSource.XIAOMI), AppSource.SAMSUNG)

        assertEquals(0L, route.appId)
        assertEquals(AppSource.SAMSUNG, route.source)
        assertEquals("id=com.example.app", route.externalQuery)
    }

    @Test
    fun unchangedSourceKeepsKnownId() {
        val route = ignoredDetailRoute(ignored(AppSource.OPPO), AppSource.OPPO)

        assertEquals(42L, route.appId)
        assertEquals(AppSource.OPPO, route.source)
        assertNull(route.externalQuery)
    }

    private fun ignored(source: AppSource?) = IgnoredUpdate(
        appId = 42L,
        packageName = "com.example.app",
        displayName = "Example",
        versionName = "2.0",
        versionCode = 2L,
        icon = "",
        isSystemApp = false,
        source = source,
    )
}
