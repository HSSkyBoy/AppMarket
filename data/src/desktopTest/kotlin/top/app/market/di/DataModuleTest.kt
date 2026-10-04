package top.app.market.di

import top.app.market.domain.repository.AccountRepository
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.InstalledApkHashRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.InstallerDiscoveryRepository
import top.app.market.domain.repository.InstallerPreferencesRepository
import top.app.market.domain.repository.MarketRepository
import top.app.market.domain.repository.MarketSourceRepository
import top.app.market.domain.repository.OppoRepository
import top.app.market.domain.repository.PackageRepository
import top.app.market.domain.repository.ProfileRepository
import top.app.market.domain.repository.SavedPackageRepository
import top.app.market.domain.repository.SearchHistoryRepository
import top.app.market.domain.repository.ThemePreferencesRepository
import top.app.market.domain.repository.TodayRepository
import top.app.market.domain.repository.UpdateHistoryRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import top.app.market.domain.repository.VivoRepository
import top.app.market.domain.repository.WandoujiaRepository
import org.koin.core.KoinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

class DataModuleTest {
    @Test
    fun resolvesEveryDomainRepositoryBinding() {
        val application = KoinApplication.init().modules(dataModules)
        try {
            with(application.koin) {
                assertNotNull(get<AccountRepository>())
                assertNotNull(get<DownloadRepository>())
                assertNotNull(get<InstalledApkHashRepository>())
                assertNotNull(get<InstalledPackagesRepository>())
                assertNotNull(get<InstallerPreferencesRepository>())
                assertNotNull(get<InstallerDiscoveryRepository>())
                assertNotNull(get<MarketRepository>())
                assertNotNull(get<MarketSourceRepository>())
                assertNotNull(get<PackageRepository>())
                assertNotNull(get<ProfileRepository>())
                assertNotNull(get<SavedPackageRepository>())
                assertNotNull(get<SearchHistoryRepository>())
                assertNotNull(get<TodayRepository>())
                assertNotNull(get<ThemePreferencesRepository>())
                assertNotNull(get<UpdateHistoryRepository>())
                assertNotNull(get<UpdatePreferencesRepository>())
                assertNotNull(get<VivoRepository>())
                assertNotNull(get<WandoujiaRepository>())
                assertNotNull(get<OppoRepository>())
            }
        } finally {
            application.close()
        }
    }
}
