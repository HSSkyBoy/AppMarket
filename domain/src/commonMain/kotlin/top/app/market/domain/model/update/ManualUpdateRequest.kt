package top.app.market.domain.model.update

data class ManualUpdateRequest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String = "",
    val isSystemApp: Boolean = true,
    val oldApkHash: String = "0",
    val splits: String = "0",
    val apkSource: String = "0",
    val installedBy: String = "0",
)
