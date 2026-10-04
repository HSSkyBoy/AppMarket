package top.app.market.domain.model.download

data class DownloadTaskKey(
    val packageName: String,
    val versionCode: Long,
)

val DownloadMeta.taskKey: DownloadTaskKey
    get() = DownloadTaskKey(packageName, versionCode)

val DownloadState.taskKey: DownloadTaskKey
    get() = DownloadTaskKey(packageName, versionCode)
