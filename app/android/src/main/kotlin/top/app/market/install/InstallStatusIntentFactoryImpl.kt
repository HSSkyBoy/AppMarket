package top.app.market.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import top.app.market.data.install.platform.InstallStatusIntentFactory

internal class InstallStatusIntentFactoryImpl(
    private val context: Context,
) : InstallStatusIntentFactory {
    override fun create(taskId: String, sessionId: Int, packageName: String, displayName: String): IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction(InstallResultReceiver.ACTION_INSTALL_STATUS)
            .putExtra(InstallResultReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
            .putExtra(InstallResultReceiver.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(InstallResultReceiver.EXTRA_DISPLAY_NAME, displayName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }
}
