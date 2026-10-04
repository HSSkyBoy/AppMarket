package top.app.market.data.remote.xiaomi

import top.app.market.domain.model.installed.InstalledPackage

internal fun updateInfoRequestFields(
    common: Map<String, String>,
    androidVersion: String,
    instanceId: String,
    packages: List<InstalledPackage>,
    invalidSystemPackageHash: String,
    timestamp: Long,
): MutableMap<String, String> {
    require(packages.isNotEmpty()) { "Update-info package vector must not be empty" }
    require(packages.map(InstalledPackage::packageName).distinct().size == packages.size) {
        "Update-info package vector contains duplicate package names"
    }

    return common.toMutableMap().apply {
        remove("tzNonce")
        remove("tzSign")
        put("androidVersion", androidVersion)
        put("apkSource", packages.joinToString(",", transform = InstalledPackage::apkSource))
        put("autoUpdateEnabled", "false")
        put("background", "false")
        put("downloadRestriction", "1")
        put("downloadRestrictionMode", "0")
        put("invalidSystemPackageHash", invalidSystemPackageHash.ifBlank { "null" })
        put("privacyCompliance", "true")
        put("rankTypeV2", "true")
        put("session_id", instanceId + timestamp)
        put("showUnfitnessApp", "true")
        put("splits", packages.joinToString(",", transform = InstalledPackage::splits))
        put("packageName", packages.joinToString(",", transform = InstalledPackage::packageName))
        put("versionCode", packages.joinToString(",") { it.versionCode.toString() })
        put("oldApkHash", packages.joinToString(",", transform = InstalledPackage::oldApkHash))
        put("installedByMarket", packages.joinToString(",", transform = InstalledPackage::installedBy))
        put("ref", "update")
    }
}
