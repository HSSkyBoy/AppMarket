package top.app.market.domain.model.update

import top.app.market.domain.model.market.AppSource

/** 一条通过本应用完成的安装/更新记录；[previousVersionName] 为空表示首次安装。 */
data class UpdateHistoryEntry(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val previousVersionName: String,
    val icon: String,
    val changeLog: String,
    val installedAt: Long,
    /** null means the entry was written by a version that did not persist its market source. */
    val source: AppSource? = null,
)
