package top.app.market.data.remote.xiaomi

import top.app.market.domain.model.installed.InstalledPackage

internal const val MIUI_UPDATE_ANCHOR_PACKAGE = "com.miui.core"

private val miuiUpdateAnchor = InstalledPackage(
    packageName = MIUI_UPDATE_ANCHOR_PACKAGE,
    versionCode = 0L,
    isSystemApp = true,
)

/**
 * Adds the package-name marker that enables Xiaomi's `miuiApp` matching. Real package metadata wins
 * when the device actually exposes `com.miui.core`.
 */
internal fun withMiuiUpdateAnchor(packages: List<InstalledPackage>): List<InstalledPackage> =
    LinkedHashMap<String, InstalledPackage>().apply {
        packages.forEach { put(it.packageName, it) }
        putIfAbsent(MIUI_UPDATE_ANCHOR_PACKAGE, miuiUpdateAnchor)
    }.values.toList()
