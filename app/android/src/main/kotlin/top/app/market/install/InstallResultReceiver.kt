package top.app.market.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import top.app.market.data.install.platform.InstallResultHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

class InstallResultReceiver : BroadcastReceiver(), KoinComponent {
    private val handler: InstallResultHandler by inject()
    private val notifications: InstallNotificationCoordinator by inject()
    private val scope: CoroutineScope by inject()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { packageName }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pendingResult = goAsync()
        // ATOMIC：即便 scope 已取消也要进函数体，否则 pendingResult 永不 finish
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                withTimeoutOrNull(BROADCAST_TIMEOUT_MS.milliseconds) {
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        runCatching { handler.onPendingUserAction(taskId) }
                        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        // 前台直接拉起确认页；后台改发高优先级通知，避免任务无声卡死
                        val launched = notifications.handleUserConfirmation(packageName, displayName, confirmIntent)
                        if (!launched && confirmIntent == null) {
                            val message = "System installer did not provide a confirmation intent"
                            runCatching {
                                handler.onResult(taskId, PackageInstaller.STATUS_FAILURE_BLOCKED, message)
                            }
                        }
                    } else {
                        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                            // 用户在系统确认页主动取消：抑制随后的失败态通知
                            notifications.onUserAborted(packageName)
                        }
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        runCatching { handler.onResult(taskId, status, message) }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val BROADCAST_TIMEOUT_MS = 8_000L
        const val ACTION_INSTALL_STATUS = "top.app.market.action.INSTALL_STATUS"
        const val EXTRA_TASK_ID = "install_task_id"
        const val EXTRA_SESSION_ID = "install_session_id"
        const val EXTRA_PACKAGE_NAME = "install_package_name"
        const val EXTRA_DISPLAY_NAME = "install_display_name"
    }
}
