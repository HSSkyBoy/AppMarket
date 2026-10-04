package top.app.market.domain.model.install

import top.app.market.domain.model.market.AppSource

data class InstallRequest(
    val id: String,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val artifacts: List<InstallArtifact>,
    val saveToDownloads: Boolean = true,
    val sourceSavedPackageId: String? = null,
    val icon: String = "",
    val marketSource: AppSource? = null,
)
