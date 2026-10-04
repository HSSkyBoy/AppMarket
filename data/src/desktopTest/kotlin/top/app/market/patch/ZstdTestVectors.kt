package top.app.market.patch

import java.io.ByteArrayOutputStream

/** ZstdPure 测试样本：形态确定性重建，压缩侧内嵌在 [ZstdFixtures]。 */
internal object ZstdTestVectors {

    /** 样本名 `<shape>-<level>[c]`，c 表示带校验和的帧。 */
    fun shapeOf(fixture: String): String = fixture.substring(0, fixture.lastIndexOf('-'))

    fun shape(name: String): ByteArray = when {
        name == "text" -> buildString {
            repeat(3000) { append("The quick brown fox jumps over the lazy dog #$it. 敏捷的棕色狐狸。") }
        }.encodeToByteArray()

        name == "zeros200k" -> ByteArray(200_000)
        name == "tiny" -> "hi".encodeToByteArray()
        name == "single" -> byteArrayOf(42)
        name == "empty" -> ByteArray(0)
        name.startsWith("mod7-") -> ByteArray(name.removePrefix("mod7-").toInt()) { (it % 7).toByte() }
        else -> error("unknown shape: $name")
    }

    /** 仅含 raw block 的合法 zstd 帧，测试无需编码器即可构造。 */
    fun rawFrame(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 32)
        out.write(0x28)
        out.write(0xB5)
        out.write(0x2F)
        out.write(0xFD)
        out.write(0xA0) // single segment + 4 字节内容长度
        for (k in 0 until 4) out.write((data.size ushr (8 * k)) and 0xFF)
        var i = 0
        do {
            val n = minOf(data.size - i, 65536)
            val bh = (n shl 3) or if (i + n == data.size) 1 else 0
            out.write(bh and 0xFF)
            out.write((bh ushr 8) and 0xFF)
            out.write((bh ushr 16) and 0xFF)
            out.write(data, i, n)
            i += n
        } while (i < data.size)
        return out.toByteArray()
    }
}
