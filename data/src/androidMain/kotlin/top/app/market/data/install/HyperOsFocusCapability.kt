package top.app.market.data.install

import android.content.Context
import android.os.Bundle
import android.provider.Settings

// HyperOS 焦点通知 / 超级岛能力探测；查询耗时，结果由调用方缓存。
object HyperOsFocusCapability {

    enum class FocusMode { OS2, OS3 }

    fun protocolVersion(context: Context): Int =
        runCatching {
            Settings.System.getInt(context.contentResolver, FOCUS_PROTOCOL_KEY, 0)
        }.getOrDefault(0)

    fun supportsIsland(): Boolean =
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            method.invoke(null, ISLAND_FEATURE_KEY, false) as Boolean
        }.getOrDefault(false)

    fun hasFocusPermission(context: Context): Boolean =
        runCatching {
            val extras = Bundle().apply { putString("package", context.packageName) }
            context.contentResolver.call(FOCUS_PERMISSION_AUTHORITY, "canShowFocus", null, extras)
                ?.getBoolean("canShowFocus", false) ?: false
        }.getOrDefault(false)

    /** 设备是否支持焦点通知 / 超级岛（不查权限）；用于设置项显隐。 */
    fun supportsFocus(context: Context): Boolean {
        val protocol = protocolVersion(context)
        return protocol == 2 || (protocol >= 3 && supportsIsland())
    }

    /** 是否为带岛胶囊的超级岛（OS3）模式；OS2 仅焦点通知无岛，用于设置项措辞。 */
    fun isIslandMode(context: Context): Boolean =
        protocolVersion(context) >= 3 && supportsIsland()

    data class Snapshot(
        val protocol: Int,
        val supportsIsland: Boolean,
        val hasPermission: Boolean,
    ) {
        val mode: FocusMode? = when {
            protocol >= 3 && supportsIsland -> FocusMode.OS3
            protocol == 2 -> FocusMode.OS2
            else -> null
        }
    }

    fun probe(context: Context): Snapshot = Snapshot(
        protocol = protocolVersion(context),
        supportsIsland = supportsIsland(),
        hasPermission = hasFocusPermission(context),
    )

    private const val FOCUS_PROTOCOL_KEY = "notification_focus_protocol"
    private const val ISLAND_FEATURE_KEY = "persist.sys.feature.island"
    private const val FOCUS_PERMISSION_AUTHORITY = "miui.statusbar.notification.public"
}
