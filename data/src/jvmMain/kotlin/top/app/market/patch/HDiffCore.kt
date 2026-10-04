package top.app.market.patch

// SFPat21& 增量补丁应用；内层 HDIFFSF20 的 packUInt/cover/rle0 语义依据 MIT HDiffPatch。

import top.app.market.patch.HDiffCore.applyFiles
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class HDiffPatchException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun interface ZstdDecoder {
    fun decode(src: Buf, out: BufSink)
}

internal const val MATCH_OP = 0xD2
internal const val EOB_OP = 0xD3
internal const val ESC_OP = 0xD4

private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
private val LBASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51,
    59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
)
private val LEXT = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
    5, 5, 5, 5, 0,
)
private val DBASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385,
    513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
)
private val DEXT = intArrayOf(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
    11, 11, 12, 12, 13, 13,
)

private fun fail(msg: String): Nothing = throw HDiffPatchException(msg)

private fun lenSym(length: Int): Int {
    if (length >= 258) return 28
    var i = 28
    while (i > 0 && LBASE[i] > length) i--
    return i
}

private fun distSym(dist: Int): Int {
    var i = 29
    while (i > 0 && DBASE[i] > dist) i--
    return i
}

/** 位流读取（字节内低位在前）；越界读 0 而非抛错，畸形数据由上层长度校验拦截。 */
internal class BitReader(private val b: Buf, var p: Long) {
    private var winBase = Long.MIN_VALUE / 2
    private var win = 0L

    fun bits(n: Int): Int {
        val v = peek(n)
        p += n
        return v
    }

    fun peek(n: Int): Int {
        if (n == 0) return 0
        if (p < winBase || p + n > winBase + 64) reload()
        return ((win ushr (p - winBase).toInt()) and ((1L shl n) - 1)).toInt()
    }

    fun skip(n: Int) {
        p += n
    }

    private fun reload() {
        val base = p ushr 3
        winBase = base shl 3
        var v = 0L
        for (k in 0 until 8) {
            val i = base + k
            if (i < b.size) v = v or (b.u8(i.toInt()).toLong() shl (k shl 3))
        }
        win = v
    }
}

/** 位流写出；与既有字节按 OR 合并（回退重编码不清位），外部动 out 或改写 p 前必须 flush()。 */
internal class BitWriter(val out: BufSink) {
    var p: Long = out.len.toLong() * 8
    private var acc = 0L
    private var accBits = 0

    fun bits(v: Int, n: Int) {
        if (n == 0) return
        if (accBits + n > 64) drain()
        if (accBits == 0) accBits = (p and 7L).toInt() // 锚定字节边界，低位补零占位
        acc = acc or ((v.toLong() and ((1L shl n) - 1)) shl accBits)
        accBits += n
        p += n
    }

    fun huff(code: Int, n: Int) {
        bits(Integer.reverse(code) ushr (32 - n), n)
    }

    private fun writeByte(idx: Int, byte: Int) {
        if (idx < out.len) {
            if (byte != 0) out.set8(idx, out.u8(idx) or byte)
        } else {
            out.push(byte)
        }
    }

    private fun drain() {
        var idx = ((p - accBits) ushr 3).toInt()
        while (accBits >= 8) {
            writeByte(idx, (acc and 0xFF).toInt())
            acc = acc ushr 8
            accBits -= 8
            idx++
        }
    }

    fun flush() {
        drain()
        if (accBits > 0) {
            writeByte(((p - accBits) ushr 3).toInt(), (acc and 0xFF).toInt())
            acc = 0
            accBits = 0
        }
    }

    fun align() {
        flush()
        p = (p + 7) and 7L.inv()
        while ((p ushr 3) > out.len) out.push(0)
    }
}

private class Huff(lengths: IntArray) {
    val count = IntArray(16)
    val symbol: IntArray

    init {
        for (l in lengths) count[l]++
        count[0] = 0
        val offs = IntArray(16)
        for (l in 1 until 15) offs[l + 1] = offs[l] + count[l]
        symbol = IntArray(lengths.size)
        for (i in lengths.indices) {
            val l = lengths[i]
            if (l != 0) {
                symbol[offs[l]] = i
                offs[l]++
            }
        }
    }

    fun decode(br: BitReader): Int {
        val w = br.peek(15)
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..15) {
            code = code or ((w ushr (len - 1)) and 1)
            val cnt = count[len]
            if (code - first < cnt) {
                val si = index + code - first
                if (si < 0 || si >= symbol.size) fail("huffman symbol out of range")
                br.skip(len)
                return symbol[si]
            }
            index += cnt
            first = (first + cnt) shl 1
            code = code shl 1
        }
        fail("bad huffman code")
    }
}

private class HuffEnc(lengths: IntArray) {
    val code = IntArray(lengths.size)
    val len = IntArray(lengths.size)

    init {
        val cnt = IntArray(16)
        for (l in lengths) cnt[l]++
        cnt[0] = 0
        val next = IntArray(17)
        var c = 0
        for (l in 1..15) {
            c = (c + cnt[l - 1]) shl 1
            next[l] = c
        }
        for (i in lengths.indices) {
            val l = lengths[i]
            if (l != 0) {
                code[i] = next[l]
                next[l]++
                len[i] = l
            }
        }
    }
}

private fun fixedLit(): IntArray {
    val v = IntArray(288) { 8 }
    for (i in 144..255) v[i] = 9
    for (i in 256..279) v[i] = 7
    return v
}

internal class Record(
    var startBit: Long = 0,
    var endBit: Long = 0,
    var normLen: Long = 0,
    var codec: Int = 0,
    var f26: Int = 0,
    var f27: Int = 0,
)

