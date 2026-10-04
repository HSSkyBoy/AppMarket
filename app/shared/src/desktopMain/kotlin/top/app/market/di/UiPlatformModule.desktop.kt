package top.app.market.di

import top.app.market.platform.DesktopUiPlatform
import top.app.market.platform.UiPlatform
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

actual val uiPlatformModule = module {
    singleOf(::DesktopUiPlatform) { bind<UiPlatform>() }
}
