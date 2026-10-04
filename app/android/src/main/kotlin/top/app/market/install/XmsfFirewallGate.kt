package top.app.market.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import kotlin.time.Duration.Companion.milliseconds

// 发通知的瞬间用 Shizuku 改防火墙规则临时断 xmsf 网，绕过其云端白名单校验，随后立即恢复。
// deny 是 system_server 里的持久状态，进程若在恢复前被杀会一直断到重启，故落标记供启动时补救。
internal class XmsfFirewallGate(private val context: Context) {

    private val mutex = Mutex()
    private val marker = context.getSharedPreferences(MARKER_STORE, Context.MODE_PRIVATE)

    private val xmsfUid: Int? by lazy {
        runCatching { context.packageManager.getPackageUid(XMSF_PACKAGE_NAME, 0) }.getOrNull()
    }

    fun available(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                xmsfUid != null &&
                runCatching {
                    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)

    /** 启动时调用：上次运行若未能恢复规则，这里补上。恢复失败保留标记，下次启动继续重试。 */
    suspend fun sweepLeakedRule() {
        val leakedUid = marker.getInt(MARKER_UID, NO_UID).takeIf { it != NO_UID } ?: return
        if (!available()) {
            Log.w(TAG, "Leaked firewall rule for uid $leakedUid pending, Shizuku unavailable")
            return
        }
        mutex.withLock { restore(leakedUid) }
    }

    suspend fun blockDuring(action: () -> Unit) {
        val uid = xmsfUid
        if (uid == null || !available()) {
            action()
            return
        }
        mutex.withLock {
            // 启动时的补救可能因 Shizuku 尚未就绪而落空，这里是天然的重试点
            marker.getInt(MARKER_UID, NO_UID).takeIf { it != NO_UID }?.let { restore(it) }
            val blocked = runCatching {
                // 标记必须先于规则落盘：反过来会留下无标记的遗留规则
                marker.edit(commit = true) { putInt(MARKER_UID, uid) }
                setNetworkingEnabled(uid, false)
            }.onFailure {
                Log.w(TAG, "Failed to block xmsf network, notifying directly", it)
                marker.edit(commit = true) { remove(MARKER_UID) }
            }.isSuccess
            try {
                action()
                if (blocked) delay(BLOCK_WINDOW_MS.milliseconds)
            } finally {
                if (blocked) withContext(NonCancellable) { restore(uid) }
            }
        }
    }

    private suspend fun restore(uid: Int) {
        repeat(RESTORE_ATTEMPTS) { attempt ->
            val restored = runCatching { setNetworkingEnabled(uid, true) }
                .onFailure { Log.e(TAG, "Failed to restore xmsf network (attempt ${attempt + 1})", it) }
                .isSuccess
            if (restored) {
                marker.edit(commit = true) { remove(MARKER_UID) }
                return
            }
            delay(RESTORE_RETRY_DELAY_MS.milliseconds)
        }
    }

    private fun setNetworkingEnabled(uid: Int, enabled: Boolean) {
        val stub = Class.forName("android.net.IConnectivityManager\$Stub")
        val manager = stub.getDeclaredMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(connectivityBinder()))
            ?: error("IConnectivityManager unavailable")
        val setRule = manager.javaClass.getMethod(
            "setUidFirewallRule",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        if (!enabled) {
            // 黑名单链需保证已启用；恢复时只清本 UID 规则，避免影响链上其他应用
            manager.javaClass.getMethod(
                "setFirewallChainEnabled",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(manager, FIREWALL_CHAIN_OEM_DENY_3, true)
            setRule.invoke(manager, FIREWALL_CHAIN_OEM_DENY_3, uid, FIREWALL_RULE_DENY)
        } else {
            setRule.invoke(manager, FIREWALL_CHAIN_OEM_DENY_3, uid, FIREWALL_RULE_DEFAULT)
        }
    }

    private fun connectivityBinder(): IBinder =
        Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, Context.CONNECTIVITY_SERVICE) as IBinder

    private companion object {
        const val TAG = "XmsfFirewallGate"
        const val XMSF_PACKAGE_NAME = "com.xiaomi.xmsf"
        const val MARKER_STORE = "xmsf_firewall"
        const val MARKER_UID = "denied_uid"
        const val NO_UID = -1
        const val BLOCK_WINDOW_MS = 100L
        const val RESTORE_ATTEMPTS = 3
        const val RESTORE_RETRY_DELAY_MS = 150L

        // FIREWALL_CHAIN_OEM_DENY_3 为黑名单链；POWERSAVE 等白名单链会误伤全局
        const val FIREWALL_CHAIN_OEM_DENY_3 = 9
        const val FIREWALL_RULE_DEFAULT = 0
        const val FIREWALL_RULE_DENY = 2
    }
}
