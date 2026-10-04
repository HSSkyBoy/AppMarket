package top.app.market.data.platform

import android.util.Log

actual fun writeDebugLog(tag: String, message: String) {
    Log.i(tag, message)
}
