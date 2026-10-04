package top.app.market.data.remote.taptap

import top.app.market.data.platform.hmac
import top.app.market.data.platform.md5
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class TapTapSearchHit(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val icon: String,
    val rating: Double,
    val category: String,
)

internal data class TapTapSearchResponse(
    val hits: List<TapTapSearchHit>,
    val hasMore: Boolean,
    val sessionId: String,
)

private data class ProtoField(
    val number: Int,
    val wireType: Int,
    val bytes: ByteArray? = null,
    val value: Long? = null,
)

private class ProtoMessage(private val fields: List<ProtoField>) {
    fun first(number: Int): ProtoField? = fields.firstOrNull { it.number == number }
    fun all(number: Int): List<ProtoField> = fields.filter { it.number == number }
    fun child(field: ProtoField): ProtoMessage? = field.bytes?.let(::parseMessage)
    fun string(number: Int): String = first(number)?.bytes?.decodeToString().orEmpty()
    fun long(number: Int): Long = first(number)?.value ?: 0L
}

internal fun encodeTapTapSearchRequest(
    keyword: String,
    offset: Int,
    limit: Int,
    sessionId: String = "",
): ByteArray = buildList {
    add(protoString(2, "app"))
    add(protoString(3, keyword))
    add(protoString(5, "search"))
    if (offset > 0) add(protoVarint(6, offset.toLong()))
    add(protoVarint(7, limit.toLong()))
    if (sessionId.isNotBlank()) add(protoString(8, sessionId))
}.flattenBytes()

internal fun parseTapTapSearchResponse(bytes: ByteArray): TapTapSearchResponse {
    val envelope = parseMessage(bytes)
    val any = envelope.first(2)?.let(envelope::child)
        ?: return TapTapSearchResponse(emptyList(), false, "")
    val response = any.first(2)?.bytes?.let(::parseMessage)
        ?: return TapTapSearchResponse(emptyList(), false, "")
    val appGroup = response.all(1)
        .mapNotNull(response::child)
        .firstOrNull { it.string(1) == "app" }
        ?: return TapTapSearchResponse(emptyList(), false, "")
    val hits = appGroup.all(2).mapNotNull(appGroup::child).mapNotNull { item ->
        val app = item.first(9)?.let(item::child) ?: return@mapNotNull null
        val packageName = app.string(2)
        if (packageName.isBlank()) return@mapNotNull null
        val icon = app.first(7)?.let(app::child)
        TapTapSearchHit(
            appId = app.long(1),
            packageName = packageName,
            displayName = app.string(5).ifBlank { packageName },
            icon = icon?.string(1).orEmpty(),
            rating = app.string(11).toDoubleOrNull() ?: 0.0,
            category = app.first(8)?.let(app::child)?.string(2).orEmpty(),
        )
    }.distinctBy { it.packageName.lowercase() }
    return TapTapSearchResponse(
        hits = hits,
        hasMore = appGroup.string(7).isNotBlank(),
        sessionId = appGroup.string(5),
    )
}

@OptIn(ExperimentalEncodingApi::class)
internal fun tapTapProtobufSignature(
    method: String,
    encodedPathAndQuery: String,
    timestamp: String,
    nonce: String,
    body: ByteArray,
): String {
    val canonical = buildList {
        add(method.uppercase().encodeToByteArray())
        add("\n".encodeToByteArray())
        add(encodedPathAndQuery.encodeToByteArray())
        add("\n".encodeToByteArray())
        add("x-tap-nonce:$nonce\nx-tap-ts:$timestamp".encodeToByteArray())
        add("\n".encodeToByteArray())
        add(body)
        add("\n".encodeToByteArray())
    }.flattenBytes()
    return Base64.encode(
        hmac("HmacSHA256", TapTapProtobufSecret.encodeToByteArray(), canonical),
    )
}

internal fun tapTapFormSignature(fields: Map<String, String>, xUa: String): String {
    val canonical = (fields.map { (key, value) -> "$key=$value" } + "X-UA=$xUa")
        .sorted()
        .joinToString("&")
    return md5((canonical + TapTapFormSalt).encodeToByteArray()).toHex()
}

private fun parseMessage(bytes: ByteArray): ProtoMessage {
    val fields = ArrayList<ProtoField>()
    var offset = 0
    while (offset < bytes.size) {
        val key = readVarint(bytes, offset) ?: break
        offset = key.second
        val number = (key.first ushr 3).toInt()
        val wireType = (key.first and 7L).toInt()
        if (number <= 0) break
        when (wireType) {
            0 -> {
                val value = readVarint(bytes, offset) ?: break
                offset = value.second
                fields += ProtoField(number, wireType, value = value.first)
            }

            1 -> {
                if (offset + 8 > bytes.size) break
                offset += 8
                fields += ProtoField(number, wireType)
            }

            2 -> {
                val length = readVarint(bytes, offset) ?: break
                offset = length.second
                if (length.first > bytes.size - offset) break
                val end = offset + length.first.toInt()
                fields += ProtoField(number, wireType, bytes = bytes.copyOfRange(offset, end))
                offset = end
            }

            5 -> {
                if (offset + 4 > bytes.size) break
                offset += 4
                fields += ProtoField(number, wireType)
            }

            else -> break
        }
    }
    return ProtoMessage(fields)
}

private fun protoString(number: Int, value: String): ByteArray {
    val bytes = value.encodeToByteArray()
    return listOf(encodeVarint((number shl 3 or 2).toLong()), encodeVarint(bytes.size.toLong()), bytes)
        .flattenBytes()
}

private fun protoVarint(number: Int, value: Long): ByteArray =
    listOf(encodeVarint((number shl 3).toLong()), encodeVarint(value)).flattenBytes()

private fun encodeVarint(value: Long): ByteArray {
    var remaining = value
    val result = ArrayList<Byte>(10)
    while (remaining >= 0x80L) {
        result += ((remaining and 0x7fL) or 0x80L).toByte()
        remaining = remaining ushr 7
    }
    result += remaining.toByte()
    return result.toByteArray()
}

private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
    var value = 0L
    var shift = 0
    var offset = start
    while (offset < bytes.size && shift < 64) {
        val byte = bytes[offset++].toInt() and 0xff
        value = value or ((byte and 0x7f).toLong() shl shift)
        if (byte and 0x80 == 0) return value to offset
        shift += 7
    }
    return null
}

private fun List<ByteArray>.flattenBytes(): ByteArray {
    val result = ByteArray(sumOf(ByteArray::size))
    var offset = 0
    forEach { part ->
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val TapTapProtobufSecret = "aOqilgPqN4grnMG5VdLlhLptAU3WHorK"
private const val TapTapFormSalt = "PeCkE6Fu0B10Vm9BKfPfANwCUAn5POcs"
