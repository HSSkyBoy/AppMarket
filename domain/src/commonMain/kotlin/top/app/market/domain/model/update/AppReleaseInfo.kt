package top.app.market.domain.model.update

data class AppReleaseInfo(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val mirrorDownloadUrl: String,
    val htmlUrl: String,
)
