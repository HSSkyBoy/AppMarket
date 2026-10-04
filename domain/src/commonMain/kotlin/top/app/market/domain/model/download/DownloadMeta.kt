package top.app.market.domain.model.download

import top.app.market.domain.model.market.AppSource

data class DownloadMeta(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val url: String,
    val size: Long,
    val parts: List<DownloadPart> = listOf(DownloadPart("", "base", url, size)),
    val installedBaseApkPath: String = "",
    val icon: String = "",
    val changeLog: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val source: AppSource = AppSource.XIAOMI,
)
