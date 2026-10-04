package top.app.market.patch

// 纯 Kotlin zstd 解压（RFC 8878 子集：仅解码、无外部字典），覆盖补丁容器所需全部特性。

private fun zfail(msg: String): Nothing = throw HDiffPatchException("zstd: $msg")

private fun u8(b: Buf, i: Int): Int {
    if (i < 0 || i >= b.size) zfail("input overrun")
    return b.u8(i)
}

private fun le16(b: Buf, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

private fun le24(b: Buf, i: Int): Int = le16(b, i) or (u8(b, i + 2) shl 16)

private fun le32(b: Buf, i: Int): Int = le24(b, i) or (u8(b, i + 3) shl 24)

/** 反向位流：从尾部哨兵位之下向前读，先读的 bit 是值的高位；下溢补零（bitsTop 变负）。 */
private class BackBits(private val b: Buf, private val from: Int, until: Int) {
    var bitsTop: Int
        private set

    init {
        var last = until - 1
        if (last < from) zfail("empty bitstream")
        val v = b.u8(last)
        if (v == 0) zfail("corrupt bitstream padding")
        bitsTop = (last - from) * 8 + (31 - v.countLeadingZeroBits())
    }

    fun peek(n: Int): Int = bitsAt(bitsTop - n, n).toInt()

    fun skip(n: Int) {
        bitsTop -= n
    }

    fun read(n: Int): Int {
        bitsTop -= n
        return bitsAt(bitsTop, n).toInt()
    }

    fun readLong(n: Int): Long {
        bitsTop -= n
        return bitsAt(bitsTop, n)
    }

    private var winBase = Int.MIN_VALUE / 2 // 窗口起始位（相对 from，字节对齐）
    private var win = 0L

    private fun bitsAt(pos: Int, n: Int): Long {
        if (n == 0) return 0L
        if (pos + n <= 0) return 0L
        if (pos < 0) return bitsAtSlow(pos, n)
        if (pos < winBase || pos + n > winBase + 64) {
            // 读取递减，窗口锚在 hi 顶部，向下可复用约 7 字节
            val baseByte = maxOf(0, ((pos + n + 7) ushr 3) - 8)
            winBase = baseByte shl 3
            var v = 0L
            for (k in 0 until 8) {
                val i = from + baseByte + k
                if (i < b.size) v = v or (b.u8(i).toLong() shl (k shl 3))
            }
            win = v
        }
        return (win ushr (pos - winBase)) and ((1L shl n) - 1)
    }

    /** 下溢路径（pos<0，低位补零）。 */
    private fun bitsAtSlow(pos: Int, n: Int): Long {
        val hi = pos + n
        var acc = 0L
        var bi = 0
        val biEnd = (hi + 7) ushr 3
        var sh = 0
        while (bi < biEnd) {
            acc = acc or (b.u8(from + bi).toLong() shl sh)
            sh += 8
            bi++
        }
        return (acc shl -pos) and ((1L shl n) - 1)
    }
}

/** 正向位流（FSE 表描述用），字节内低位在前，越界补零读。 */
private class FwdBits(private val b: Buf, private var bytePos: Int, private val end: Int) {
    private var bit = 0

    fun peek(n: Int): Int {
        var acc = 0L
        var sh = 0
        var bi = bytePos
        while (sh < n + bit && bi < end) {
            acc = acc or (b.u8(bi).toLong() shl sh)
            sh += 8
            bi++
        }
        return ((acc ushr bit) and ((1L shl n) - 1)).toInt()
    }

    fun skip(n: Int) {
        bit += n
        bytePos += bit ushr 3
        bit = bit and 7
    }

    fun read(n: Int): Int {
        val v = peek(n)
        skip(n)
        return v
    }

    fun alignedBytePos(): Int = if (bit == 0) bytePos else bytePos + 1
}

private class Fse(val sym: IntArray, val nb: IntArray, val base: IntArray, val al: Int) {
    companion object {
        fun rle(symbol: Int) = Fse(intArrayOf(symbol), intArrayOf(0), intArrayOf(0), 0)

        fun fromProbs(probs: IntArray, count: Int, al: Int): Fse {
            val size = 1 shl al
            val symbols = IntArray(size)
            var highThreshold = size - 1
            for (s in 0 until count) {
                if (probs[s] == -1) {
                    if (highThreshold < 0) zfail("fse low-prob overflow")
                    symbols[highThreshold--] = s
                }
            }
            val step = (size shr 1) + (size shr 3) + 3
            val mask = size - 1
            var pos = 0
            for (s in 0 until count) {
                val p = probs[s]
                if (p <= 0) continue
                repeat(p) {
                    symbols[pos] = s
                    do {
                        pos = (pos + step) and mask
                    } while (pos > highThreshold)
                }
            }
            if (pos != 0) zfail("fse spread incomplete")
            val next = IntArray(count)
            for (s in 0 until count) next[s] = if (probs[s] == -1) 1 else probs[s]
            val nb = IntArray(size)
            val base = IntArray(size)
            for (cell in 0 until size) {
                val s = symbols[cell]
                val x = next[s]++
                val bits = al - (31 - x.countLeadingZeroBits())
                nb[cell] = bits
                base[cell] = (x shl bits) - size
            }
            return Fse(symbols, nb, base, al)
        }
    }
}

/** FSE 表描述（正向流，结束后字节对齐），返回表与下一个字节位置。 */
private fun readFseTable(b: Buf, at: Int, end: Int, maxSym: Int, maxAl: Int): Pair<Fse, Int> {
    val fw = FwdBits(b, at, end)
    val al = 5 + fw.read(4)
    if (al > maxAl) zfail("accuracy log too large: $al")
    val size = 1 shl al
    var remaining = size + 1
    var threshold = size
    var nbBits = al + 1
    val probs = IntArray(maxSym + 1)
    var sym = 0
    while (remaining > 1) {
        while (remaining < threshold) {
            nbBits--
            threshold = threshold shr 1
        }
        val max = 2 * threshold - 1 - remaining
        var value = fw.peek(nbBits - 1)
        if (value < max) {
            fw.skip(nbBits - 1)
        } else {
            value = fw.read(nbBits)
            if (value >= threshold) value -= max
        }
        val prob = value - 1
        if (sym > maxSym) zfail("too many fse symbols")
        probs[sym++] = prob
        remaining -= if (prob < 0) 1 else prob
        if (prob == 0) {
            while (true) {
                val rep = fw.read(2)
                if (sym + rep > maxSym + 1) zfail("fse zero-run overflow")
                sym += rep
                if (rep != 3) break
            }
        }
    }
    if (remaining != 1) zfail("fse distribution mismatch")
    return Fse.fromProbs(probs, sym, al) to fw.alignedBytePos()
}

private class Huf(val maxBits: Int, val sym: ByteArray, val len: IntArray)

private fun buildHuf(weights: IntArray, n: Int): Huf {
    var sum = 0L
    for (i in 0 until n) {
        val w = weights[i]
        if (w > 12) zfail("huffman weight too large")
        if (w > 0) sum += 1L shl (w - 1)
    }
    if (sum == 0L) zfail("empty huffman weights")
    val maxBits = 64 - sum.countLeadingZeroBits()
    if (maxBits > 12) zfail("huffman table too deep")
    val rest = (1L shl maxBits) - sum
    if (rest <= 0 || rest and (rest - 1) != 0L) zfail("bad huffman weights")
    val lastWeight = (63 - rest.countLeadingZeroBits()) + 1
    val size = 1 shl maxBits
    val symT = ByteArray(size)
    val lenT = IntArray(size)
    var pos = 0
    for (w in 1..maxBits) {
        val span = 1 shl (w - 1)
        val bits = maxBits + 1 - w
        for (s in 0..n) {
            val ws = if (s == n) lastWeight else weights[s]
            if (ws != w) continue
            for (k in 0 until span) {
                symT[pos + k] = s.toByte()
                lenT[pos + k] = bits
            }
            pos += span
        }
    }
    if (pos != size) zfail("huffman table incomplete")
    return Huf(maxBits, symT, lenT)
}

/** Huffman 权重表：直接 4bit 编码或 FSE 双态流。 */
private fun readHufTable(b: Buf, at: Int, end: Int): Pair<Huf, Int> {
    val h0 = u8(b, at)
    var q = at + 1
    val weights = IntArray(256)
    var n: Int
    if (h0 < 128) {
        val wEnd = q + h0
        if (wEnd > end) zfail("huffman weights overrun")
        val (fse, streamStart) = readFseTable(b, q, wEnd, maxSym = 15, maxAl = 6)
        val bb = BackBits(b, streamStart, wEnd)
        var s1 = bb.read(fse.al)
        var s2 = bb.read(fse.al)
        n = 0
        while (true) {
            if (n > 253) zfail("huffman weights overrun")
            weights[n++] = fse.sym[s1]
            s1 = fse.base[s1] + bb.read(fse.nb[s1])
            if (bb.bitsTop < 0) {
                weights[n++] = fse.sym[s2]
                break
            }
            weights[n++] = fse.sym[s2]
            s2 = fse.base[s2] + bb.read(fse.nb[s2])
            if (bb.bitsTop < 0) {
                weights[n++] = fse.sym[s1]
                break
            }
        }
        q = wEnd
    } else {
        n = h0 - 127
        val bytes = (n + 1) / 2
        if (q + bytes > end) zfail("huffman weights overrun")
        for (k in 0 until n) {
            val byte = u8(b, q + (k shr 1))
            weights[k] = if (k and 1 == 0) byte ushr 4 else byte and 0xF
        }
        q += bytes
    }
    return buildHuf(weights, n) to q
}

private fun hufDecodeStream(b: Buf, from: Int, until: Int, huf: Huf, dst: ByteArray, dstFrom: Int, count: Int) {
    val bb = BackBits(b, from, until)
    var o = dstFrom
    repeat(count) {
        val idx = bb.peek(huf.maxBits)
        dst[o++] = huf.sym[idx]
        bb.skip(huf.len[idx])
    }
    if (bb.bitsTop < -huf.maxBits) zfail("huffman stream overrun")
}

// 序列码表（RFC 8878 §3.1.1.3.2.1.1）
private val LL_BITS = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
)
private val LL_BASE = intArrayOf(
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536,
)
private val ML_BITS = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
)
private val ML_BASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
    19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
    35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539,
)