internal class Header {
    var compressorZstd = false
    var newSize = 0L
    var oldSize = 0L
    val codecs = mutableListOf<Int>()
    var countA = 0L
    var countB = 0L
    var limitMemDecoded = 0L
    var tblUncompressed = 0L
    var tblCompressed = 0L
    var tablePos = 0
    var innerPos = 0
}

internal class InnerHeader {
    var compressTypeZstd = false
    var newDataSize = 0L
    var oldDataSize = 0L
    var coverCount = 0L
    var stepMemSize = 0L
    var uncompressedSize = 0L
    var compressedSize = 0L
    var diffDataPos = 0
}

internal class Reader(private val b: Buf, var i: Int) {
    fun u8(): Int {
        if (i >= b.size) fail("reader overrun")
        return b.u8(i++)
    }

    fun varint(): Long {
        var v = 0L
        while (true) {
            if (i >= b.size) fail("varint overrun")
            val x = b.u8(i++)
            v = (v shl 7) or (x and 0x7F).toLong()
            if (x and 0x80 == 0) return v
        }
    }

    fun varintTag(tagbit: Int): Long {
        if (i >= b.size) fail("varint overrun")
        val c = b.u8(i++)
        var v = (c and ((1 shl (7 - tagbit)) - 1)).toLong()
        if (c and (1 shl (7 - tagbit)) != 0) {
            while (true) {
                if (i >= b.size) fail("varint overrun")
                val x = b.u8(i++)
                v = (v shl 7) or (x and 0x7F).toLong()
                if (x and 0x80 == 0) break
            }
        }
        return v
    }
}

internal fun parseContainer(patch: Buf): Header {
    if (patch.size < 8 || patch.asString(0, 7) != "SFPat21" || patch.u8(7) != '&'.code) {
        fail("not a SFPat21 container")
    }
    val amp = 7
    var nul = amp
    while (nul < patch.size && patch.u8(nul) != 0) nul++
    if (nul >= patch.size) fail("no NUL")
    val h = Header()
    h.compressorZstd = patch.asString(amp + 1, nul) == "zstd"
    val r = Reader(patch, nul + 1)
    h.newSize = r.varint()
    h.oldSize = r.varint()
    val nc = r.u8()
    repeat(nc) {
        val c = r.u8()
        if (c != 7) fail("unsupported codec $c")
        h.codecs.add(c)
    }
    h.countA = r.varint()
    h.countB = r.varint()
    h.limitMemDecoded = r.varint()
    repeat(2) {
        val skip = r.varint()
        val next = r.i + skip
        if (next < r.i || next > patch.size) fail("optional block out of range")
        r.i = next.toInt()
    }
    h.tblUncompressed = r.varint()
    h.tblCompressed = r.varint()
    h.tablePos = r.i
    val innerPos = h.tablePos + h.tblCompressed
    if (innerPos > patch.size) fail("table region out of range")
    h.innerPos = innerPos.toInt()
    return h
}

internal fun parseInner(patch: Buf, pos: Int): InnerHeader {
    if (pos + 9 > patch.size || patch.asString(pos, pos + 9) != "HDIFFSF20") {
        fail("inner: not HDIFFSF20")
    }
    val amp = pos + 9
    if (amp >= patch.size || patch.u8(amp) != '&'.code) fail("inner: no '&'")
    var nul = amp
    while (nul < patch.size && patch.u8(nul) != 0) nul++
    if (nul >= patch.size) fail("inner: no NUL")
    val h = InnerHeader()
    h.compressTypeZstd = patch.asString(amp + 1, nul) == "zstd"
    val r = Reader(patch, nul + 1)
    h.newDataSize = r.varint()
    h.oldDataSize = r.varint()
    h.coverCount = r.varint()
    h.stepMemSize = r.varint()
    h.uncompressedSize = r.varint()
    h.compressedSize = r.varint()
    h.diffDataPos = r.i
    return h
}

internal fun decodeTables(blob: Buf, countA: Long, countB: Long): Pair<List<Record>, List<Record>> {
    val r = Reader(blob, 0)
    val tabs = ArrayList<List<Record>>(2)
    for (count in longArrayOf(countA, countB)) {
        if (count < 0 || count > blob.size) fail("table count out of range")
        val n = count.toInt()
        val v = List(n) { Record() }
        for (x in v) {
            x.codec = r.u8()
            x.f26 = r.u8()
        }
        for (x in v) x.f27 = r.u8()
        val p0 = LongArray(n)
        for (k in 0 until n) p0[k] = r.varint()
        var running = 0L
        for (k in 0 until n) {
            val gap = r.varint()
            val dlen = r.varint()
            v[k].startBit = p0[k] + running
            running += gap + p0[k]
            v[k].endBit = running
            v[k].normLen = dlen
        }
        tabs.add(v)
    }
    if (r.i != blob.size) fail("table blob not fully consumed: ${r.i}/${blob.size}")
    return tabs[0] to tabs[1]
}

