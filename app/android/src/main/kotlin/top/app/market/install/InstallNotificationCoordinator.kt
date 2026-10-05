package top.app.market.install

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import top.app.market.R
import top.app.market.data.install.HyperOsFocusCapability
import top.app.market.domain.model.download.DownloadPhase
import top.app.market.domain.model.download.DownloadState
import top.app.market.domain.repository.DownloadRepository
import top.app.market.domain.repository.InstallerPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// 安装通知的唯一出口。进程级单例、生命周期长于 InstallForegroundService，
// 以便提交安装后才到达的终态（成功 / 失败）在后台仍能通知到用户。
@SuppressLint("MissingPermission")
internal class InstallNotificationCoordinator(
    private val context: Context,
    private val downloads: DownloadRepository,
    private val preferences: InstallerPreferencesRepository,
    private val firewall: XmsfFirewallGate,
    private val icons: NotificationIconLoader,
) {
    private data class TrackedTask(val displayName: String, val phase: DownloadPhase, val icon: String)

    private data class FocusDecision(val mode: HyperOsFocusCapability.FocusMode, val needsBypass: Boolean)

    private sealed interface Event {
        data class States(val states: Map<String, DownloadState>) : Event
        data class Installed(val packageName: String) : Event
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tracked = HashMap<String, TrackedTask>()
    private val confirmIntents = ConcurrentHashMap<String, Intent>()
    private val userAborted = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var foregroundActive = false

    // 焦点通知能力（协议版本/岛支持/权限）快照，耗时探测结果按会话缓存
    @Volatile
    private var capabilitySnapshot: HyperOsFocusCapability.Snapshot? = null

    @Volatile
    private var lastCapabilityProbe = 0L
    private var lastSignature: List<String>? = null
    private var lastPhaseSignature: List<String>? = null
    private var lastOngoingTime = 0L

    init {
        InstallNotifications.createChannels(context)
        scope.launch { firewall.sweepLeakedRule() }
        scope.launch {
            merge(
                downloads.states.map { Event.States(it) },
                downloads.installedPackages.map { Event.Installed(it) },
            ).collect { event ->
                when (event) {
                    is Event.States -> onStates(event.states)
                    is Event.Installed -> onInstalled(event.packageName)
                }
            }
        }
    }

    fun foregroundNotification(): Notification {
        val active = downloads.states.value.values.filter { it.phase in ActivePhases }
        if (active.isEmpty()) return InstallNotifications.preparing(context)
        return InstallNotifications.ongoing(context, InstallNotifications.ongoingContent(context, active)).build()
    }

    fun onForegroundChanged(active: Boolean) {
        foregroundActive = active
        if (active) {
            lastSignature = null
            lastPhaseSignature = null
            lastOngoingTime = 0L
            // 重探能力，用户可能刚授予焦点通知权限；探测跨 Binder，故限一个时间窗
            if (SystemClock.elapsedRealtime() - lastCapabilityProbe > CAPABILITY_PROBE_INTERVAL_MS) {
                capabilitySnapshot = null
            }
        } else {
            notificationManager.cancel(InstallNotifications.ONGOING_ID)
        }
    }

    suspend fun handleUserConfirmation(packageName: String, displayName: String, confirmIntent: Intent?): Boolean {
        confirmIntent?.let { confirmIntents[packageName] = it }
        val autoLaunch = runCatching { preferences.autoLaunchConfirmUi() }.getOrDefault(true)
        if (autoLaunch && confirmIntent != null && appInForeground()) {
            val launched = runCatching {
                context.startActivity(Intent(confirmIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (launched) return true
        }
        val icon = downloads.states.value[packageName]?.icon?.let { icons.remoteIcon(it) }
        notificationManager.notify(
            InstallNotifications.alertId(packageName),
            InstallNotifications.awaitingConfirm(context, displayName, confirmIntent, icon),
        )
        return false
    }

    /** 用户在系统确认页主动取消：抑制紧随其后的失败态通知。 */
    fun onUserAborted(packageName: String) {
        userAborted.add(packageName)
    }

    private suspend fun onStates(states: Map<String, DownloadState>) {
        states.forEach { (packageName, state) ->
            val previous = tracked[packageName]?.phase
            tracked[packageName] = TrackedTask(state.displayName, state.phase, state.icon)
            if (previous != state.phase) {
                onPhaseTransition(packageName, previous, state)
            }
        }
        // 已从状态表消失且不再等待系统安装结果的条目直接清理
        tracked.entries.removeAll { (packageName, task) ->
            packageName !in states && task.phase !in PendingResultPhases
        }
        updateOngoing(states.values.filter { it.phase in ActivePhases }.sortedBy { it.packageName })
    }

    private suspend fun onPhaseTransition(packageName: String, previous: DownloadPhase?, state: DownloadState) {
        when (state.phase) {
            DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING, DownloadPhase.INSTALLING -> {
                // 重试 / 新任务：清掉遗留的确认或失败通知
                cancelAlert(packageName)
                userAborted.remove(packageName)
            }

            DownloadPhase.FAILED -> postFailure(state)

            DownloadPhase.DOWNLOADED -> when {
                state.errorMessage.isNotBlank() -> postFailure(state)
                previous == DownloadPhase.QUEUED || previous == DownloadPhase.DOWNLOADING -> {
                    val icon = icons.remoteIcon(state.icon)
                    postAlert(
                        packageName = packageName,
                        notification = InstallNotifications.downloadComplete(context, state.displayName, icon),
                        islandTitle = state.displayName,
                        islandContent = context.getString(R.string.notif_download_complete),
                        icon = icon,
                        dismissAfterSeconds = InstallNotifications.AUTO_HIDE_SECONDS,
                    )
                }

                // 用户从等待确认态取消：只撤掉确认通知
                else -> cancelAlert(packageName)
            }

            DownloadPhase.PAUSED -> cancelAlert(packageName)
            DownloadPhase.AWAITING_USER_ACTION -> Unit // 确认通知由 handleUserConfirmation 负责
        }
    }

    private suspend fun onInstalled(packageName: String) {
        val task = tracked.remove(packageName) ?: return
        confirmIntents.remove(packageName)
        userAborted.remove(packageName)
        cancelAlert(packageName)
        if (appInForeground()) return
        val icon = icons.installedIcon(packageName) ?: icons.remoteIcon(task.icon)
        postAlert(
            packageName = packageName,
            notification = InstallNotifications.installSuccess(context, packageName, task.displayName, icon),
            islandTitle = task.displayName,
            islandContent = context.getString(R.string.notif_install_success),
            icon = icon,
            dismissAfterSeconds = InstallNotifications.AUTO_HIDE_SECONDS,
        )
    }

    private suspend fun postFailure(state: DownloadState) {
        confirmIntents.remove(state.packageName)
        if (userAborted.remove(state.packageName)) {
            cancelAlert(state.packageName)
            return
        }
        if (appInForeground()) {
            cancelAlert(state.packageName)
            return
        }
        val icon = icons.remoteIcon(state.icon)
        postAlert(
            packageName = state.packageName,
            notification = InstallNotifications.installFailed(context, state.displayName, state.errorMessage, icon),
            islandTitle = state.displayName,
            islandContent = state.errorMessage.ifBlank { context.getString(R.string.notif_install_failed) },
            icon = icon,
        )
    }

    private suspend fun updateOngoing(active: List<DownloadState>) {
        if (!foregroundActive || active.isEmpty()) return
        val signature = active.map { "${it.packageName}:${it.phase}:${it.progress}" }
        if (signature == lastSignature) return
        val phaseSignature = active.map { "${it.packageName}:${it.phase}" }
        val now = SystemClock.elapsedRealtime()
        if (phaseSignature == lastPhaseSignature && now - lastOngoingTime < ONGOING_MIN_INTERVAL_MS) return
        lastSignature = signature
        lastPhaseSignature = phaseSignature
        lastOngoingTime = now

        val content = InstallNotifications.ongoingContent(context, active)
        val appIcon = content.iconUrl.takeIf { it.isNotBlank() }?.let { icons.remoteIcon(it) }
        val focus = resolveFocus()
        // 挂岛载荷时不申请 Live Update 晋升，二者互斥
        val builder = InstallNotifications.ongoing(context, content, appIcon, promoteLiveUpdate = focus == null)
        if (focus != null) {
            builder.addExtras(
                MiIslandExtras.build(
                    context = context,
                    mode = focus.mode,
                    title = content.title,
                    content = content.text,
                    percent = content.percent,
                    float = false,
                    appIcon = appIcon,
                    capsuleStatus = content.capsuleStatus,
                    actions = content.cancelablePackage?.let { packageName ->
                        listOf(
                            MiIslandExtras.Action(
                                key = "miui_action_cancel",
                                title = context.getString(R.string.notif_action_cancel),
                                pendingIntent = InstallNotifications.cancelIntent(context, packageName),
                            )
                        )
                    } ?: emptyList(),
                )
            )
        }
        notify(InstallNotifications.ONGOING_ID, builder.build(), focus?.needsBypass == true)
    }

    private suspend fun postAlert(
        packageName: String,
        notification: Notification,
        islandTitle: String,
        islandContent: String,
        icon: Bitmap? = null,
        dismissAfterSeconds: Int? = null,
    ) {
        val focus = resolveFocus()
        if (focus != null) {
            notification.extras.putAll(
                MiIslandExtras.build(
                    context = context,
                    mode = focus.mode,
                    title = islandTitle,
                    content = islandContent,
                    percent = null,
                    float = true,
                    appIcon = icon,
                    dismissAfterSeconds = dismissAfterSeconds,
                )
            )
        }
        notify(InstallNotifications.alertId(packageName), notification, focus?.needsBypass == true)
    }

    private suspend fun notify(id: Int, notification: Notification, needsBypass: Boolean) {
        if (!notificationManager.areNotificationsEnabled()) return
        if (needsBypass && firewall.available()) {
            firewall.blockDuring { notificationManager.notify(id, notification) }
        } else {
            notificationManager.notify(id, notification)
        }
    }

    // 协议支持即挂载荷（xmsf 不接受会自动忽略、降级普通通知，故 Xposed 解限也能渲染）；
    // needsBypass 仅决定是否触发断网旁路：已授权不需要，未授权时才在有旁路时强制放行。
    private suspend fun resolveFocus(): FocusDecision? {
        if (!runCatching { preferences.xiaomiIslandOptimizationEnabled() }.getOrDefault(false)) return null
        val snapshot = capabilitySnapshot ?: HyperOsFocusCapability.probe(context).also {
            capabilitySnapshot = it
            lastCapabilityProbe = SystemClock.elapsedRealtime()
        }
        val mode = snapshot.mode ?: return null
        return FocusDecision(mode, needsBypass = !snapshot.hasPermission)
    }

    private fun cancelAlert(packageName: String) {
        notificationManager.cancel(InstallNotifications.alertId(packageName))
    }

    private fun appInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private companion object {
        const val ONGOING_MIN_INTERVAL_MS = 500L
        const val CAPABILITY_PROBE_INTERVAL_MS = 60_000L
        val ActivePhases = setOf(DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING, DownloadPhase.INSTALLING)
        val PendingResultPhases = setOf(DownloadPhase.INSTALLING, DownloadPhase.AWAITING_USER_ACTION)
    }
}
