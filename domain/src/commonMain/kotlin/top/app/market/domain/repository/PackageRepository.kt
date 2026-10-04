package top.app.market.domain.repository

import top.app.market.domain.model.installed.PackageChange
import kotlinx.coroutines.flow.SharedFlow

/** Queries and opens locally installed applications. */
interface PackageRepository {
    val selfPackageName: String

    /** PackageManager-backed changes, emitted only after the local installed-app cache is updated. */
    val changes: SharedFlow<PackageChange>
    suspend fun installedVersionCodes(packageNames: Collection<String>): Map<String, Long>
    suspend fun installedVersionName(packageName: String): String?

    /** 绕过缓存直查：缓存靠包变化广播刷新，而调用方要判断的正是广播丢没丢。 */
    suspend fun freshInstalledVersionCode(packageName: String): Long?
    fun openApp(packageName: String): Boolean
    fun openLink(link: String): Boolean
}