internal fun codec7Decode(archive: Buf, startBit: Long, endBit: Long, out: BufSink): Long {
    val br = BitReader(archive, startBit)
    val fld = Huff(fixedLit())
    val fdd = Huff(IntArray(30) { 5 })
    // 攒批落盘；直写 out 前与返回前必须 sflush
    val stage = ByteArray(8192)
    var sp = 0
    fun sflush() {
        if (sp > 0) {
            out.extend(stage, 0, sp)
            sp = 0
        }
    }

    fun sput(v: Int) {
        if (sp == stage.size) sflush()
        stage[sp++] = v.toByte()
    }
    while (br.p < endBit) {
        val bf = br.bits(1)
        val bt = br.bits(2)
        sput((bt shl 1) or bf)
        if (bt == 0) {
            br.p = (br.p + 7) and 7L.inv()
            val base = (br.p ushr 3).toInt()
            if (base + 1 >= archive.size) fail("stored hdr oob")
            val ln = archive.u8(base) or (archive.u8(base + 1) shl 8)
            br.p += 32
            sput(ln and 0xFF)
            sput(ln ushr 8)
            val s = (br.p ushr 3).toInt()
            if (s + ln > archive.size) fail("stored body oob")
            sflush()
            out.extendFrom(archive, s, s + ln)
            br.p += ln.toLong() * 8
        } else {
            val lit: Huff
            val dist: Huff
            if (bt == 1) {
                lit = fld
                dist = fdd
            } else {
                val hlit = br.bits(5)
                val hdist = br.bits(5)
                val hclen = br.bits(4)
                sput(hlit)
                sput(hdist)
                sput(hclen)
                val cl = IntArray(19)
                for (k in 0 until hclen + 4) {
                    cl[k] = br.bits(3)
                    sput(cl[k])
                }
                val l = IntArray(19)
                for (k in 0 until hclen + 4) l[ORDER[k]] = cl[k]
                val clh = Huff(l)
                val want = hlit + 257 + hdist + 1
                val lens = IntArray(want)
                var got = 0
                while (got < want) {
                    val s = clh.decode(br)
                    sput(s)
                    when {
                        s <= 15 -> {
                            lens[got] = s
                            got++
                        }

                        s == 16 -> {
                            val r2 = br.bits(2)
                            sput(r2)
                            val prev = if (got > 0) lens[got - 1] else 0
                            repeat(3 + r2) {
                                if (got >= want) fail("cl overrun")
                                lens[got] = prev
                                got++
                            }
                        }

                        s == 17 -> {
                            val r2 = br.bits(3)
                            sput(r2)
                            repeat(3 + r2) {
                                if (got >= want) fail("cl overrun")
                                lens[got] = 0
                                got++
                            }
                        }

                        else -> {
                            val r2 = br.bits(7)
                            sput(r2)
                            repeat(11 + r2) {
                                if (got >= want) fail("cl overrun")
                                lens[got] = 0
                                got++
                            }
                        }
                    }
                }
                lit = Huff(lens.copyOfRange(0, hlit + 257))
                dist = Huff(lens.copyOfRange(hlit + 257, want))
            }
            while (true) {
                val s = lit.decode(br)
                if (s == 256) {
                    sput(EOB_OP)
                    break
                }
                if (s < 256) {
                    if (s == MATCH_OP || s == EOB_OP || s == ESC_OP) sput(ESC_OP)
                    sput(s)
                } else {
                    val li = s - 257
                    if (li >= 29) fail("bad length symbol")
                    val e = br.bits(LEXT[li])
                    val ds = dist.decode(br)
                    if (ds >= 30) fail("bad distance symbol")
                    val de = br.bits(DEXT[ds])
                    val length = LBASE[li] + e
                    val d = DBASE[ds] + de
                    sput(MATCH_OP)
                    sput(length - 3)
                    sput((d - 1) and 0xFF)
                    sput(((d - 1) ushr 8) and 0xFF)
                }
            }
        }
        if (bf != 0) break
    }
    sflush()
    return br.p
}

internal fun codec7Encode(norm: Buf, from: Int, until: Int, bw: BitWriter) {
    var i = from
    val cache = ByteArray(minOf(until - from, NORM_READ_CHUNK).coerceAtLeast(1))
    var cacheBase = -1
    var cacheEnd = -1
    fun at(idx: Int): Int {
        if (idx < from || idx >= until) fail("norm stream truncated")
        if (idx < cacheBase || idx >= cacheEnd) {
            cacheBase = idx
            cacheEnd = minOf(until, idx + cache.size)
            norm.copyInto(cache, 0, cacheBase, cacheEnd)
        }
        return cache[idx - cacheBase].toInt() and 0xFF
    }

    val fle = HuffEnc(fixedLit())
    val fde = HuffEnc(IntArray(30) { 5 })
    while (i < until) {
        val b0 = at(i)
        i++
        val bf = b0 and 1
        val bt = b0 ushr 1
        bw.bits(bf, 1)
        bw.bits(bt, 2)
        if (bt == 0) {
            val ln = at(i) or (at(i + 1) shl 8)
            i += 2
            bw.align()
            val nl = ln.inv() and 0xFFFF
            bw.out.push(ln and 0xFF)
            bw.out.push((ln ushr 8) and 0xFF)
            bw.out.push(nl and 0xFF)
            bw.out.push((nl ushr 8) and 0xFF)
            bw.p += 32
            if (i + ln > until) fail("norm stored oob")
            bw.out.extendFrom(norm, i, i + ln)
            bw.p += ln.toLong() * 8
            i += ln
        } else {
            val lit: HuffEnc
            val dist: HuffEnc
            if (bt == 1) {
                lit = fle
                dist = fde
            } else {
                val hlit = at(i)
                val hdist = at(i + 1)
                val hclen = at(i + 2)
                i += 3
                bw.bits(hlit, 5)
                bw.bits(hdist, 5)
                bw.bits(hclen, 4)
                val cl = IntArray(19)
                for (k in 0 until hclen + 4) {
                    cl[k] = at(i)
                    i++
                    bw.bits(cl[k], 3)
                }
                val l = IntArray(19)
                for (k in 0 until hclen + 4) l[ORDER[k]] = cl[k]
                val cle = HuffEnc(l)
                val want = hlit + 257 + hdist + 1
                val lens = IntArray(want)
                var got = 0
                while (got < want) {
                    val s = at(i)
                    i++
                    if (s > 18) fail("bad code-length symbol")
                    bw.huff(cle.code[s], cle.len[s])
                    when {
                        s <= 15 -> {
                            lens[got] = s
                            got++
                        }

                        s == 16 -> {
                            val r2 = at(i)
                            i++
                            bw.bits(r2, 2)
                            val prev = if (got > 0) lens[got - 1] else 0
                            repeat(3 + r2) {
                                if (got >= want) fail("cl overrun")
                                lens[got] = prev
                                got++
                            }
                        }

                        s == 17 -> {
                            val r2 = at(i)
                            i++
                            bw.bits(r2, 3)
                            repeat(3 + r2) {
                                if (got >= want) fail("cl overrun")
                                lens[got] = 0
                                got++
                            }
                        }

                        else -> {
                            val r2 = at(i)
                            i++
                            bw.bits(r2, 7)
                            repeat(11 + r2) {
                                if (got >= want) fail("cl overrun")
                                lens[got] = 0
                                got++
                            }
                        }
                    }
                }
                lit = HuffEnc(lens.copyOfRange(0, hlit + 257))
                dist = HuffEnc(lens.copyOfRange(hlit + 257, want))
            }
            while (true) {
                val b = at(i)
                if (b == EOB_OP) {
                    i++
                    bw.huff(lit.code[256], lit.len[256])
                    break
                }
                if (b == MATCH_OP) {
                    val length = at(i + 1) + 3
                    val d = (at(i + 2) or (at(i + 3) shl 8)) + 1
                    i += 4
                    val li = lenSym(length)
                    bw.huff(lit.code[257 + li], lit.len[257 + li])
                    bw.bits(length - LBASE[li], LEXT[li])
                    val ds = distSym(d)
                    bw.huff(dist.code[ds], dist.len[ds])
                    bw.bits(d - DBASE[ds], DEXT[ds])
                } else {
                    val v = if (b == ESC_OP) {
                        val v = at(i + 1)
                        i += 2
                        v
                    } else {
                        i++
                        b
                    }
                    bw.huff(lit.code[v], lit.len[v])
                }
            }
        }
        if (bf != 0) break
    }
}

