package top.app.market.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import top.app.market.data.install.platform.InstallResultHandler
import top.app.market.data.install.platform.PackageChangeHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

class InstallPackageChangedReceiver : BroadcastReceiver(), KoinComponent {
    private val handler: InstallResultHandler by inject()
    private val packages: PackageChangeHandler by inject()
    private val scope: CoroutineScope by inject()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        val installed = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                true
            }

            Intent.ACTION_PACKAGE_REPLACED -> true
            Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                false
            }

            else -> return
        }
        val pendingResult = goAsync()
        // ATOMIC：即便 scope 已取消也要进函数体，否则 pendingResult 永不 finish
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                withTimeoutOrNull(BROADCAST_TIMEOUT_MS.milliseconds) {
                    // Refresh installed-app state before completing the market-owned install task.
                    runCatching { packages.onPackageChanged(packageName, installed) }
                    if (installed) runCatching { handler.onPackageChanged(packageName) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val BROADCAST_TIMEOUT_MS = 8_000L
    }
}
