package top.app.market.data.remote.xiaomi

import top.app.market.domain.exception.MarketException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient tree reader mirroring the previous `org.json` access patterns (`optString`/`optLong`/...),
 * so the port stays a mechanical 1:1 transform of the Xiaomi response parsing.
 */
internal val LenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

// 顶层非对象（null/[]/裸串，常见于风控响应）必须报错，否则检查更新会拿到空列表并覆盖缓存
internal fun parseJsonObject(text: String): JsonObject =
    (LenientJson.parseToJsonElement(text) as? JsonObject)
        ?: throw MarketException("接口返回了非预期的响应格式")

private fun JsonElement?.prim(key: String): JsonPrimitive? =
    ((this as? JsonObject)?.get(key)) as? JsonPrimitive

internal fun JsonElement?.str(key: String, default: String = ""): String =
    prim(key)?.contentOrNull ?: default

internal fun JsonElement?.long(key: String, default: Long = 0L): Long =
    prim(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: default

internal fun JsonElement?.int(key: String, default: Int = 0): Int =
    prim(key)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: default

internal fun JsonElement?.double(key: String, default: Double = 0.0): Double =
    prim(key)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: default

internal fun JsonElement?.bool(key: String, default: Boolean = false): Boolean =
    prim(key)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() } ?: default

internal fun JsonElement?.obj(key: String): JsonObject? =
    ((this as? JsonObject)?.get(key)) as? JsonObject

internal fun JsonElement?.arr(key: String): JsonArray? =
    ((this as? JsonObject)?.get(key)) as? JsonArray

internal fun JsonArray?.objAt(index: Int): JsonObject? =
    this?.getOrNull(index) as? JsonObject

internal fun JsonArray?.strAt(index: Int, default: String = ""): String =
    (this?.getOrNull(index) as? JsonPrimitive)?.contentOrNull ?: default

internal val JsonArray?.len: Int get() = this?.size ?: 0
