package top.app.market.domain.model.update

import top.app.market.domain.model.market.AppSource

data class IgnoredUpdate(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: String,
    val isSystemApp: Boolean,
    /** null means the entry was written by a version that did not persist its market source. */
    val source: AppSource? = null,
)
