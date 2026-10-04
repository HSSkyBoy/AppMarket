package top.app.market.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import top.app.market.MainActivity
import top.app.market.R
import top.app.market.domain.model.download.DownloadPhase
import top.app.market.domain.model.download.DownloadState

// 通道管理与各形态通知构建；发出与节流由 InstallNotificationCoordinator 负责。
internal object InstallNotifications {
    const val ONGOING_ID = 4101
    const val PROGRESS_CHANNEL_ID = "package_install"
    const val ALERT_CHANNEL_ID = "install_alerts"

    // 成功/下载完成态 3 秒后自动隐藏（通知超时 + 岛 islandTimeout 双保险）
    const val AUTO_HIDE_SECONDS = 3
    private const val AUTO_HIDE_MS = AUTO_HIDE_SECONDS * 1000L

    // Android 16 ProgressStyle 分段权重：下载占大头，提交安装后没有细粒度回调
    private const val SEGMENT_DOWNLOAD = 90
    private const val SEGMENT_INSTALL = 10

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                context.getString(R.string.install_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.install_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    fun alertId(packageName: String): Int = "install_alert:$packageName".hashCode()

    fun preparing(context: Context): Notification =
        progressBuilder(context)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.install_notification_text))
            .setProgress(0, 0, true)
            .build()

    data class OngoingContent(
        val title: String,
        val text: String,
        val percent: Int?,
        val installing: Boolean,
        val cancelablePackage: String?,
        val iconUrl: String,
        val capsuleStatus: String,
        val packageName: String,
        val multiple: Boolean = false,
    ) {
        val shortText: String get() = percent?.let { "$it%" } ?: text
    }

    fun ongoingContent(context: Context, active: List<DownloadState>): OngoingContent {
        val single = active.singleOrNull()
        if (single != null) {
            return OngoingContent(
                title = single.displayName,
                text = phaseLabel(context, single.phase),
                percent = single.progress?.takeIf { single.phase != DownloadPhase.INSTALLING },
                installing = single.phase == DownloadPhase.INSTALLING,
                cancelablePackage = single.packageName.takeIf {
                    single.phase in setOf(DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING)
                },
                iconUrl = single.icon,
                capsuleStatus = shortPhaseLabel(context, single.phase),
                packageName = single.packageName,
            )
        }
        // 多任务：以下载最接近完成的应用为代表，附 x/N 完成计数
        val representative = active.maxByOrNull { downloadRank(it) } ?: active.first()
        val completed = active.count { downloadRank(it) >= 100 }
        return OngoingContent(
            title = representative.displayName,
            text = context.getString(R.string.notif_multi_progress, completed, active.size),
            percent = representative.progress?.takeIf { representative.phase != DownloadPhase.INSTALLING },
            installing = representative.phase == DownloadPhase.INSTALLING,
            cancelablePackage = null,
            iconUrl = representative.icon,
            capsuleStatus = shortPhaseLabel(context, representative.phase),
            packageName = representative.packageName,
            multiple = true,
        )
    }

    private fun downloadRank(state: DownloadState): Int =
        if (state.phase == DownloadPhase.INSTALLING) 100 else state.progress ?: 0

    // promoteLiveUpdate=false 时走普通进度条：与超级岛互斥，避免两套胶囊机制并存打架。
    fun ongoing(
        context: Context,
        content: OngoingContent,
        largeIcon: Bitmap? = null,
        promoteLiveUpdate: Boolean = true,
    ): NotificationCompat.Builder {
        val builder = progressBuilder(context)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(
                if (content.multiple) downloadsIntent(context)
                else detailIntent(context, content.packageName)
            )
        largeIcon?.let(builder::setLargeIcon)

        content.cancelablePackage?.let { packageName ->
            builder.addAction(
                0,
                context.getString(R.string.notif_action_cancel),
                cancelIntent(context, packageName),
            )
        }

        if (promoteLiveUpdate && Build.VERSION.SDK_INT >= 36) {
            applyLiveUpdate(builder, content)
        } else {
            when {
                content.installing -> builder.setProgress(0, 0, true)
                content.percent != null -> builder.setProgress(100, content.percent, false)
                else -> builder.setProgress(0, 0, true)
            }
        }
        return builder
    }

    fun awaitingConfirm(
        context: Context,
        displayName: String,
        confirmIntent: Intent?,
        largeIcon: Bitmap? = null,
    ): Notification {
        val contentIntent = if (confirmIntent != null) {
            PendingIntent.getActivity(
                context,
                displayName.hashCode(),
                Intent(confirmIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            openAppIntent(context)
        }
        return alertBuilder(context)
            .setContentTitle(displayName)
            .setContentText(context.getString(R.string.notif_awaiting_confirm))
            .setSubText(context.getString(R.string.notif_tap_to_confirm))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { largeIcon?.let(::setLargeIcon) }
            .build()
    }

    fun installSuccess(context: Context, packageName: String, displayName: String, largeIcon: Bitmap? = null): Notification {
        val builder = alertBuilder(context)
            .setContentTitle(displayName)
            .setContentText(context.getString(R.string.notif_install_success))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setTimeoutAfter(AUTO_HIDE_MS)
        largeIcon?.let(builder::setLargeIcon)
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.addAction(
                0,
                context.getString(R.string.notif_action_open),
                PendingIntent.getActivity(
                    context,
                    packageName.hashCode(),
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    fun downloadComplete(context: Context, displayName: String, largeIcon: Bitmap? = null): Notification =
        alertBuilder(context)
            .setContentTitle(displayName)
            .setContentText(context.getString(R.string.notif_download_complete))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setTimeoutAfter(AUTO_HIDE_MS)
            .apply { largeIcon?.let(::setLargeIcon) }
            .build()

    fun installFailed(context: Context, displayName: String, message: String, largeIcon: Bitmap? = null): Notification =
        alertBuilder(context)
            .setContentTitle(displayName)
            .setContentText(message.ifBlank { context.getString(R.string.notif_install_failed) })
            .setSubText(context.getString(R.string.notif_install_failed))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .apply { largeIcon?.let(::setLargeIcon) }
            .build()

    fun phaseLabel(context: Context, phase: DownloadPhase): String = when (phase) {
        DownloadPhase.QUEUED -> context.getString(R.string.notif_phase_queued)
        DownloadPhase.DOWNLOADING -> context.getString(R.string.notif_phase_downloading)
        DownloadPhase.INSTALLING -> context.getString(R.string.notif_phase_installing)
        DownloadPhase.AWAITING_USER_ACTION -> context.getString(R.string.notif_awaiting_confirm)
        else -> context.getString(R.string.install_notification_text)
    }

    // 岛胶囊左侧用的短状态（3 字），与右侧百分比宽度平衡
    private fun shortPhaseLabel(context: Context, phase: DownloadPhase): String = when (phase) {
        DownloadPhase.QUEUED -> context.getString(R.string.notif_phase_short_queued)
        DownloadPhase.DOWNLOADING -> context.getString(R.string.notif_phase_short_downloading)
        DownloadPhase.INSTALLING -> context.getString(R.string.notif_phase_short_installing)
        else -> context.getString(R.string.notif_phase_short_downloading)
    }

    fun cancelIntent(context: Context, packageName: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            Intent(context, InstallNotificationActionReceiver::class.java)
                .setAction(InstallNotificationActionReceiver.ACTION_CANCEL)
                .putExtra(InstallNotificationActionReceiver.EXTRA_PACKAGE_NAME, packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // Android 16 Live Update：分段 ProgressStyle + 简短文本 + 申请晋升为常驻胶囊。
    private fun applyLiveUpdate(builder: NotificationCompat.Builder, content: OngoingContent) {
        val style = NotificationCompat.ProgressStyle()
            .setProgressSegments(
                listOf(
                    NotificationCompat.ProgressStyle.Segment(SEGMENT_DOWNLOAD),
                    NotificationCompat.ProgressStyle.Segment(SEGMENT_INSTALL),
                )
            )
            .setStyledByProgress(true)
        when {
            // 提交安装后没有进度回调：停在安装段中点，chip 保持活动感
            content.installing -> style.setProgress(SEGMENT_DOWNLOAD + SEGMENT_INSTALL / 2)
            content.percent != null -> style.setProgress(content.percent * SEGMENT_DOWNLOAD / 100)
            else -> style.setProgressIndeterminate(true)
        }
        builder.setStyle(style)
            .setShortCriticalText(content.shortText)
            .setRequestPromotedOngoing(true)
    }

    private fun progressBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent(context))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)

    private fun alertBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // 多任务下载时跳「下载中的应用」页；复用 MainActivity 的 market://downloads 深链
    private fun downloadsIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            "downloads".hashCode(),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://downloads"),
                context,
                MainActivity::class.java,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // 点通知/岛跳转到该应用详情页；复用 MainActivity 的 market://details 深链
    private fun detailIntent(context: Context, packageName: String): PendingIntent {
        if (packageName.isBlank()) return openAppIntent(context)
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$packageName"),
            context,
            MainActivity::class.java,
        )
        return PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
