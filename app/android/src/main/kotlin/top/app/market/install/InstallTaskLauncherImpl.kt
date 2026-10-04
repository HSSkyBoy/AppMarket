package top.app.market.install

import android.content.Context
import android.content.Intent
import top.app.market.data.install.platform.InstallTaskLauncher

internal class InstallTaskLauncherImpl(
    private val context: Context,
) : InstallTaskLauncher {
    override fun start(taskId: String) {
        val intent = Intent(context, InstallForegroundService::class.java)
            .setAction(InstallForegroundService.ACTION_PROCESS)
            .putExtra(InstallForegroundService.EXTRA_TASK_ID, taskId)
        context.startForegroundService(intent)
    }
}
