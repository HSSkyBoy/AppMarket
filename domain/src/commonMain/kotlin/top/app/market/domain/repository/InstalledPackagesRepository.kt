package top.app.market.domain.repository

import top.app.market.domain.model.installed.InstalledPackage

/** Installed-app metadata for targeted detail lookups and full update checks. */
fun interface InstalledPackagesRepository {
    suspend fun installed(): List<InstalledPackage>

    /** Resolves one package without requiring implementations to enumerate the full installed set. */
    suspend fun installedPackage(packageName: String): InstalledPackage? =
        installed().firstOrNull { it.packageName == packageName }
}
