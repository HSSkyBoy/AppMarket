package top.app.market.viewmodel

import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateSourceMergeTest {
    @Test
    fun keepsHighestVersionForEachPackage() {
        val xiaomi = app("a", versionCode = 10, source = AppSource.XIAOMI)
        val wandoujia = app("a", versionCode = 12, source = AppSource.WANDOUJIA)
        val second = app("b", versionCode = 3, source = AppSource.WANDOUJIA)

        val merged = mergeUpdateSources(listOf(xiaomi), listOf(wandoujia, second))

        assertEquals(listOf("a", "b"), merged.map { it.packageName })
        assertEquals(12L, merged.first().versionCode)
        assertEquals(AppSource.WANDOUJIA, merged.first().source)
    }

    private fun app(packageName: String, versionCode: Long, source: AppSource) = MarketAppInfo(
        appId = versionCode,
        packageName = packageName,
        displayName = packageName,
        publisherName = "",
        versionName = versionCode.toString(),
        versionCode = versionCode,
        icon = "",
        apkSize = 1L,
        ratingScore = 0.0,
        source = source,
    )
}
