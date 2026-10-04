package top.app.market.viewmodel

import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdatesViewModelDeltaSizeTest {
    @Test
    fun compatibleLiveResultKeepsKnownDeltaSize() {
        val cached = app(deltaSize = 128L, displayName = "Cached")
        val live = app(deltaSize = 0L, displayName = "Live")

        val merged = preserveKnownDeltaSizes(listOf(cached), listOf(live)).single()

        assertEquals(128L, merged.deltaSize)
        assertEquals("Live", merged.displayName)
    }

    @Test
    fun liveDeltaSizeAlwaysWins() {
        val merged = preserveKnownDeltaSizes(
            previous = listOf(app(deltaSize = 128L)),
            incoming = listOf(app(deltaSize = 96L)),
        ).single()

        assertEquals(96L, merged.deltaSize)
    }

    @Test
    fun changedArtifactIdentityDoesNotReuseCachedDeltaSize() {
        val cached = app(deltaSize = 128L)
        val changed = listOf(
            app(deltaSize = 0L, versionCode = 3L),
            app(deltaSize = 0L, installedVersionCode = 2L),
            app(deltaSize = 0L, apkSize = 2048L),
            app(deltaSize = 0L, installedBaseApkPath = "/data/app/other/base.apk"),
            app(deltaSize = 0L, installedSplits = "1:[config.arm64_v8a]"),
        )

        changed.forEach { live ->
            val merged = preserveKnownDeltaSizes(listOf(cached), listOf(live)).single()
            assertEquals(0L, merged.deltaSize)
        }
    }

    @Test
    fun invalidCachedDeltaSizeIsNotReused() {
        val fullSize = 1024L
        val merged = preserveKnownDeltaSizes(
            previous = listOf(app(deltaSize = fullSize, apkSize = fullSize)),
            incoming = listOf(app(deltaSize = 0L, apkSize = fullSize)),
        ).single()

        assertEquals(0L, merged.deltaSize)
    }

    @Test
    fun deltaSizeNeverCrossesMarketSources() {
        val cached = app(deltaSize = 128L, source = AppSource.XIAOMI)
        val samsung = app(deltaSize = 0L, source = AppSource.SAMSUNG)

        val merged = preserveKnownDeltaSizes(listOf(cached), listOf(samsung)).single()

        assertEquals(0L, merged.deltaSize)
    }

    @Test
    fun samsungLiveResultCannotAdvertiseDelta() {
        val samsung = app(deltaSize = 128L, source = AppSource.SAMSUNG)

        val merged = preserveKnownDeltaSizes(emptyList(), listOf(samsung)).single()

        assertEquals(0L, merged.deltaSize)
    }

    private fun app(
        deltaSize: Long,
        displayName: String = "App",
        versionCode: Long = 2L,
        installedVersionCode: Long = 1L,
        apkSize: Long = 1024L,
        installedBaseApkPath: String = "/data/app/base.apk",
        installedSplits: String = "0",
        source: AppSource = AppSource.XIAOMI,
    ) = MarketAppInfo(
        appId = 1L,
        packageName = "com.example.app",
        displayName = displayName,
        publisherName = "Example",
        versionName = "2.0",
        versionCode = versionCode,
        icon = "",
        apkSize = apkSize,
        deltaSize = deltaSize,
        ratingScore = 0.0,
        installedVersionName = "1.0",
        installedVersionCode = installedVersionCode,
        installedOldApkHash = "0123456789abcdef",
        installedBaseApkPath = installedBaseApkPath,
        installedSplits = installedSplits,
        source = source,
    )
}
