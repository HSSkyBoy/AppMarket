package top.app.market

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import top.app.market.data.platform.DebugLogging
import top.app.market.data.platform.ThemePlatformPreferenceContract
import top.app.market.di.dataModules
import top.app.market.di.uiPlatformModule
import top.app.market.di.viewModelModule
import top.app.market.domain.repository.DownloadRepository
import top.app.market.install.InstallNotificationCoordinator
import top.app.market.install.InstallPackageChangedReceiver
import top.app.market.install.androidInstallPlatformModule
import top.app.market.platform.setupImageLoader
import io.ktor.client.HttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.lsposed.hiddenapibypass.HiddenApiBypass

class MarketApplication : Application(), KoinComponent {
    companion object {
        private fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enabled: Boolean) {
            runCatching {
                val method = ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(appInfo, enabled)
            }
        }
    }

    private val downloads: DownloadRepository by inject()
    private val notifications: InstallNotificationCoordinator by inject()
    private val httpClient: HttpClient by inject()
    private val packageChangedReceiver = InstallPackageChangedReceiver()

    override fun onCreate() {
        super.onCreate()
        DebugLogging.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val predictiveBackEnabled = getSharedPreferences(
                ThemePlatformPreferenceContract.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ).getBoolean(ThemePlatformPreferenceContract.PREDICTIVE_BACK_KEY, false)
            setEnableOnBackInvokedCallback(applicationInfo, predictiveBackEnabled)
        }
        val applicationModule = module {
            single<Context> { this@MarketApplication.applicationContext }
        }
        startKoin {
            modules(
                applicationModule,
                androidInstallPlatformModule,
                *dataModules.toTypedArray(),
                uiPlatformModule,
                viewModelModule,
            )
        }
        setupImageLoader(httpClient)
        registerPackageChangedReceiver()
        // Eagerly construct the download graph so persisted task and update-history
        // reconciliation also run when the user has not opened a download screen.
        downloads
        // Eagerly start the notification coordinator so it observes install progress
        // from process start, independent of any UI or the foreground service.
        notifications
    }

    private fun registerPackageChangedReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageChangedReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(packageChangedReceiver, filter)
        }
    }
}