/** record 布局：字节区间与 norm 流偏移前缀和，供顺序全量与并行分片共用。 */
internal class RecordLayout(recs: List<Record>, archiveSize: Int) {
    val byteStart = IntArray(recs.size)
    val byteEnd = IntArray(recs.size)
    val normOff = LongArray(recs.size + 1)

    init {
        var prevEnd = 0
        var off = 0L
        for (k in recs.indices) {
            val x = recs[k]
            if (x.codec != 7) fail("unsupported record codec ${x.codec}")
            if (x.f26 != 1) fail("unsupported record variant ${x.f26}")
            val s = ((x.startBit + 7) ushr 3).toInt()
            val e = ((x.endBit + 7) ushr 3).toInt()
            if (s < prevEnd || s > archiveSize) fail("bad record range")
            normOff[k] = off
            off += (s - prevEnd) + x.normLen
            byteStart[k] = s
            byteEnd[k] = e
            prevEnd = e
        }
        normOff[recs.size] = off
    }

    fun prevEnd(k: Int): Int = if (k == 0) 0 else byteEnd[k - 1]
}

/** 归一化 record 区间 [from, until)；[until]==recs.size 时携带归档尾段。 */
internal fun normalizeRange(archive: Buf, recs: List<Record>, layout: RecordLayout, from: Int, until: Int, out: BufSink) {
    var pos = layout.prevEnd(from)
    for (k in from until until) {
        val x = recs[k]
        val s = layout.byteStart[k]
        out.extendFrom(archive, pos, s)
        val before = out.len
        codec7Decode(archive, x.startBit, x.endBit, out)
        if ((out.len - before).toLong() != x.normLen) {
            fail("record decode length mismatch: ${out.len - before} != ${x.normLen}")
        }
        pos = layout.byteEnd[k]
    }
    if (until == recs.size) {
        if (pos > archive.size) fail("tail out of archive")
        out.extendFrom(archive, pos, archive.size)
    }
}

internal fun normalize(archive: Buf, recs: List<Record>, out: BufSink) {
    normalizeRange(archive, recs, RecordLayout(recs, archive.size), 0, recs.size, out)
}

/** 反归一化 record 区间 [from, until)（含各自前置 gap）；[until]==recs.size 时携带 norm 尾段。 */
internal fun denormalizeRange(norm: Buf, recs: List<Record>, layout: RecordLayout, from: Int, until: Int, out: BufSink) {
    val bw = BitWriter(out)
    var np = layout.normOff[from].toInt()
    var ap = layout.prevEnd(from)
    for (k in from until until) {
        val x = recs[k]
        val s = layout.byteStart[k]
        val e = layout.byteEnd[k]
        val gap = s - ap
        if (gap > 0) {
            bw.align()
            if (np + gap > norm.size) fail("gap out of norm")
            bw.out.extendFrom(norm, np, np + gap)
            bw.p += gap.toLong() * 8
        }
        np += gap
        if (bw.p > x.startBit) {
            bw.flush()
            bw.p = x.startBit
            bw.out.truncate(((x.startBit + 7) ushr 3).toInt())
        }
        if (x.normLen > Int.MAX_VALUE || np + x.normLen > norm.size) fail("record out of norm")
        val nl = x.normLen.toInt()
        codec7Encode(norm, np, np + nl, bw)
        np += nl
        val sh = (x.endBit and 7L).toInt()
        if (sh != 0) {
            bw.flush()
            bw.p = x.endBit
            val idx = (x.endBit ushr 3).toInt()
            while (bw.out.len <= idx) bw.out.push(0)
            bw.out.set8(idx, ((bw.out.u8(idx) and ((1 shl sh) - 1)) or ((x.f27 ushr sh) shl sh)) and 0xFF)
        }
        ap = e
    }
    if (until == recs.size) {
        bw.align()
        bw.out.extendFrom(norm, np, norm.size)
    } else {
        bw.flush()
    }
}

