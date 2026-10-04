package top.app.market.domain.model.installer

data class SavedPackage(
    val id: String,
    val fileName: String,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val size: Long,
    val modifiedAt: Long,
    val artifacts: List<SavedPackageArtifact>,
    val icon: String = "",
)

data class SavedPackageArtifact(
    val uri: String,
    val fileName: String,
    val size: Long,
)
