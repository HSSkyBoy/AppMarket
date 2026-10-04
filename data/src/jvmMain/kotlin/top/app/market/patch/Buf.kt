package top.app.market.patch

import java.io.File
import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

// 大缓冲统一走 ByteBuffer：堆数组或内存映射文件后备，堆占用与数据规模解耦。

/** 绝对读写配合每次复制时独立的定位游标，可安全地供互不重叠的并行分片使用。 */
class Buf(private val bb: ByteBuffer) {
    val size: Int = bb.capacity()
    fun u8(i: Int): Int = bb.get(i).toInt() and 0xFF

    fun set8(i: Int, v: Int) {
        bb.put(i, v.toByte())
    }

    fun getLong(i: Int): Long = bb.getLong(i)

    fun setLong(i: Int, v: Long) {
        bb.putLong(i, v)
    }

    fun copyInto(dest: ByteArray, destOffset: Int, from: Int, until: Int) {
        val pos = bb.duplicate()
        (pos as Buffer).position(from)
        pos.get(dest, destOffset, until - from)
    }

    fun copyFromArray(at: Int, src: ByteArray, from: Int, until: Int) {
        val pos = bb.duplicate()
        (pos as Buffer).position(at)
        pos.put(src, from, until - from)
    }

    fun slice(from: Int, until: Int): Buf {
        if (from < 0 || until < from || until > size) throw HDiffPatchException("slice out of range")
        val d = bb.duplicate()
        (d as Buffer).position(from)
        (d as Buffer).limit(until)
        return Buf(d.slice())
    }

    fun toByteArray(from: Int = 0, until: Int = size): ByteArray {
        val out = ByteArray(until - from)
        copyInto(out, 0, from, until)
        return out
    }

    fun asString(from: Int, until: Int): String = toByteArray(from, until).decodeToString()

    fun force() {
        val b = bb
        if (b is MappedByteBuffer) b.force()
    }

    companion object {
        fun wrap(b: ByteArray): Buf = Buf(ByteBuffer.wrap(b))

        fun allocate(size: Int): Buf = Buf(ByteBuffer.allocate(size))
    }
}

/** 顺序写游标（可从 [start] 起写分片）；容量即最终尺寸（各阶段头部已声明），越界视为补丁畸形。 */
class BufSink(val buf: Buf, start: Int = 0) {
    var len = start
        private set
    private val tmp = ByteArray(TRANSFER_CHUNK)

    private fun ensure(n: Int) {
        if (n < 0 || n > buf.size) throw HDiffPatchException("output overflow: $n > ${buf.size}")
    }

    fun push(v: Int) {
        ensure(len + 1)
        buf.set8(len, v)
        len++
    }

    fun u8(i: Int): Int = buf.u8(i)

    fun set8(i: Int, v: Int) {
        buf.set8(i, v)
    }

    fun truncate(n: Int) {
        len = n
    }

    fun extend(src: ByteArray, from: Int, until: Int) {
        val n = until - from
        ensure(len + n)
        if (n <= 16) {
            for (k in from until until) buf.set8(len++, src[k].toInt())
        } else {
            buf.copyFromArray(len, src, from, until)
            len += n
        }
    }

    fun extendFrom(src: Buf, from: Int, until: Int) {
        ensure(len + (until - from))
        var p = from
        while (p < until) {
            val n = minOf(tmp.size, until - p)
            src.copyInto(tmp, 0, p, p + n)
            buf.copyFromArray(len, tmp, 0, n)
            len += n
            p += n
        }
    }

    /** 自拷贝（zstd match）：源区可与写入区重叠；offset≥8 用 long 步进（容量留 7 字节松弛时尾部可越写，后续追加会覆盖）。 */
    fun extendFromSelf(from: Int, count: Int) {
        if (from < 0 || from >= len) throw HDiffPatchException("bad self-copy offset")
        ensure(len + count)
        val offset = len - from
        if (offset >= 8 && count <= 256 && len + count + 7 <= buf.size) {
            var s = from
            var d = len
            val e = len + count
            while (d < e) {
                buf.setLong(d, buf.getLong(s))
                s += 8
                d += 8
            }
            len = e
            return
        }
        if (count <= 32) {
            for (k in from until from + count) buf.set8(len++, buf.u8(k))
            return
        }
        var remaining = count
        while (remaining > 0) {
            val n = minOf(remaining, len - from, tmp.size)
            buf.copyInto(tmp, 0, from, from + n)
            buf.copyFromArray(len, tmp, 0, n)
            len += n
            remaining -= n
        }
    }

    fun fill(v: Int, count: Int) {
        ensure(len + count)
        tmp.fill(v.toByte(), 0, minOf(count, tmp.size))
        var remaining = count
        while (remaining > 0) {
            val n = minOf(remaining, tmp.size)
            buf.copyFromArray(len, tmp, 0, n)
            len += n
            remaining -= n
        }
    }

    fun toByteArray(): ByteArray = buf.toByteArray(0, until = len)

    private companion object {
        const val TRANSFER_CHUNK = 256 * 1024
    }
}

/** 映射文件分配器：临时文件挂在 [dir] 下、以 [prefix] 区分并发任务，close 时尽力删除。 */
internal class MappedBufAllocator(private val dir: File, private val prefix: String) : AutoCloseable {
    private val temps = mutableListOf<File>()

    fun mapFile(file: File, size: Long, readOnly: Boolean): Buf {
        if (size < 0 || size > Int.MAX_VALUE) throw HDiffPatchException("buffer too large: $size")
        RandomAccessFile(file, if (readOnly) "r" else "rw").use { raf ->
            if (!readOnly) raf.setLength(size)
            val mode = if (readOnly) FileChannel.MapMode.READ_ONLY else FileChannel.MapMode.READ_WRITE
            return Buf(raf.channel.map(mode, 0, size))
        }
    }

    fun temp(name: String, size: Long): Buf {
        val file = File(dir, "$prefix.$name.tmp")
        temps += file
        return mapFile(file, size, readOnly = false)
    }

    override fun close() {
        // Windows 上映射未回收前删除会失败，留给 deleteOnExit 兜底；Linux/Android 直接删除成功
        for (file in temps) if (!file.delete()) file.deleteOnExit()
        temps.clear()
    }
}