internal fun denormalize(norm: Buf, recs: List<Record>, out: BufSink) {
    denormalizeRange(norm, recs, RecordLayout(recs, out.buf.size), 0, recs.size, out)
}

internal class Rle0(private val b: Buf, private var i: Int, private val end: Int) {
    private var len0 = 0L
    private var lenv = 0L
    private var need0 = true
    private val cache = ByteArray(4096)
    private var cacheBase = -1
    private var cacheEnd = -1

    private fun data(idx: Int): Int {
        if (idx < cacheBase || idx >= cacheEnd) {
            cacheBase = idx
            cacheEnd = minOf(end, idx + cache.size)
            b.copyInto(cache, 0, cacheBase, cacheEnd)
        }
        return cache[idx - cacheBase].toInt() and 0xFF
    }

    private fun u(): Long {
        var v = 0L
        while (true) {
            if (i >= end) fail("rle0 overrun")
            val x = data(i++)
            v = (v shl 7) or (x and 0x7F).toLong()
            if (x and 0x80 == 0) return v
        }
    }

    fun add(buf: ByteArray, from: Int, until: Int) {
        val size = (until - from).toLong()
        var p = 0L
        while (true) {
            if (len0 > 0) {
                if (len0 >= size - p) {
                    len0 -= size - p
                    return
                }
                p += len0
                len0 = 0
                need0 = true
                lenv = u()
                continue
            }
            if (lenv > 0) {
                val t = minOf(lenv, size - p)
                if (i + t > end) fail("rle0 data overrun")
                for (k in 0 until t.toInt()) {
                    val dk = from + p.toInt() + k
                    buf[dk] = (buf[dk] + data(i + k)).toByte()
                }
                i += t.toInt()
                lenv -= t
                p += t
                if (p >= size) return
                need0 = false
                len0 = u()
                continue
            }
            if (need0) {
                need0 = false
                len0 = u()
            } else {
                need0 = true
                lenv = u()
            }
        }
    }
}

internal fun applyInner(diff: Buf, old: Buf, coverCount: Long, out: BufSink) {
    val r = Reader(diff, 0)
    var oldPos = 0L
    var newPos = 0L
    var length = 0L
    var lastNewEnd = 0L
    var remaining = coverCount
    val chunk = ByteArray(COVER_CHUNK)
    while (remaining > 0) {
        val bufCover = r.varint()
        val bufRle = r.varint()
        val base = r.i
        if (bufCover < 0 || bufRle < 0 || base + bufCover + bufRle > diff.size) fail("step buffers out of range")
        val coverEnd = base + bufCover.toInt()
        val covers = Buf.wrap(diff.toByteArray(base, coverEnd))
        val rle = Rle0(diff, coverEnd, coverEnd + bufRle.toInt())
        r.i = coverEnd + bufRle.toInt()
        val cr = Reader(covers, 0)
        while (cr.i < covers.size && remaining > 0) {
            val lastOldEnd = oldPos + length
            val lastNewEndC = newPos + length
            val sign = covers.u8(cr.i) ushr 7
            val v = cr.varintTag(1)
            oldPos = if (sign == 0) {
                lastOldEnd + v
            } else {
                if (v > lastOldEnd) fail("oldPos underflow")
                lastOldEnd - v
            }
            newPos = cr.varint() + lastNewEndC
            length = cr.varint()
            if (newPos > lastNewEnd) {
                val nlit = newPos - lastNewEnd
                if (nlit > Int.MAX_VALUE || r.i + nlit > diff.size) fail("literal overrun")
                out.extendFrom(diff, r.i, r.i + nlit.toInt())
                r.i += nlit.toInt()
            }
            remaining--
            if (length > 0) {
                val a = oldPos
                val b = oldPos + length
                if (a < 0 || b < a || b > old.size) fail("cover out of old range")
                // 覆盖段分块经堆内应用 RLE 后落盘，Rle0 状态跨块延续
                var cp = a.toInt()
                val ce = b.toInt()
                while (cp < ce) {
                    val n = minOf(chunk.size, ce - cp)
                    old.copyInto(chunk, 0, cp, cp + n)
                    rle.add(chunk, 0, n)
                    out.extend(chunk, 0, n)
                    cp += n
                }
            }
            lastNewEnd = newPos + length
        }
    }
}

private data class ClassicSection(
    val size: Long,
    val compressedSize: Long,
    var offset: Int = 0,
) {
    val storedSize: Long
        get() = compressedSize.takeIf { it > 0L } ?: size
}

private data class ClassicHeader(
    val compressor: String,
    val newSize: Long,
    val oldSize: Long,
    val coverCount: Long,
    val cover: ClassicSection,
    val rleControl: ClassicSection,
    val rleCode: ClassicSection,
    val newDataDiff: ClassicSection,
)

