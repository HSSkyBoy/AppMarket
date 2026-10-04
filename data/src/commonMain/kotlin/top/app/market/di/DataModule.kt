package top.app.market.di

import top.app.market.data.download.RecordingDownloadRepositoryImpl
import top.app.market.data.platform.debugLog
import top.app.market.data.remote.honor.HonorApi
import top.app.market.data.remote.honor.HonorProtocol
import top.app.market.data.remote.huawei.HuaweiApi
import top.app.market.data.remote.huawei.HuaweiProtocol
import top.app.market.data.remote.oppo.OppoApi
import top.app.market.data.remote.samsung.SamsungApi
import top.app.market.data.remote.taptap.TapTapApi
import top.app.market.data.remote.taptap.TapTapApiConfig
import top.app.market.data.remote.vivo.VivoApi
import top.app.market.data.remote.vivo.VivoAuroraApi
import top.app.market.data.remote.vivo.VivoUpdateApi
import top.app.market.data.remote.wandoujia.WandoujiaApi
import top.app.market.data.remote.wandoujia.WandoujiaApiConfig
import top.app.market.data.remote.xiaomi.UpdateInfoCache
import top.app.market.data.remote.xiaomi.XiaomiApi
import top.app.market.data.remote.xiaomi.XiaomiClient
import top.app.market.data.remote.xiaomi.XiaomiHttpClient
import top.app.market.data.repository.AnonymousAccountRepositoryImpl
import top.app.market.data.repository.HonorRepositoryImpl
import top.app.market.data.repository.HuaweiRepositoryImpl
import top.app.market.data.repository.MarketRepositoryImpl
import top.app.market.data.repository.MarketSourceRepositoryImpl
import top.app.market.data.repository.OppoRepositoryImpl
import top.app.market.data.repository.ProfileRepositoryImpl
import top.app.market.data.repository.SamsungRepositoryImpl
import top.app.market.data.repository.TapTapRepositoryImpl
import top.app.market.data.repository.TodayRepositoryImpl
import top.app.market.data.repository.VivoRepositoryImpl
import top.app.market.data.repository.WandoujiaRepositoryImpl
import top.app.market.data.store.SearchHistoryRepositoryImpl
import top.app.market.data.store.ThemePreferencesRepositoryImpl
import top.app.market.data.store.UpdateHistoryRepositoryImpl
import top.app.market.data.store.UpdatePreferencesRepositoryImpl
import top.app.market.domain.repository.AccountRepository
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.HonorRepository
import top.app.market.domain.repository.HuaweiRepository
import top.app.market.domain.repository.MarketRepository
import top.app.market.domain.repository.MarketSourceRepository
import top.app.market.domain.repository.OppoRepository
import top.app.market.domain.repository.ProfileRepository
import top.app.market.domain.repository.SamsungRepository
import top.app.market.domain.repository.TapTapRepository
import top.app.market.domain.repository.SearchHistoryRepository
import top.app.market.domain.repository.ThemePreferencesRepository
import top.app.market.domain.repository.TodayRepository
import top.app.market.domain.repository.UpdateHistoryRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import top.app.market.domain.repository.VivoRepository
import top.app.market.domain.repository.WandoujiaRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

private val commonDataModule = module {
    single<CoroutineScope> {
        // 数据层后台协程的最后防线：存储/IO 异常记录日志而不是崩溃整个进程
        val handler = CoroutineExceptionHandler { _, error ->
            debugLog("DataScope") { "Uncaught exception in data scope: ${error.stackTraceToString()}" }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }
    single { Json { ignoreUnknownKeys = true; isLenient = true } }

    singleOf(::UpdatePreferencesRepositoryImpl) { bind<UpdatePreferencesRepository>() }
    singleOf(::ThemePreferencesRepositoryImpl) { bind<ThemePreferencesRepository>() }
    singleOf(::SearchHistoryRepositoryImpl) { bind<SearchHistoryRepository>() }
    singleOf(::UpdateHistoryRepositoryImpl) { bind<UpdateHistoryRepository>() }

    singleOf(::AnonymousAccountRepositoryImpl) { bind<AccountRepository>() }
    singleOf(::UpdateInfoCache)
    singleOf(::XiaomiClient)
    singleOf(::XiaomiHttpClient)
    singleOf(::XiaomiApi)
    singleOf(::ProfileRepositoryImpl) { bind<ProfileRepository>() }
    singleOf(::MarketRepositoryImpl) { bind<MarketRepository>() }
    singleOf(::TodayRepositoryImpl) { bind<TodayRepository>() }
    singleOf(::VivoApi)
    singleOf(::VivoUpdateApi)
    singleOf(::VivoAuroraApi)
    singleOf(::VivoRepositoryImpl) { bind<VivoRepository>() }
    single { WandoujiaApiConfig() }
    singleOf(::WandoujiaApi)
    singleOf(::WandoujiaRepositoryImpl) { bind<WandoujiaRepository>() }
    singleOf(::OppoApi)
    singleOf(::OppoRepositoryImpl) { bind<OppoRepository>() }
    singleOf(::SamsungApi)
    singleOf(::SamsungRepositoryImpl) { bind<SamsungRepository>() }
    singleOf(::HonorProtocol)
    singleOf(::HonorApi)
    singleOf(::HonorRepositoryImpl) { bind<HonorRepository>() }
    singleOf(::HuaweiProtocol)
    singleOf(::HuaweiApi)
    singleOf(::HuaweiRepositoryImpl) { bind<HuaweiRepository>() }
    single { TapTapApiConfig() }
    singleOf(::TapTapApi)
    singleOf(::TapTapRepositoryImpl) { bind<TapTapRepository>() }
    singleOf(::MarketSourceRepositoryImpl) { bind<MarketSourceRepository>() }

    singleOf(::RecordingDownloadRepositoryImpl) { bind<DownloadRepository>() }
}

internal expect val platformDataModule: Module

/** Complete data graph loaded by an application composition root. */
val dataModules: List<Module>
    get() = listOf(commonDataModule, platformDataModule)