// 预定义分布（RFC 8878 §3.1.1.3.2.2.1）
private val LL_DEFAULT = intArrayOf(
    4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
    2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
    -1, -1, -1, -1,
)
private val ML_DEFAULT = intArrayOf(
    1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
    -1, -1, -1, -1, -1,
)
private val OF_DEFAULT = intArrayOf(
    1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1,
)

private val LL_PREDEF by lazy { Fse.fromProbs(LL_DEFAULT, LL_DEFAULT.size, 6) }
private val ML_PREDEF by lazy { Fse.fromProbs(ML_DEFAULT, ML_DEFAULT.size, 6) }
private val OF_PREDEF by lazy { Fse.fromProbs(OF_DEFAULT, OF_DEFAULT.size, 5) }

private const val LITERALS_MAX = 128 * 1024

private class ZstdFrameDecoder(private val src: Buf, private val out: BufSink) {
    private var frameBase = 0
    private var huf: Huf? = null
    private var llFse: Fse? = null
    private var ofFse: Fse? = null
    private var mlFse: Fse? = null
    private val reps = intArrayOf(1, 4, 8)
    private val lits = ByteArray(LITERALS_MAX)

    fun decodeAll() {
        var i = 0
        while (i < src.size) {
            val magic = le32(src, i)
            i += 4
            if (magic.toLong() and 0xFFFFFFF0L == 0x184D2A50L) {
                val skip = le32(src, i)
                if (skip < 0) zfail("bad skippable frame")
                i += 4 + skip
                continue
            }
            if (magic != 0xFD2FB528.toInt()) zfail("bad magic")
            i = decodeFrame(i)
        }
    }

