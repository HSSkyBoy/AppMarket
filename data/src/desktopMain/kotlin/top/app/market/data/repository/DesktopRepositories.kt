package top.app.market.data.repository

import top.app.market.domain.model.installed.PackageChange
import top.app.market.domain.model.installer.InstallerAttributionMode
import top.app.market.domain.model.installer.InstallerCandidate
import top.app.market.domain.model.installer.InstallerMode
import top.app.market.domain.model.installer.SavedPackage
import top.app.market.domain.repository.InstalledApkHashRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.InstallerDiscoveryRepository
import top.app.market.domain.repository.InstallerPreferencesRepository
import top.app.market.domain.repository.PackageRepository
import top.app.market.domain.repository.SavedPackageRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.awt.Desktop
import java.net.URI

class DesktopPackageRepositoryImpl : PackageRepository {
    override val selfPackageName: String = "top.app.market"
    override val changes: SharedFlow<PackageChange> = MutableSharedFlow()
    override suspend fun installedVersionCodes(packageNames: Collection<String>): Map<String, Long> = emptyMap()
    override suspend fun installedVersionName(packageName: String): String? = null
    override suspend fun freshInstalledVersionCode(packageName: String): Long? = null
    override fun openApp(packageName: String): Boolean = false
    override fun openLink(link: String): Boolean {
        if (link.isBlank()) return false
        return runCatching { openInBrowser(link) }.isSuccess
    }
}

class DesktopInstallerPreferencesRepositoryImpl : InstallerPreferencesRepository {
    override suspend fun mode(): InstallerMode = InstallerMode.STANDARD
    override suspend fun setMode(mode: InstallerMode) {}
    override suspend fun thirdPartyInstallerPackage(): String = ""
    override suspend fun setThirdPartyInstallerPackage(packageName: String) {}
    override suspend fun saveToDownloads(): Boolean = true
    override suspend fun setSaveToDownloads(enabled: Boolean) {}
    override fun userActionNotRequiredConfigurable(): Boolean = false
    override suspend fun userActionNotRequiredEnabled(): Boolean = false
    override suspend fun setUserActionNotRequiredEnabled(enabled: Boolean) {}
    override fun deltaUpdateSupported(): Boolean = false
    override suspend fun deltaUpdateEnabled(): Boolean = false
    override suspend fun setDeltaUpdateEnabled(enabled: Boolean) {}
    override suspend fun deltaFallbackNoticeEnabled(): Boolean = false
    override suspend fun setDeltaFallbackNoticeEnabled(enabled: Boolean) {}
    override fun focusNotificationSupported(): Boolean = false
    override fun xiaomiIslandSupported(): Boolean = false
    override suspend fun xiaomiIslandOptimizationEnabled(): Boolean = false
    override suspend fun setXiaomiIslandOptimizationEnabled(enabled: Boolean) {}
    override suspend fun autoLaunchConfirmUi(): Boolean = true
    override suspend fun setAutoLaunchConfirmUi(enabled: Boolean) {}
    override suspend fun attributionMode(): InstallerAttributionMode =
        InstallerAttributionMode.AUTO_BY_SOURCE
    override suspend fun setAttributionMode(mode: InstallerAttributionMode) {}
    override suspend fun attributionCustomPackage(): String = ""
    override suspend fun setAttributionCustomPackage(packageName: String) {}
}

class DesktopInstallerDiscoveryRepositoryImpl : InstallerDiscoveryRepository {
    override suspend fun listCandidates(): List<InstallerCandidate> = emptyList()
}

class DesktopSavedPackageRepositoryImpl : SavedPackageRepository {
    override suspend fun list(): List<SavedPackage> = emptyList()
    override suspend fun install(id: String) {}
    override suspend fun delete(id: String) {}
}

class DesktopInstalledPackagesRepositoryImpl : InstalledPackagesRepository {
    override suspend fun installed() = emptyList<top.app.market.domain.model.installed.InstalledPackage>()
}

class DesktopInstalledApkHashRepositoryImpl : InstalledApkHashRepository {
    override suspend fun tapTap(path: String): String = top.app.market.data.platform.tapTapApkHash(path)

    override suspend fun md5(path: String): String = "0"
}

private fun openInBrowser(url: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
    }
}
