package top.app.market.data.remote

import io.ktor.http.encodeURLParameter
import kotlin.random.Random

/** 表单体 / 查询参数统一编码，供各来源协议复用。 */
internal fun urlEncodeParameters(values: Map<String, String>): String =
    values.entries.joinToString("&") { (key, value) ->
        "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
    }

internal fun Long.kibToBytes(): Long = if (this > 0L) this * 1024L else 0L

internal fun Long.secondsToMillis(): Long = if (this > 0L) this * 1000L else 0L

internal fun randomHex(bytes: Int): String = Random.nextBytes(bytes).joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