    private fun decodeFrame(start: Int): Int {
        var i = start
        val fhd = u8(src, i)
        i++
        if ((fhd ushr 3) and 1 != 0) zfail("reserved frame header bit")
        val singleSegment = (fhd ushr 5) and 1
        if (singleSegment == 0) i++
        i += intArrayOf(0, 1, 2, 4)[fhd and 3]
        i += when (fhd ushr 6) {
            0 -> singleSegment
            1 -> 2
            2 -> 4
            else -> 8
        }
        huf = null
        llFse = null
        ofFse = null
        mlFse = null
        reps[0] = 1
        reps[1] = 4
        reps[2] = 8
        frameBase = out.len
        while (true) {
            val bh = le24(src, i)
            i += 3
            val size = bh ushr 3
            when ((bh ushr 1) and 3) {
                0 -> {
                    if (i + size > src.size || out.len + size > out.buf.size) zfail("raw block overrun")
                    out.extendFrom(src, i, i + size)
                    i += size
                }

                1 -> {
                    if (out.len + size > out.buf.size) zfail("rle block overrun")
                    out.fill(u8(src, i), size)
                    i++
                }

                2 -> {
                    if (i + size > src.size) zfail("block overrun")
                    decodeCompressedBlock(i, i + size)
                    i += size
                }

                else -> zfail("reserved block type")
            }
            if (bh and 1 != 0) break
        }
        if ((fhd ushr 2) and 1 != 0) i += 4 // 跳过 xxhash 校验和（外层有整体长度/哈希校验）
        return i
    }

