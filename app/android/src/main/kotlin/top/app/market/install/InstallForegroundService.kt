package top.app.market.install

import android.app.Service
import android.content.Intent
import android.os.IBinder
import top.app.market.data.install.platform.InstallTaskProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap

class InstallForegroundService : Service(), KoinComponent {
    private val processor: InstallTaskProcessor by inject()
    private val notifications: InstallNotificationCoordinator by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val jobLock = Any()
    private var latestStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先进入前台再校验，避免 startForegroundService 后不调 startForeground 触发 ANR 崩溃
        startForeground(NOTIFICATION_ID, notifications.foregroundNotification())
        notifications.onForegroundChanged(true)
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID) ?: run {
            stopForegroundAndNotify()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val candidate = scope.launch(start = CoroutineStart.LAZY) {
            val runningJob = currentCoroutineContext().job
            try {
                try {
                    processor.process(taskId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    runCatching { processor.onUnhandledFailure(taskId, error) }
                }
            } finally {
                val stopId = synchronized(jobLock) {
                    jobs.remove(taskId, runningJob)
                    latestStartId.takeIf { jobs.isEmpty() }
                }
                if (stopId != null && stopSelfResult(stopId)) {
                    stopForegroundAndNotify()
                }
            }
        }
        val shouldStart = synchronized(jobLock) {
            val inserted = jobs.putIfAbsent(taskId, candidate) == null
            latestStartId = startId
            inserted
        }
        if (shouldStart) {
            candidate.start()
        } else {
            candidate.cancel()
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundAndNotify() {
        // 让协调器接手后台兜底（终态 / 等待确认通知），并撤下前台进度条
        notifications.onForegroundChanged(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        notifications.onForegroundChanged(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PROCESS = "top.app.market.action.PROCESS_INSTALL_TASK"
        const val EXTRA_TASK_ID = "install_task_id"
        private val NOTIFICATION_ID = InstallNotifications.ONGOING_ID
    }
}
