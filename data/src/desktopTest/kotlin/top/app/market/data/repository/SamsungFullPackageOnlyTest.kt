package top.app.market.data.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.download.DownloadPatch
import top.app.market.domain.model.market.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SamsungFullPackageOnlyTest {
    @Test
    fun stripsAnySamsungPatchCandidateAndInstalledBasePath() {
        val meta = DownloadMeta(
            appId = 1L,
            packageName = "com.example.app",
            displayName = "Example",
            versionName = "2.0",
            versionCode = 2L,
            url = "https://example.test/full.apk",
            size = 1000L,
            parts = listOf(
                DownloadPart(
                    name = "base",
                    type = "base",
                    url = "https://example.test/full.apk",
                    size = 1000L,
                    patch = DownloadPatch(
                        url = "https://example.test/patch.bin",
                        size = 100L,
                        hash = "",
                        version = 1,
                        protocol = "samsung-xdelta",
                    ),
                )
            ),
            installedBaseApkPath = "/data/app/base.apk",
            source = AppSource.SAMSUNG,
        ).samsungFullPackageOnly()

        assertEquals("", meta.installedBaseApkPath)
        assertNull(meta.parts.single().patch)
        assertEquals("https://example.test/full.apk", meta.parts.single().url)
    }
}