    private fun decodeCompressedBlock(from: Int, end: Int) {
        var p = from
        // 字面量段
        val t0 = u8(src, p)
        val litType = t0 and 3
        val sizeFormat = (t0 ushr 2) and 3
        val regen: Int
        var cmp = 0
        var fourStreams = false
        if (litType <= 1) {
            when (sizeFormat) {
                0, 2 -> {
                    regen = t0 ushr 3
                    p += 1
                }

                1 -> {
                    regen = (t0 ushr 4) or (u8(src, p + 1) shl 4)
                    p += 2
                }

                else -> {
                    regen = (t0 ushr 4) or (u8(src, p + 1) shl 4) or (u8(src, p + 2) shl 12)
                    p += 3
                }
            }
        } else {
            when (sizeFormat) {
                0, 1 -> {
                    val h = le24(src, p)
                    regen = (h ushr 4) and 0x3FF
                    cmp = (h ushr 14) and 0x3FF
                    fourStreams = sizeFormat == 1
                    p += 3
                }

                2 -> {
                    val h = le32(src, p)
                    regen = (h ushr 4) and 0x3FFF
                    cmp = (h ushr 18) and 0x3FFF
                    fourStreams = true
                    p += 4
                }

                else -> {
                    val h = le32(src, p).toLong() and 0xFFFFFFFFL or ((u8(src, p + 4).toLong()) shl 32)
                    regen = ((h ushr 4) and 0x3FFFF).toInt()
                    cmp = ((h ushr 22) and 0x3FFFF).toInt()
                    fourStreams = true
                    p += 5
                }
            }
        }
        if (regen > LITERALS_MAX) zfail("literals too large")
        when (litType) {
            0 -> {
                if (p + regen > end) zfail("raw literals overrun")
                src.copyInto(lits, 0, p, p + regen)
                p += regen
            }

            1 -> {
                lits.fill(u8(src, p).toByte(), 0, regen)
                p += 1
            }

            else -> {
                val litEnd = p + cmp
                if (litEnd > end) zfail("literals overrun")
                var q = p
                val table = if (litType == 2) {
                    val (h, q2) = readHufTable(src, q, litEnd)
                    huf = h
                    q = q2
                    h
                } else {
                    huf ?: zfail("treeless literals without table")
                }
                if (!fourStreams) {
                    hufDecodeStream(src, q, litEnd, table, lits, 0, regen)
                } else {
                    val s1 = le16(src, q)
                    val s2 = le16(src, q + 2)
                    val s3 = le16(src, q + 4)
                    q += 6
                    val total = litEnd - q
                    val s4 = total - s1 - s2 - s3
                    if (s4 <= 0) zfail("bad literal stream sizes")
                    val quarter = (regen + 3) / 4
                    val last = regen - 3 * quarter
                    if (last < 0) zfail("bad literal split")
                    hufDecodeStream(src, q, q + s1, table, lits, 0, quarter)
                    hufDecodeStream(src, q + s1, q + s1 + s2, table, lits, quarter, quarter)
                    hufDecodeStream(src, q + s1 + s2, q + s1 + s2 + s3, table, lits, 2 * quarter, quarter)
                    hufDecodeStream(src, q + s1 + s2 + s3, litEnd, table, lits, 3 * quarter, last)
                }
                p = litEnd
            }
        }
        decodeSequences(p, end, regen)
    }

