package top.app.market.data.remote.googleplay

/**
 * 最小 protobuf 线格式读写：只支持本模块用到的 varint / 定长 / 长度前缀字段，不依赖 protobuf 运行时。
 * 读取时按需把长度前缀字段当作嵌套消息解析，结构未知的响应也能逐层遍历。
 */
internal class PbMessage private constructor(private val fields: Map<Int, List<Any>>) {
    fun long(number: Int): Long = fields[number]?.firstOrNull() as? Long ?: 0L

    fun str(number: Int): String = (fields[number]?.firstOrNull() as? ByteArray)?.decodeToString().orEmpty()

    fun strs(number: Int): List<String> =
        fields[number].orEmpty().mapNotNull { (it as? ByteArray)?.decodeToString() }

    fun float(number: Int): Float = (fields[number]?.firstOrNull() as? Int)?.let(Float::fromBits) ?: 0f

    fun msg(number: Int): PbMessage? = (fields[number]?.firstOrNull() as? ByteArray)?.let(::tryParse)

    fun msgs(number: Int): List<PbMessage> =
        fields[number].orEmpty().mapNotNull { (it as? ByteArray)?.let(::tryParse) }

    /** 所有能解析成消息的非空长度前缀字段，用于遍历结构未知的响应。 */
    fun children(): List<PbMessage> = fields.values.flatten().mapNotNull { value ->
        (value as? ByteArray)?.takeIf { it.isNotEmpty() }?.let(::tryParse)
    }

    companion object {
        private const val MaxFields = 10_000

        fun tryParse(bytes: ByteArray): PbMessage? = try {
            parse(bytes)
        } catch (_: IllegalArgumentException) {
            null
        }

        fun parse(bytes: ByteArray): PbMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0

            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    require(pos < bytes.size && shift < 64) { "Truncated varint" }
                    val b = bytes[pos++].toInt()
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }

            var guard = 0
            while (pos < bytes.size) {
                require(++guard < MaxFields) { "Too many fields" }
                val tag = varint()
                val number = (tag ushr 3).toInt()
                require(number > 0) { "Bad field number" }
                val value: Any = when ((tag and 7).toInt()) {
                    0 -> varint()
                    1 -> {
                        require(pos + 8 <= bytes.size) { "Truncated fixed64" }
                        var v = 0L
                        for (i in 7 downTo 0) v = (v shl 8) or (bytes[pos + i].toLong() and 0xff)
                        pos += 8
                        v
                    }
                    2 -> {
                        val length = varint()
                        require(length in 0..(bytes.size - pos).toLong()) { "Bad length" }
                        bytes.copyOfRange(pos, pos + length.toInt()).also { pos += length.toInt() }
                    }
                    5 -> {
                        require(pos + 4 <= bytes.size) { "Truncated fixed32" }
                        var v = 0
                        for (i in 3 downTo 0) v = (v shl 8) or (bytes[pos + i].toInt() and 0xff)
                        pos += 4
                        v
                    }
                    else -> throw IllegalArgumentException("Unsupported wire type")
                }
                fields.getOrPut(number) { mutableListOf() }.add(value)
            }
            return PbMessage(fields)
        }
    }
}

internal class PbWriter {
    private var buffer = ByteArray(64)
    private var size = 0

    fun string(number: Int, value: String) = bytes(number, value.encodeToByteArray())

    fun bool(number: Int, value: Boolean) = varint(number, if (value) 1L else 0L)

    fun varint(number: Int, value: Long) {
        rawVarint((number.toLong() shl 3))
        rawVarint(value)
    }

    fun bytes(number: Int, value: ByteArray) {
        rawVarint((number.toLong() shl 3) or 2L)
        rawVarint(value.size.toLong())
        value.forEach(::put)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            put(((v and 0x7f) or 0x80).toInt().toByte())
            v = v ushr 7
        }
        put(v.toByte())
    }

    private fun put(byte: Byte) {
        if (size == buffer.size) buffer = buffer.copyOf(size * 2)
        buffer[size++] = byte
    }
}
