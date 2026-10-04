package top.app.market.data.platform

actual fun writeDebugLog(tag: String, message: String) {
    println("$tag: $message")
}
