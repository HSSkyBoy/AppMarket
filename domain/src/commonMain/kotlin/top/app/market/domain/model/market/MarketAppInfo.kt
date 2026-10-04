package top.app.market.domain.model.market

data class MarketAppInfo(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val publisherName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: String,
    val apkSize: Long,
    val deltaSize: Long = 0L,
    val ratingScore: Double,
    val changeLog: String = "",
    val isSystemApp: Boolean = false,
    val openLink: String = "",
    val installedVersionName: String = "",
    val installedVersionCode: Long = 0L,
    val installedOldApkHash: String = "0",
    val installedBaseApkPath: String = "",
    val installedSplits: String = "0",
    val type: String = "",
    val isAd: Boolean = false,
    /** 服务端预约状态:0 未开放预约、1 可预约、2 已预约(对齐官方 AppSubscribeState)。 */
    val subscribeState: Int = 0,
    /** 服务端标记的"暂不可提供下载"原因(空串=可下载;vivo 的 problemSearchTips 等)。 */
    val downloadBlockReason: String = "",
    val source: AppSource = AppSource.XIAOMI,
    val category: String = "",
    val downloadCount: Long = 0L,
)

fun MarketAppInfo.hasInstalledSplits(): Boolean =
    installedSplits.isNotBlank() && installedSplits != "0"

/** 预约期应用;带可下载版本的「预约+测试」类游戏不算,仍走下载。 */
fun MarketAppInfo.isReservation(): Boolean = subscribeState > 0 && versionCode <= 0L

/** 服务端禁止下载的应用(problemLevel 非 0 / downloadDisable 等),UI 应禁用下载点击。 */
fun MarketAppInfo.isDownloadBlocked(): Boolean = downloadBlockReason.isNotBlank()
