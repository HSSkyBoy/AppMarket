package top.app.market.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import top.app.market.data.install.platform.PackageChangeHandler
import top.app.market.domain.model.installed.PackageChange
import top.app.market.domain.repository.PackageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

class PackageRepositoryImpl(private val context: Context) : PackageRepository, PackageChangeHandler {
    override val selfPackageName: String get() = context.packageName

    private val mutableChanges = MutableSharedFlow<PackageChange>(extraBufferCapacity = 16)
    override val changes: SharedFlow<PackageChange> = mutableChanges.asSharedFlow()

    // Resolved from one cached bulk scan instead of a per-package query per result; invalidated by
    // package add/remove broadcasts (mirrors the official Market's in-memory installed-apps cache).
    // MIUI 会丢包变化广播，纯增量维护漏一次就永远陈旧，故加一道时间兜底。
    @Volatile
    private var versionCache: Map<String, Long>? = null

    @Volatile
    private var versionCacheAt = 0L

    override suspend fun installedVersionCodes(packageNames: Collection<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val all = installedVersionMap()
            buildMap {
                packageNames.distinct().forEach { pkg -> all[pkg]?.let { put(pkg, it) } }
            }
        }

    override suspend fun installedVersionName(packageName: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
                info.versionName
            }.getOrNull()
        }

    override suspend fun freshInstalledVersionCode(packageName: String): Long? =
        withContext(Dispatchers.IO) { packageInfo(packageName)?.let(::versionCode) }

    @Synchronized
    private fun installedVersionMap(): Map<String, Long> {
        val fresh = SystemClock.elapsedRealtime() - versionCacheAt < VERSION_CACHE_TTL_MS
        versionCache?.takeIf { fresh }?.let { return it }
        val scanned = scanInstalledVersions()
        versionCache = scanned
        versionCacheAt = SystemClock.elapsedRealtime()
        return scanned
    }

    @Suppress("DEPRECATION")
    private fun scanInstalledVersions(): Map<String, Long> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getInstalledPackages(0)
        }
        return buildMap {
            packages.forEach { put(it.packageName, versionCode(it)) }
        }
    }

    override suspend fun onPackageChanged(packageName: String, installed: Boolean): Boolean {
        val packageInfo = if (installed) packageInfo(packageName) else null
        if (installed && packageInfo == null) return false
        val versionCode = packageInfo?.let(::versionCode)
        synchronized(this) {
            versionCache = versionCache?.toMutableMap()?.apply {
                if (versionCode == null) remove(packageName) else put(packageName, versionCode)
            }
        }
        mutableChanges.emit(
            PackageChange(
                packageName = packageName,
                installedVersionCode = versionCode,
                installedVersionName = packageInfo?.versionName.orEmpty(),
            )
        )
        return true
    }

    private fun packageInfo(packageName: String): PackageInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
    }.getOrNull()

    override fun openApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    override fun openLink(link: String): Boolean {
        if (link.isBlank()) return false
        val intent = runCatching {
            if (link.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(link, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))
            }
        }.getOrNull() ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private companion object {
        const val VERSION_CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
