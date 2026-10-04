package top.app.market.data.remote.wandoujia

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

internal val WandoujiaJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseWandoujiaObject(text: String): JsonObject =
    (WandoujiaJson.parseToJsonElement(text) as? JsonObject)
        ?: throw MarketException("豌豆荚接口返回了非预期的响应格式")

private fun JsonElement?.primitive(key: String): JsonPrimitive? =
    ((this as? JsonObject)?.get(key)) as? JsonPrimitive

internal fun JsonElement?.wdjString(key: String, default: String = ""): String =
    primitive(key)?.contentOrNull ?: default

internal fun JsonElement?.wdjLong(key: String, default: Long = 0L): Long =
    primitive(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: default

internal fun JsonElement?.wdjInt(key: String, default: Int = 0): Int =
    primitive(key)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: default

internal fun JsonElement?.wdjDouble(key: String, default: Double = 0.0): Double =
    primitive(key)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: default

internal fun JsonElement?.wdjObject(key: String): JsonObject? =
    ((this as? JsonObject)?.get(key)) as? JsonObject

internal fun JsonElement?.wdjArray(key: String): JsonArray? =
    ((this as? JsonObject)?.get(key)) as? JsonArray

internal fun JsonArray?.wdjObjectAt(index: Int): JsonObject? =
    this?.getOrNull(index) as? JsonObject
