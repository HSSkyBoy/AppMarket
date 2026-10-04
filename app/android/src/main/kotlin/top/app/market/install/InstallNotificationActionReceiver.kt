package top.app.market.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import top.app.market.domain.repository.DownloadRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 处理进度通知上的用户动作（当前仅取消下载）。 */
class InstallNotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val downloads: DownloadRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)?.takeIf { it.isNotBlank() } ?: return
        downloads.cancel(packageName)
    }

    companion object {
        const val ACTION_CANCEL = "top.app.market.action.NOTIFICATION_CANCEL_DOWNLOAD"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
