package top.app.market.data.remote.vivo

import top.app.market.domain.exception.MarketException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal val VivoJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseVivoObject(text: String): JsonObject =
    (VivoJson.parseToJsonElement(text) as? JsonObject)
        ?: throw MarketException("vivo 接口返回了非预期的响应格式")

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

internal fun JsonElement?.boolLike(key: String, default: Boolean = false): Boolean =
    prim(key)?.contentOrNull?.equals("true", ignoreCase = true)
        ?: default

internal fun JsonElement?.obj(key: String): JsonObject? =
    ((this as? JsonObject)?.get(key)) as? JsonObject

internal fun JsonElement?.arr(key: String): JsonArray? =
    ((this as? JsonObject)?.get(key)) as? JsonArray

internal fun JsonArray?.objAt(index: Int): JsonObject? =
    this?.getOrNull(index) as? JsonObject

internal fun JsonArray?.strAt(index: Int): String =
    (this?.getOrNull(index) as? JsonPrimitive)?.contentOrNull.orEmpty()
