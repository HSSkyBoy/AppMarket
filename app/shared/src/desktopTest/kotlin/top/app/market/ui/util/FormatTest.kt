package top.app.market.ui.util

import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun todaySummaryUsesCategoryAndInstallCount() {
        val app = MarketAppInfo(
            appId = 30_755_227L,
            packageName = "com.example.beauty",
            displayName = "获奖应用",
            publisherName = "开发者",
            versionName = "1.0",
            versionCode = 1L,
            icon = "",
            apkSize = 0L,
            ratingScore = 0.0,
            source = AppSource.OPPO,
            category = "经营策略",
            downloadCount = 21_923_487L,
        )

        assertEquals("经营策略 | 2192.3万次安装", app.todayAppSummary())
    }

    @Test
    fun todaySummaryFallsBackWhenStoreMetadataIsAbsent() {
        val app = MarketAppInfo(
            appId = 1L,
            packageName = "com.example.app",
            displayName = "应用",
            publisherName = "开发者",
            versionName = "1.0",
            versionCode = 1L,
            icon = "",
            apkSize = 0L,
            ratingScore = 0.0,
        )

        assertEquals("开发者", app.todayAppSummary())
    }
}
