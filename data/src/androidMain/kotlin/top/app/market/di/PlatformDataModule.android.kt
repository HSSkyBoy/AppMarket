package top.app.market.di

import top.app.market.data.download.PlatformDownloadDataSource
import top.app.market.data.install.AndroidInstallRepositoryImpl
import top.app.market.data.install.DeltaFallbackBus
import top.app.market.data.install.PackageStagingDownloader
import top.app.market.data.install.RemotePackageDownloader
import top.app.market.data.install.ThirdPartyInstallEngine
import top.app.market.data.install.backend.InstallerBackendSelector
import top.app.market.data.install.backend.PrivilegedPackageInstallerFactory
import top.app.market.data.install.backend.RootInstallerBackend
import top.app.market.data.install.backend.ShizukuInstallerBackend
import top.app.market.data.install.backend.StandardInstallerBackend
import top.app.market.data.install.backend.root.RootBinderBridge
import top.app.market.data.install.network.ArtifactSourceReader
import top.app.market.data.install.network.InstallHttpClient
import top.app.market.data.install.platform.InstallResultHandler
import top.app.market.data.install.platform.InstallTaskProcessor
import top.app.market.data.install.platform.PackageChangeHandler
import top.app.market.data.install.platform.SavedPackageInstallLauncher
import top.app.market.data.install.storage.ArtifactStagingStore
import top.app.market.data.install.storage.MediaStorePackageStore
import top.app.market.data.install.storage.SavedPackageIndex
import top.app.market.data.install.task.InstallTaskStore
import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.local.PreferencesDataSourceImpl
import top.app.market.data.platform.AndroidThemePlatformPreferences
import top.app.market.data.platform.ThemePlatformPreferences
import top.app.market.data.platform.createAndroidHttpClient
import top.app.market.data.remote.xiaomi.platform.AndroidDeviceDefaultsDataSource
import top.app.market.data.remote.xiaomi.platform.AndroidInstalledApkHashRepositoryImpl
import top.app.market.data.remote.xiaomi.platform.AndroidInstalledPackagesRepositoryImpl
import top.app.market.data.remote.xiaomi.platform.AndroidXiaomiDeviceIdentityDataSource
import top.app.market.data.remote.xiaomi.platform.DeviceDefaultsDataSource
import top.app.market.data.remote.xiaomi.platform.XiaomiDeviceIdentityDataSource
import top.app.market.data.repository.AndroidDownloadDataSource
import top.app.market.data.repository.AndroidInstallerDiscoveryRepositoryImpl
import top.app.market.data.repository.InstallerPreferencesRepositoryImpl
import top.app.market.data.repository.PackageRepositoryImpl
import top.app.market.data.repository.SavedPackageRepositoryImpl
import top.app.market.domain.repository.InstallRepository
import top.app.market.domain.repository.InstalledApkHashRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.InstallerDiscoveryRepository
import top.app.market.domain.repository.InstallerPreferencesRepository
import top.app.market.domain.repository.PackageRepository
import top.app.market.domain.repository.SavedPackageRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

internal actual val platformDataModule: Module = module {
    single { createAndroidHttpClient() }
    singleOf(::PreferencesDataSourceImpl) { bind<PreferencesDataSource>() }
    singleOf(::AndroidThemePlatformPreferences) { bind<ThemePlatformPreferences>() }
    singleOf(::AndroidDeviceDefaultsDataSource) { bind<DeviceDefaultsDataSource>() }
    singleOf(::AndroidXiaomiDeviceIdentityDataSource) { bind<XiaomiDeviceIdentityDataSource>() }
    singleOf(::AndroidInstalledPackagesRepositoryImpl) { bind<InstalledPackagesRepository>() }
    singleOf(::AndroidInstalledApkHashRepositoryImpl) { bind<InstalledApkHashRepository>() }
    singleOf(::PackageRepositoryImpl) {
        bind<PackageRepository>()
        bind<PackageChangeHandler>()
    }
    singleOf(::InstallerPreferencesRepositoryImpl) { bind<InstallerPreferencesRepository>() }
    singleOf(::AndroidInstallerDiscoveryRepositoryImpl) { bind<InstallerDiscoveryRepository>() }

    singleOf(::InstallTaskStore)
    singleOf(::SavedPackageIndex)
    singleOf(::MediaStorePackageStore)
    singleOf(::ArtifactStagingStore)
    singleOf(::InstallHttpClient)
    singleOf(::DeltaFallbackBus)
    singleOf(::ArtifactSourceReader)
    singleOf(::PackageStagingDownloader)
    singleOf(::PrivilegedPackageInstallerFactory)
    singleOf(::RootBinderBridge)
    singleOf(::StandardInstallerBackend)
    singleOf(::RootInstallerBackend)
    singleOf(::ShizukuInstallerBackend)
    singleOf(::InstallerBackendSelector)
    singleOf(::ThirdPartyInstallEngine)
    singleOf(::AndroidInstallRepositoryImpl) { bind<InstallRepository>() }
    singleOf(::RemotePackageDownloader)
    singleOf(::SavedPackageRepositoryImpl) { bind<SavedPackageRepository>() }
    singleOf(::AndroidDownloadDataSource) {
        bind<PlatformDownloadDataSource>()
        bind<InstallTaskProcessor>()
        bind<InstallResultHandler>()
        bind<SavedPackageInstallLauncher>()
    }
}
