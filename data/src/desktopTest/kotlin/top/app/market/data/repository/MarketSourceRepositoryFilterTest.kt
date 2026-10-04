package top.app.market.data.repository

import top.app.market.domain.model.market.MarketAppInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketSourceRepositoryFilterTest {
    @Test
    fun sameDeveloperCandidatesExcludeCurrentPackageAcrossDifferentStoreIds() {
        val candidates = listOf(
            app(100L, "com.tencent.mm"),
            app(101L, "com.tencent.wework"),
            app(102L, "COM.TENCENT.WEWORK"),
        )

        val filtered = filterSameDeveloperCandidates("COM.TENCENT.MM", candidates)

        assertEquals(listOf("com.tencent.wework"), filtered.map(MarketAppInfo::packageName))
    }

    private fun app(id: Long, packageName: String) = MarketAppInfo(
        appId = id,
        packageName = packageName,
        displayName = packageName,
        publisherName = "Tencent",
        versionName = "1.0",
        versionCode = 1L,
        icon = "",
        apkSize = 1L,
        ratingScore = 5.0,
    )
}
