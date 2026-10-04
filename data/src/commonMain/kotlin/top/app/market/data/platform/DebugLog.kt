package top.app.market.data.platform

import kotlin.concurrent.Volatile

/** 诊断日志开关，由 composition root 打开；关闭时消息 lambda 不求值。 */
object DebugLogging {
    @Volatile
    var enabled: Boolean = false
}

expect fun writeDebugLog(tag: String, message: String)

inline fun debugLog(tag: String, message: () -> String) {
    if (DebugLogging.enabled) writeDebugLog(tag, message())
}