/** HDIFF13 byte-RLE stream. Literal regions consume RLE bytes but intentionally ignore them. */
private class ClassicRle(
    private val control: Buf,
    private val code: Buf,
) {
    private val controlReader = Reader(control, 0)
    private val codeReader = Reader(code, 0)
    private var runType = -1
    private var runRemaining = 0L
    private var repeatedValue = 0
    var consumed = 0L
        private set

    fun skip(length: Long) = consume(length, null, 0)

    fun addTo(bytes: ByteArray, length: Int) = consume(length.toLong(), bytes, 0)

    fun requireFinished(expectedSize: Long) {
        if (consumed != expectedSize || runRemaining != 0L ||
            controlReader.i != control.size || codeReader.i != code.size
        ) {
            fail("HDIFF13 RLE stream not fully consumed")
        }
    }

    private fun consume(length: Long, target: ByteArray?, targetOffset: Int) {
        if (length < 0L || targetOffset < 0 || (target != null && length > target.size - targetOffset)) {
            fail("HDIFF13 RLE range out of bounds")
        }
        var remaining = length
        var outputOffset = targetOffset
        while (remaining > 0L) {
            if (runRemaining == 0L) readRun()
            val count = minOf(remaining, runRemaining, Int.MAX_VALUE.toLong()).toInt()
            if (target != null) {
                when (runType) {
                    0 -> Unit
                    1 -> for (index in 0 until count) {
                        val at = outputOffset + index
                        target[at] = (target[at].toInt() - 1).toByte()
                    }

                    2 -> for (index in 0 until count) {
                        val at = outputOffset + index
                        target[at] = (target[at].toInt() + repeatedValue).toByte()
                    }

                    3 -> for (index in 0 until count) {
                        val at = outputOffset + index
                        target[at] = (target[at].toInt() + codeReader.u8()).toByte()
                    }

                    else -> fail("invalid HDIFF13 RLE type")
                }
            } else if (runType == 3) {
                val next = codeReader.i.toLong() + count
                if (next > code.size) fail("HDIFF13 RLE code overrun")
                codeReader.i = next.toInt()
            }
            remaining -= count
            runRemaining -= count
            outputOffset += count
            consumed += count
        }
    }

    private fun readRun() {
        if (controlReader.i >= control.size) fail("HDIFF13 RLE control overrun")
        runType = control.u8(controlReader.i) ushr 6
        runRemaining = controlReader.varintTag(2) + 1L
        if (runRemaining <= 0L) fail("invalid HDIFF13 RLE length")
        repeatedValue = if (runType == 2) codeReader.u8() else 0
    }
}

private fun parseClassicHeader(patch: Buf): ClassicHeader {
    val magic = "HDIFF13&"
    if (patch.size < magic.length + 1 || patch.asString(0, magic.length) != magic) {
        fail("not a HDIFF13 patch")
    }
    var terminator = magic.length
    while (terminator < patch.size && patch.u8(terminator) != 0) terminator++
    if (terminator >= patch.size) fail("HDIFF13 compressor has no terminator")
    val compressor = patch.asString(magic.length, terminator)
    val reader = Reader(patch, terminator + 1)
    val header = ClassicHeader(
        compressor = compressor,
        newSize = reader.varint(),
        oldSize = reader.varint(),
        coverCount = reader.varint(),
        cover = ClassicSection(reader.varint(), reader.varint()),
        rleControl = ClassicSection(reader.varint(), reader.varint()),
        rleCode = ClassicSection(reader.varint(), reader.varint()),
        newDataDiff = ClassicSection(reader.varint(), reader.varint()),
    )
    if (header.newSize < 0L || header.oldSize < 0L || header.coverCount < 0L) {
        fail("negative HDIFF13 header value")
    }
    var offset = reader.i.toLong()
    listOf(header.cover, header.rleControl, header.rleCode, header.newDataDiff).forEach { section ->
        if (section.size < 0L || section.compressedSize < 0L || section.storedSize > Int.MAX_VALUE) {
            fail("HDIFF13 section size out of range")
        }
        if (offset > Int.MAX_VALUE || offset + section.storedSize > patch.size) {
            fail("HDIFF13 section outside patch")
        }
        section.offset = offset.toInt()
        offset += section.storedSize
    }
    if (offset != patch.size.toLong()) fail("trailing or missing HDIFF13 data")
    if (header.compressor.isNotEmpty() && header.compressor != "zlib") {
        fail("unsupported HDIFF13 compressor: ${header.compressor}")
    }
    return header
}

private fun inflateZlib(source: Buf, output: BufSink) {
    if (source.size == 0) fail("empty HDIFF13 zlib section")
    // HDiffPatch's "zlib" plugin stores one signed windowBits byte before the stream and uses
    // raw deflate for the normal -9..-15 values. Accept a conventional zlib stream as well for
    // compatibility with older/non-reference producers.
    val windowBits = source.u8(0).toByte().toInt()
    val hasSavedWindowBits = windowBits in -15..-9 || windowBits in 9..15
    val inflater = Inflater(hasSavedWindowBits && windowBits < 0)
    val input = ByteArray(64 * 1024)
    val decoded = ByteArray(64 * 1024)
    var sourceOffset = if (hasSavedWindowBits) 1 else 0
    try {
        while (!inflater.finished()) {
            if (inflater.needsInput()) {
                if (sourceOffset >= source.size) fail("truncated HDIFF13 zlib section")
                val count = minOf(input.size, source.size - sourceOffset)
                source.copyInto(input, 0, sourceOffset, sourceOffset + count)
                sourceOffset += count
                inflater.setInput(input, 0, count)
            }
            val count = try {
                inflater.inflate(decoded)
            } catch (error: DataFormatException) {
                throw HDiffPatchException(error.message ?: "invalid HDIFF13 zlib section")
            }
            when {
                count > 0 -> output.extend(decoded, 0, count)
                inflater.needsDictionary() -> fail("HDIFF13 zlib dictionary is unsupported")
                !inflater.needsInput() && !inflater.finished() -> fail("stalled HDIFF13 zlib decoder")
            }
        }
        val consumed = sourceOffset - inflater.remaining
        if (consumed != source.size || output.len != output.buf.size) {
            fail("HDIFF13 zlib section size mismatch")
        }
    } finally {
        inflater.end()
    }
}

