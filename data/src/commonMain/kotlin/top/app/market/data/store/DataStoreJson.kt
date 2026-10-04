package top.app.market.data.store

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal fun JsonElement?.primitive(key: String) = ((this as? JsonObject)?.get(key)) as? JsonPrimitive
internal fun JsonElement?.str(key: String, default: String = "") = primitive(key)?.contentOrNull ?: default
internal fun JsonElement?.long(key: String, default: Long = 0L) = primitive(key)?.contentOrNull?.toLongOrNull() ?: default
internal fun JsonElement?.double(key: String, default: Double = 0.0) = primitive(key)?.contentOrNull?.toDoubleOrNull() ?: default
internal fun JsonElement?.bool(key: String, default: Boolean = false) = primitive(key)?.contentOrNull?.toBooleanStrictOrNull() ?: default

@OptIn(ExperimentalTime::class)
internal fun epochMillis() = Clock.System.now().toEpochMilliseconds()
