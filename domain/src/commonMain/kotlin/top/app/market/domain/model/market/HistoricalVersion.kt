package top.app.market.domain.model.market

data class HistoricalVersion(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val icon: String,
    val versionId: Long,
    val versionName: String,
    val versionCode: Long,
    val minSdkVersion: Int,
    val downloadUrl: String,
    val size: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

data class HistoricalVersionPage(
    val items: List<HistoricalVersion>,
    val nextOffset: Int?,
)
