package top.app.market.di

import top.app.market.data.download.PlatformDownloadDataSource
import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.local.PreferencesDataSourceImpl
import top.app.market.data.platform.DesktopThemePlatformPreferences
import top.app.market.data.platform.ThemePlatformPreferences
import top.app.market.data.platform.createHttpClient
import top.app.market.data.remote.xiaomi.platform.DesktopDeviceDefaultsDataSource
import top.app.market.data.remote.xiaomi.platform.DesktopXiaomiDeviceIdentityDataSource
import top.app.market.data.remote.xiaomi.platform.DeviceDefaultsDataSource
import top.app.market.data.remote.xiaomi.platform.XiaomiDeviceIdentityDataSource
import top.app.market.data.repository.DesktopDownloadDataSource
import top.app.market.data.repository.DesktopInstalledApkHashRepositoryImpl
import top.app.market.data.repository.DesktopInstalledPackagesRepositoryImpl
import top.app.market.data.repository.DesktopInstallerDiscoveryRepositoryImpl
import top.app.market.data.repository.DesktopInstallerPreferencesRepositoryImpl
import top.app.market.data.repository.DesktopPackageDownloader
import top.app.market.data.repository.DesktopPackageRepositoryImpl
import top.app.market.data.repository.DesktopSavedPackageRepositoryImpl
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
    single { createHttpClient() }
    singleOf(::PreferencesDataSourceImpl) { bind<PreferencesDataSource>() }
    singleOf(::DesktopThemePlatformPreferences) { bind<ThemePlatformPreferences>() }
    singleOf(::DesktopDeviceDefaultsDataSource) { bind<DeviceDefaultsDataSource>() }
    singleOf(::DesktopXiaomiDeviceIdentityDataSource) { bind<XiaomiDeviceIdentityDataSource>() }
    singleOf(::DesktopInstalledPackagesRepositoryImpl) { bind<InstalledPackagesRepository>() }
    singleOf(::DesktopInstalledApkHashRepositoryImpl) { bind<InstalledApkHashRepository>() }
    singleOf(::DesktopPackageRepositoryImpl) { bind<PackageRepository>() }
    singleOf(::DesktopSavedPackageRepositoryImpl) { bind<SavedPackageRepository>() }
    singleOf(::DesktopInstallerPreferencesRepositoryImpl) { bind<InstallerPreferencesRepository>() }
    singleOf(::DesktopInstallerDiscoveryRepositoryImpl) { bind<InstallerDiscoveryRepository>() }
    single { DesktopPackageDownloader(get()) }
    singleOf(::DesktopDownloadDataSource) { bind<PlatformDownloadDataSource>() }
}
