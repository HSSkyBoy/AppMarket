package top.app.market.di

import top.app.market.viewmodel.AppDetailViewModel
import top.app.market.viewmodel.CategoryViewModel
import top.app.market.viewmodel.DeviceProfileViewModel
import top.app.market.viewmodel.DownloadingAppsViewModel
import top.app.market.viewmodel.HistoricalVersionsViewModel
import top.app.market.viewmodel.IgnoredAppsViewModel
import top.app.market.viewmodel.InstallerSettingsViewModel
import top.app.market.viewmodel.ManualUpdateViewModel
import top.app.market.viewmodel.SavedPackagesViewModel
import top.app.market.viewmodel.SearchViewModel
import top.app.market.viewmodel.ThemeSettingsViewModel
import top.app.market.viewmodel.TodayViewModel
import top.app.market.viewmodel.UpdateHistoryViewModel
import top.app.market.viewmodel.UpdatesViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModelOf(::UpdatesViewModel)
    viewModelOf(::ThemeSettingsViewModel)
    viewModelOf(::UpdateHistoryViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::SavedPackagesViewModel)
    viewModelOf(::DownloadingAppsViewModel)
    viewModelOf(::ManualUpdateViewModel)
    viewModelOf(::InstallerSettingsViewModel)
    viewModelOf(::IgnoredAppsViewModel)
    viewModelOf(::HistoricalVersionsViewModel)
    viewModelOf(::DeviceProfileViewModel)
    viewModelOf(::AppDetailViewModel)
    viewModelOf(::TodayViewModel)
    viewModelOf(::CategoryViewModel)
}
