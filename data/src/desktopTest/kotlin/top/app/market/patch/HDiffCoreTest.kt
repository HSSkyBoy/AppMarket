package top.app.market.patch

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class HDiffCoreTest {

    // region 工具

    private fun deflateRaw(data: ByteArray, level: Int): ByteArray {
        val d = Deflater(level, true)
        d.setInput(data)
        d.finish()
        val out = ByteArray(data.size + data.size / 2 + 1024)
        var n = 0
        while (!d.finished()) {
            check(n < out.size) { "deflate buffer too small" }
            n += d.deflate(out, n, out.size - n)
        }
        d.end()
        return out.copyOf(n)
    }

    private fun heapSink(capacity: Int): BufSink = BufSink(Buf.allocate(capacity))

    /** 测试用可增长缓冲（回放归一化流需要随机回读）。 */
    private class GrowBuf(capacity: Int = 64) {
        var a = ByteArray(maxOf(capacity, 16))
            private set
        var len = 0
            private set

        private fun ensure(n: Int) {
            if (n > a.size) a = a.copyOf(maxOf(n, a.size * 2))
        }

        fun push(v: Int) {
            ensure(len + 1)
            a[len++] = v.toByte()
        }

        fun extend(src: ByteArray, from: Int, until: Int) {
            ensure(len + (until - from))
            src.copyInto(a, len, from, until)
            len += until - from
        }

        fun toByteArray(): ByteArray = a.copyOf(len)
    }

    /** 按归一化流语义回放出原始数据，验证 codec7Decode 的 token 语义。 */
    private fun replayNorm(norm: ByteArray): ByteArray {
        val out = GrowBuf(1024)
        var i = 0
        while (i < norm.size) {
            val b0 = norm[i++].toInt() and 0xFF
            val bf = b0 and 1
            val bt = b0 ushr 1
            if (bt == 0) {
                val ln = (norm[i].toInt() and 0xFF) or ((norm[i + 1].toInt() and 0xFF) shl 8)
                i += 2
                out.extend(norm, i, i + ln)
                i += ln
            } else {
                if (bt == 2) {
                    val hlit = norm[i].toInt() and 0xFF
                    val hdist = norm[i + 1].toInt() and 0xFF
                    i += 3 + (norm[i + 2].toInt() and 0xFF) + 4
                    val want = hlit + 257 + hdist + 1
                    var got = 0
                    while (got < want) {
                        val s = norm[i++].toInt() and 0xFF
                        got += when {
                            s <= 15 -> 1
                            s == 16 -> 3 + (norm[i++].toInt() and 0xFF)
                            s == 17 -> 3 + (norm[i++].toInt() and 0xFF)
                            else -> 11 + (norm[i++].toInt() and 0xFF)
                        }
                    }
                }
                while (true) {
                    val b = norm[i++].toInt() and 0xFF
                    if (b == EOB_OP) break
                    if (b == MATCH_OP) {
                        val len = (norm[i].toInt() and 0xFF) + 3
                        val d = ((norm[i + 1].toInt() and 0xFF) or ((norm[i + 2].toInt() and 0xFF) shl 8)) + 1
                        i += 3
                        repeat(len) { out.push(out.a[out.len - d].toInt()) }
                    } else {
                        out.push(if (b == ESC_OP) norm[i++].toInt() and 0xFF else b)
                    }
                }
            }
            if (bf != 0) break
        }
        return out.toByteArray()
    }

    private class Bytes {
        private val out = ByteArrayOutputStream()

        fun byte(v: Int) = apply { out.write(v) }

        fun raw(b: ByteArray) = apply { out.write(b) }

        /** HDiffPatch packUInt：高位组在前，续位 0x80。 */
        fun pack(v: Long) = apply {
            var t = 0
            while ((v ushr (7 * t)) > 0x7F) t++
            for (j in t downTo 0) {
                var g = (v ushr (7 * j)).toInt() and 0x7F
                if (j > 0) g = g or 0x80
                out.write(g)
            }
        }

        /** tagbit=1 变体：首字节 bit7=tag、bit6=续位、6 位负载。 */
        fun packTag1(v: Long, tag: Int) = apply {
            var t = 0
            while ((v ushr (7 * t)) > 0x3F) t++
            var first = (tag shl 7) or ((v ushr (7 * t)).toInt() and 0x3F)
            if (t > 0) first = first or 0x40
            out.write(first)
            for (j in t - 1 downTo 0) {
                var g = (v ushr (7 * j)).toInt() and 0x7F
                if (j > 0) g = g or 0x80
                out.write(g)
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private val textData = buildString {
        repeat(400) { append("The quick brown fox jumps over the lazy dog #$it. ") }
    }.encodeToByteArray()

    private val randomData = Random(42).nextBytes(4096)

    private fun deflateCases(): List<Pair<String, Pair<ByteArray, ByteArray>>> = listOf(
        "text/9" to (textData to deflateRaw(textData, 9)),
        "text/1" to (textData to deflateRaw(textData, 1)),
        "text/0-stored" to (textData to deflateRaw(textData, 0)),
        "random/9" to (randomData to deflateRaw(randomData, 9)),
        "small/9" to ("hello hello hello".encodeToByteArray() to deflateRaw("hello hello hello".encodeToByteArray(), 9)),
    )

    // endregion

    @Test
    fun codec7DecodeMatchesDeflater() {
        for ((name, case) in deflateCases()) {
            val (data, archive) = case
            val out = heapSink(data.size * 2 + archive.size * 2 + 4096)
            val endBit = codec7Decode(Buf.wrap(archive), 0L, archive.size.toLong() * 8, out)
            assertTrue(endBit <= archive.size.toLong() * 8, "case $name: endBit out of range")
            assertContentEquals(data, replayNorm(out.toByteArray()), "case $name: replay mismatch")
        }
    }

    @Test
    fun codec7EncodeRoundTrip() {
        for ((name, case) in deflateCases()) {
            val (data, archive) = case
            val probe = heapSink(data.size * 2 + archive.size * 2 + 4096)
            codec7Decode(Buf.wrap(archive), 0L, archive.size.toLong() * 8, probe)
            val norm = probe.toByteArray()
            val bw = BitWriter(heapSink(archive.size + 16))
            codec7Encode(Buf.wrap(norm), 0, norm.size, bw)
            bw.align()
            assertContentEquals(archive, bw.out.toByteArray(), "case $name: re-encode mismatch")
        }
    }

    @Test
    fun normalizeDenormalizeRoundTrip() {
        for ((name, case) in deflateCases()) {
            val (data, deflate) = case
            val prefix = byteArrayOf(0x11, 0x22, 0x33)
            val suffix = byteArrayOf(0x44, 0x55, 0x66, 0x77, 0x08)
            val archive = prefix + deflate + suffix
            val probe = heapSink(data.size * 2 + archive.size * 2 + 4096)
            val endBit = codec7Decode(Buf.wrap(archive), 24L, archive.size.toLong() * 8, probe)
            val f27 = if (endBit % 8 != 0L) archive[(endBit ushr 3).toInt()].toInt() and 0xFF else 0
            val rec = Record(
                startBit = 24L,
                endBit = endBit,
                normLen = probe.len.toLong(),
                codec = 7,
                f26 = 1,
                f27 = f27,
            )
            val normSink = heapSink(probe.len + archive.size)
            normalize(Buf.wrap(archive), listOf(rec), normSink)
            val norm = normSink.toByteArray()
            val denormSink = heapSink(archive.size + 16)
            denormalize(Buf.wrap(norm), listOf(rec), denormSink)
            assertContentEquals(archive, denormSink.toByteArray(), "case $name: normalize/denormalize mismatch")
        }
    }

    // old/new 样本：new = "XX"(字面量) + old[4,8)("BBBB") + old[12,16)+1("EEEE")
    private val oldSample = "AAAABBBBCCCCDDDD".encodeToByteArray()
    private val newSample = "XXBBBBEEEE".encodeToByteArray()

    private fun buildInnerDiff(): ByteArray {
        val covers = Bytes()
            .packTag1(4, 0).pack(2).pack(4)
            .packTag1(4, 0).pack(0).pack(4)
            .toByteArray()
        val rle = Bytes()
            .pack(4)
            .pack(4).raw(byteArrayOf(1, 1, 1, 1))
            .toByteArray()
        return Bytes()
            .pack(covers.size.toLong()).pack(rle.size.toLong())
            .raw(covers).raw(rle)
            .raw("XX".encodeToByteArray())
            .toByteArray()
    }

    @Test
    fun applyInnerCoversRleAndLiterals() {
        val out = heapSink(newSample.size)
        applyInner(Buf.wrap(buildInnerDiff()), Buf.wrap(oldSample), 2, out)
        assertContentEquals(newSample, out.toByteArray())
    }

    @Test
    fun applyPatchEndToEnd() {
        val diff = buildInnerDiff()
        val tblComp = ZstdTestVectors.rawFrame(ByteArray(0))
        val diffComp = ZstdTestVectors.rawFrame(diff)
        val patch = Bytes()
            .raw("SFPat21&zstd".encodeToByteArray()).byte(0)
            .pack(newSample.size.toLong()).pack(oldSample.size.toLong())
            .byte(1).byte(7)
            .pack(0).pack(0) // countA / countB
            .pack(0) // limitMemDecoded
            .pack(0).pack(0) // 两个可选块，均为空
            .pack(0) // tblUncompressed
            .pack(tblComp.size.toLong())
            .raw(tblComp)
            .raw("HDIFFSF20&zstd".encodeToByteArray()).byte(0)
            .pack(newSample.size.toLong()).pack(oldSample.size.toLong())
            .pack(2) // coverCount
            .pack(0) // stepMemSize
            .pack(diff.size.toLong())
            .pack(diffComp.size.toLong())
            .raw(diffComp)
            .toByteArray()
        val out = HDiffCore.apply(patch, oldSample, ZstdPure)
        assertContentEquals(newSample, out)
    }
}