private fun classicSection(
    patch: Buf,
    section: ClassicSection,
    compressor: String,
    name: String,
    allocate: (String, Long) -> Buf,
): Buf {
    val source = patch.slice(section.offset, section.offset + section.storedSize.toInt())
    if (section.compressedSize == 0L) return source
    if (compressor != "zlib") fail("compressed HDIFF13 section without zlib")
    val decoded = allocate(name, section.size)
    inflateZlib(source, BufSink(decoded))
    return decoded
}

private fun applyClassicBufs(
    patch: Buf,
    old: Buf,
    allocate: (String, Long) -> Buf,
): BufSink {
    val header = parseClassicHeader(patch)
    if (header.oldSize != old.size.toLong()) fail("HDIFF13 old size mismatch")
    if (header.newSize > Int.MAX_VALUE) fail("HDIFF13 output is too large")
    if (header.coverCount > Int.MAX_VALUE) fail("HDIFF13 cover count is too large")
    val coverData = classicSection(patch, header.cover, header.compressor, "cover", allocate)
    val rleControl = classicSection(patch, header.rleControl, header.compressor, "rle-control", allocate)
    val rleCode = classicSection(patch, header.rleCode, header.compressor, "rle-code", allocate)
    val literals = classicSection(patch, header.newDataDiff, header.compressor, "literals", allocate)
    val covers = Reader(coverData, 0)
    val rle = ClassicRle(rleControl, rleCode)
    val output = BufSink(allocate("out", header.newSize))
    val transfer = ByteArray(256 * 1024)
    var oldEnd = 0L
    var newEnd = 0L
    var literalOffset = 0

    fun copyLiterals(length: Long) {
        if (length < 0L || length > Int.MAX_VALUE || literalOffset + length > literals.size) {
            fail("HDIFF13 literal range out of bounds")
        }
        rle.skip(length)
        output.extendFrom(literals, literalOffset, literalOffset + length.toInt())
        literalOffset += length.toInt()
    }

    repeat(header.coverCount.toInt()) {
        if (covers.i >= coverData.size) fail("HDIFF13 cover stream truncated")
        val negative = coverData.u8(covers.i) ushr 7 != 0
        val oldDelta = covers.varintTag(1)
        val oldPosition = if (negative) oldEnd - oldDelta else oldEnd + oldDelta
        val literalLength = covers.varint()
        val coverLength = covers.varint()
        if (oldPosition < 0L || oldPosition > old.size ||
            coverLength < 0L || coverLength > old.size.toLong() - oldPosition ||
            newEnd < 0L || newEnd > header.newSize ||
            literalLength < 0L || literalLength > header.newSize - newEnd ||
            coverLength > header.newSize - newEnd - literalLength
        ) {
            fail("HDIFF13 cover outside input or output")
        }
        copyLiterals(literalLength)
        var sourceOffset = oldPosition.toInt()
        var remaining = coverLength
        while (remaining > 0L) {
            val count = minOf(remaining, transfer.size.toLong()).toInt()
            old.copyInto(transfer, 0, sourceOffset, sourceOffset + count)
            rle.addTo(transfer, count)
            output.extend(transfer, 0, count)
            sourceOffset += count
            remaining -= count
        }
        oldEnd = oldPosition + coverLength
        newEnd += literalLength + coverLength
    }
    copyLiterals(header.newSize - newEnd)
    if (covers.i != coverData.size || literalOffset != literals.size || output.len.toLong() != header.newSize) {
        fail("HDIFF13 streams were not fully consumed")
    }
    rle.requireFinished(header.newSize)
    return output
}

private const val COVER_CHUNK = 256 * 1024
private const val NORM_READ_CHUNK = 64 * 1024

object HDiffCore {

    /** 全堆版本：单元测试与小数据用；生产走 [applyFiles]。 */
    fun apply(patch: ByteArray, old: ByteArray, zstd: ZstdDecoder): ByteArray {
        val out = applyBufs(Buf.wrap(patch), Buf.wrap(old), zstd) { _, size ->
            if (size > Int.MAX_VALUE) fail("buffer too large: $size")
            Buf.allocate(size.toInt())
        }
        return out.toByteArray()
    }

    /** 映射文件版本：堆占用与 APK 大小无关；临时文件挂在 [outputFile] 同目录。 */
    fun applyFiles(patchFile: File, oldFile: File, outputFile: File) {
        val dir = outputFile.parentFile ?: fail("output file has no parent directory")
        MappedBufAllocator(dir, outputFile.name).use { alloc ->
            val patch = alloc.mapFile(patchFile, patchFile.length(), readOnly = true)
            val old = alloc.mapFile(oldFile, oldFile.length(), readOnly = true)
            val out = applyBufs(patch, old, ZstdPure) { name, size ->
                if (name == "out") alloc.mapFile(outputFile, size, readOnly = false)
                else alloc.temp(name, size)
            }
            out.buf.force()
        }
    }

    /** OPPO file-by-file patches carry standard HDIFF13 sections compressed with zlib. */
    fun applyClassicFiles(patchFile: File, oldFile: File, outputFile: File) {
        val dir = outputFile.parentFile ?: fail("output file has no parent directory")
        MappedBufAllocator(dir, outputFile.name).use { allocator ->
            val patch = allocator.mapFile(patchFile, patchFile.length(), readOnly = true)
            val old = if (oldFile.length() == 0L) {
                Buf.allocate(0)
            } else {
                allocator.mapFile(oldFile, oldFile.length(), readOnly = true)
            }
            val out = applyClassicBufs(patch, old) { name, size ->
                when {
                    size < 0L || size > Int.MAX_VALUE -> fail("HDIFF13 buffer too large: $size")
                    size == 0L -> Buf.allocate(0)
                    name == "out" -> allocator.mapFile(outputFile, size, readOnly = false)
                    else -> allocator.temp("hdiff13-$name", size)
                }
            }
            out.buf.force()
        }
    }