    private fun decodeSequences(from: Int, end: Int, litLen: Int) {
        var p = from
        val b0 = u8(src, p)
        val nSeq: Int
        when {
            b0 == 0 -> {
                appendLiterals(0, litLen)
                return
            }

            b0 < 128 -> {
                nSeq = b0
                p += 1
            }

            b0 < 255 -> {
                nSeq = ((b0 - 128) shl 8) or u8(src, p + 1)
                p += 2
            }

            else -> {
                nSeq = le16(src, p + 1) + 0x7F00
                p += 3
            }
        }
        val modes = u8(src, p)
        p += 1
        if (modes and 3 != 0) zfail("reserved sequence mode bits")

        fun table(mode: Int, prev: Fse?, predef: Fse, maxSym: Int, maxAl: Int): Fse = when (mode) {
            0 -> predef
            1 -> {
                val s = u8(src, p)
                p += 1
                if (s > maxSym) zfail("rle symbol out of range")
                Fse.rle(s)
            }

            2 -> {
                val (f, next) = readFseTable(src, p, end, maxSym, maxAl)
                p = next
                f
            }

            else -> prev ?: zfail("repeat mode without previous table")
        }

        val ll = table(modes ushr 6, llFse, LL_PREDEF, 35, 9).also { llFse = it }
        val of = table((modes ushr 4) and 3, ofFse, OF_PREDEF, 31, 8).also { ofFse = it }
        val ml = table((modes ushr 2) and 3, mlFse, ML_PREDEF, 52, 9).also { mlFse = it }

        val bb = BackBits(src, p, end)
        var llS = bb.read(ll.al)
        var ofS = bb.read(of.al)
        var mlS = bb.read(ml.al)
        var litPos = 0
        for (seq in 0 until nSeq) {
            val ofCode = of.sym[ofS]
            val mlCode = ml.sym[mlS]
            val llCode = ll.sym[llS]
            // of→ml→ll 三段数值位一次读出再切片；总位数超窗口上限的罕见情形逐个读
            val nOf = ofCode
            val nMl = ML_BITS[mlCode]
            val nLl = LL_BITS[llCode]
            val ofExtra: Long
            val mlExtra: Int
            val llExtra: Int
            if (nOf + nMl + nLl <= 56) {
                val w = bb.readLong(nOf + nMl + nLl)
                ofExtra = w ushr (nMl + nLl)
                mlExtra = ((w ushr nLl) and ((1L shl nMl) - 1)).toInt()
                llExtra = (w and ((1L shl nLl) - 1)).toInt()
            } else {
                ofExtra = bb.readLong(nOf)
                mlExtra = bb.read(nMl)
                llExtra = bb.read(nLl)
            }
            val ofValue = (1L shl ofCode) + ofExtra
            val mlv = ML_BASE[mlCode] + mlExtra
            val llv = LL_BASE[llCode] + llExtra
            if (seq != nSeq - 1) {
                val nl = ll.nb[llS]
                val nm = ml.nb[mlS]
                val no = of.nb[ofS]
                val w2 = bb.read(nl + nm + no)
                llS = ll.base[llS] + (w2 ushr (nm + no))
                mlS = ml.base[mlS] + ((w2 ushr no) and ((1 shl nm) - 1))
                ofS = of.base[ofS] + (w2 and ((1 shl no) - 1))
            }
            val offset: Int
            if (ofValue > 3) {
                if (ofValue - 3 > Int.MAX_VALUE) zfail("offset too large")
                offset = (ofValue - 3).toInt()
                reps[2] = reps[1]
                reps[1] = reps[0]
                reps[0] = offset
            } else {
                when (ofValue.toInt() + if (llv == 0) 1 else 0) {
                    1 -> offset = reps[0]
                    2 -> {
                        offset = reps[1]
                        reps[1] = reps[0]
                        reps[0] = offset
                    }

                    3 -> {
                        offset = reps[2]
                        reps[2] = reps[1]
                        reps[1] = reps[0]
                        reps[0] = offset
                    }

                    else -> {
                        offset = reps[0] - 1
                        if (offset <= 0) zfail("zero repeat offset")
                        reps[2] = reps[1]
                        reps[1] = reps[0]
                        reps[0] = offset
                    }
                }
            }
            if (litPos + llv > litLen) zfail("literal overrun")
            appendLiterals(litPos, llv)
            litPos += llv
            if (offset > out.len - frameBase) zfail("offset out of window")
            if (out.len + mlv > out.buf.size) zfail("match overrun")
            out.extendFromSelf(out.len - offset, mlv)
        }
        if (bb.bitsTop != 0) zfail("sequence bitstream not fully consumed")
        appendLiterals(litPos, litLen - litPos)
    }

    private fun appendLiterals(from: Int, count: Int) {
        if (count == 0) return
        if (out.len + count > out.buf.size) zfail("literals overflow output")
        out.extend(lits, from, from + count)
    }
}

object ZstdPure : ZstdDecoder {
    override fun decode(src: Buf, out: BufSink) {
        ZstdFrameDecoder(src, out).decodeAll()
    }

    /** 全堆便捷入口：测试与小数据用。 */
    fun decode(src: ByteArray, expectedSize: Int): ByteArray {
        val sink = BufSink(Buf.allocate(expectedSize))
        decode(Buf.wrap(src), sink)
        if (sink.len != expectedSize) zfail("size mismatch: ${sink.len} != $expectedSize")
        return sink.toByteArray()
    }
}
