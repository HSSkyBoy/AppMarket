package top.app.market.data.remote.wandoujia

import top.app.market.data.platform.md5
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val WandoujiaCaller = "secret.wdj.client"
private const val WandoujiaSalt = "LVJd97AbRtikeYRRhi3ocdwSD"

internal fun wandoujiaDirectSign(data: JsonObject): String {
    val canonical = buildString {
        append(WandoujiaCaller)
        data.entries.sortedBy { it.key }.forEach { (key, value) ->
            append(key)
            append('=')
            append(wandoujiaSignValue(value))
        }
    }
    return wandoujiaMd5(canonical)
}

internal fun wandoujiaCombineSign(requestId: Long, data: JsonArray): String {
    val canonical = requestId.toString() + WandoujiaCaller + data.toString().replace("\\", "")
    return wandoujiaMd5(canonical)
}

private fun wandoujiaSignValue(value: JsonElement): String = when (value) {
    JsonNull -> "null"
    is JsonPrimitive -> value.contentOrNull ?: value.toString()
    is JsonArray -> value.joinToString(",") { wandoujiaSignValue(it) }
    is JsonObject -> value.toString()
}

private fun wandoujiaMd5(value: String): String =
    md5((value + WandoujiaSalt).encodeToByteArray()).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