    private fun applyBufs(patch: Buf, old: Buf, zstd: ZstdDecoder, alloc: (String, Long) -> Buf): BufSink {
        val h = parseContainer(patch)
        if (old.size.toLong() != h.oldSize) fail("old size mismatch")
        if (!h.compressorZstd) fail("unsupported compressor")

        val tblSrc = slice(patch, h.tablePos, h.tblCompressed, "table region out of range")
        if (h.tblUncompressed > Int.MAX_VALUE) fail("decoded block too large")
        val tbl = BufSink(Buf.allocate(h.tblUncompressed.toInt()))
        zstdDecode(zstd, tblSrc, tbl)
        val (ta, tb) = decodeTables(tbl.buf, h.countA, h.countB)

        val ih = parseInner(patch, h.innerPos)
        if (!ih.compressTypeZstd) fail("unsupported inner compressor")
        val diffSrc = slice(patch, ih.diffDataPos, ih.compressedSize, "diff region out of range")
        val diff = BufSink(alloc("diff", ih.uncompressedSize))

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(cores)
        try {
            // diff 解压与 normalize 无数据依赖，并发执行
            val diffTask = pool.submit<Unit> { zstdDecode(zstd, diffSrc, diff) }

            val tbLayout = RecordLayout(tb, old.size)
            val noldExpected = tbLayout.normOff[tb.size] + (old.size - tbLayout.prevEnd(tb.size))
            if (noldExpected != ih.oldDataSize) fail("normalized old size mismatch")
            val noldBuf = alloc("nold", ih.oldDataSize)
            runPartitioned(pool, tb, tbLayout, cores) { a, b ->
                val sink = BufSink(noldBuf.slice(0, noldBuf.size), start = tbLayout.normOff[a].toInt())
                normalizeRange(old.slice(0, old.size), tb, tbLayout, a, b, sink)
                val end = if (b == tb.size) noldExpected else tbLayout.normOff[b]
                if (sink.len.toLong() != end) fail("normalize partition length mismatch: ${sink.len} != $end")
            }
            joinTask(diffTask)

            val nnew = BufSink(alloc("nnew", ih.newDataSize))
            applyInner(diff.buf, noldBuf, ih.coverCount, nnew)
            if (nnew.len.toLong() != ih.newDataSize) fail("normalized new size mismatch")

            val outBuf = alloc("out", h.newSize)
            val taLayout = RecordLayout(ta, outBuf.size)
            val outExpected = taLayout.prevEnd(ta.size) + (nnew.len - taLayout.normOff[ta.size])
            if (outExpected != h.newSize) fail("new size mismatch")
            runPartitioned(pool, ta, taLayout, cores) { a, b ->
                val sink = BufSink(outBuf.slice(0, outBuf.size), start = taLayout.prevEnd(a))
                denormalizeRange(nnew.buf.slice(0, nnew.buf.size), ta, taLayout, a, b, sink)
                val end = if (b == ta.size) outExpected else taLayout.prevEnd(b).toLong()
                if (sink.len.toLong() != end) fail("denormalize partition length mismatch: ${sink.len} != $end")
            }
            return BufSink(outBuf, start = h.newSize.toInt())
        } finally {
            pool.shutdown()
        }
    }

    /** 分片起点下标；切分点须无跨片共享输出字节（有 gap 或 startBit 字节对齐）。 */
    private fun partitionCuts(recs: List<Record>, layout: RecordLayout, cores: Int): List<Int> {
        val total = layout.normOff[recs.size]
        val parts = minOf(cores.toLong(), maxOf(1L, total / PARALLEL_MIN_CHUNK)).toInt()
        val cuts = mutableListOf(0)
        if (parts > 1 && recs.isNotEmpty()) {
            val target = total / parts
            var threshold = target
            for (k in 1 until recs.size) {
                if (layout.normOff[k] < threshold) continue
                if (layout.byteStart[k] > layout.byteEnd[k - 1] || (recs[k].startBit and 7L) == 0L) {
                    cuts.add(k)
                    if (cuts.size == parts) break
                    threshold = layout.normOff[k] + target
                }
            }
        }
        return cuts
    }

    private fun runPartitioned(
        pool: ExecutorService,
        recs: List<Record>,
        layout: RecordLayout,
        cores: Int,
        body: (from: Int, until: Int) -> Unit,
    ) {
        val cuts = partitionCuts(recs, layout, cores)
        if (cuts.size == 1) {
            body(0, recs.size)
            return
        }
        val futures = cuts.mapIndexed { ci, a ->
            val b = if (ci + 1 < cuts.size) cuts[ci + 1] else recs.size
            pool.submit<Unit> { body(a, b) }
        }
        var error: Throwable? = null
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) {
                if (error == null) error = e.cause ?: e
            }
        }
        error?.let { throw it }
    }

    private fun joinTask(task: Future<Unit>) {
        try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun slice(b: Buf, pos: Int, len: Long, msg: String): Buf {
        if (pos < 0 || len < 0 || pos + len > b.size) fail(msg)
        return b.slice(pos, pos + len.toInt())
    }

    private fun zstdDecode(zstd: ZstdDecoder, src: Buf, out: BufSink) {
        zstd.decode(src, out)
        if (out.len != out.buf.size) fail("zstd size mismatch")
    }

    private const val PARALLEL_MIN_CHUNK = 8L shl 20
}
