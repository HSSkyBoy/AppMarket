package top.app.market.data.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDownloadDataSourceTest {
    @Test
    fun singlePackageKeepsUnicodeDisplayNameAndApkSuffix() {
        val meta = downloadMeta(displayName = "小米应用商店", versionName = "4.120.1")

        assertEquals(listOf("小米应用商店-4.120.1.apk"), desktopDownloadFileNames(meta))
    }

    @Test
    fun splitPackageUsesStableSessionFileNames() {
        val parts = listOf(
            DownloadPart("base", "base", "https://example.com/base", 1L),
            DownloadPart("config.arm64_v8a", "config", "https://example.com/split", 1L),
        )
        val meta = downloadMeta(parts = parts)

        assertEquals(
            listOf("base.apk", "1_config.arm64_v8a_config.apk"),
            desktopDownloadFileNames(meta),
        )
    }

    private fun downloadMeta(
        displayName: String = "App",
        versionName: String = "1.0",
        parts: List<DownloadPart> = listOf(
            DownloadPart("base", "base", "https://example.com/base", 1L)
        ),
    ) = DownloadMeta(
        appId = 1L,
        packageName = "com.example.app",
        displayName = displayName,
        versionName = versionName,
        versionCode = 1L,
        url = parts.first().url,
        size = parts.sumOf(DownloadPart::size),
        parts = parts,
    )
}
