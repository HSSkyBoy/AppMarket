package top.app.market.patch

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Pure JVM/Android implementation of OPPO's OFbFv2_1 file-by-file patch container.
 *
 * The container turns selected APK ranges into a delta-friendly stream, applies a standard
 * HDIFF13 patch, then writes or raw-deflates selected ranges back to their final APK offsets.
 */
internal object OppoDeltaPatchEngine {
    fun applyFiles(patchFile: File, oldApk: File, outputApk: File) {
        val workDir = outputApk.parentFile ?: throw HDiffPatchException("output file has no parent directory")
        workDir.mkdirs()
        RandomAccessFile(outputApk, "rw").use { it.setLength(0L) }

        val counting = CountingInputStream(BufferedInputStream(FileInputStream(patchFile), BUFFER_SIZE))
        try {
            DataInputStream(counting).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) throw HDiffPatchException("not an OFbFv2_1 patch")
                input.readInt() // Reserved container version/flags.
                repeat(HEADER_LONG_COUNT) { input.readLong() } // Informational sizes; full APK verification is authoritative.
                val planCount = checkedCount(input.readInt(), "plan")
                repeat(planCount) { planIndex ->
                    val planLength = checkedLength(input.readLong(), "plan length")
                    val planStart = counting.count
                    val oldRanges = List(checkedCount(input.readInt(), "old range")) {
                        TransformRange(
                            type = input.readUnsignedByte(),
                            offset = checkedLength(input.readLong(), "old range offset"),
                            length = checkedLength(input.readLong(), "old range length"),
                        ).also(::validateTransformType)
                    }
                    val newRanges = List(checkedCount(input.readInt(), "new range")) {
                        OutputRange(
                            offset = checkedLength(input.readLong(), "new range offset"),
                            length = checkedLength(input.readLong(), "new range length"),
                            outputOffset = checkedLength(input.readLong(), "new range output offset"),
                            type = input.readUnsignedByte(),
                            level = input.readUnsignedByte(),
                            strategy = input.readUnsignedByte(),
                            nowrap = input.readUnsignedByte(),
                        ).also(::validateOutputRange)
                    }
                    input.readUnsignedByte() // Delta index; current format carries one HDIFF delta per plan.
                    val deltaLength = checkedLength(input.readLong(), "delta length")

                    val oldFriendly = File.createTempFile("oppo-$planIndex-", ".old", workDir)
                    val delta = File.createTempFile("oppo-$planIndex-", ".hdiff", workDir)
                    val newFriendly = File.createTempFile("oppo-$planIndex-", ".new", workDir)
                    try {
                        val oldInput = if (isWholeFileCopy(oldRanges, oldApk)) {
                            oldApk
                        } else {
                            transformOldApk(oldApk, oldRanges, oldFriendly)
                            oldFriendly
                        }
                        copyExactly(input, delta, deltaLength)
                        if (counting.count - planStart != planLength) {
                            throw HDiffPatchException(
                                "OFbFv2_1 plan length mismatch: ${counting.count - planStart}/$planLength",
                            )
                        }
                        HDiffCore.applyClassicFiles(delta, oldInput, newFriendly)
                        writeOutputRanges(newFriendly, newRanges, outputApk)
                    } finally {
                        oldFriendly.delete()
                        delta.delete()
                        newFriendly.delete()
                    }
                }
                if (counting.count != patchFile.length()) {
                    throw HDiffPatchException("trailing OFbFv2_1 data")
                }
            }
        } catch (error: HDiffPatchException) {
            throw error
        } catch (error: Throwable) {
            throw HDiffPatchException(error.message ?: "OFbFv2_1 patch failed", error)
        }
    }

    private fun transformOldApk(oldApk: File, ranges: List<TransformRange>, output: File) {
        FileOutputStream(output, false).buffered(BUFFER_SIZE).use { sink ->
            ranges.forEach { range ->
                requireFileRange(oldApk, range.offset, range.length, "old APK")
                if (range.type == TYPE_DEFLATE) {
                    inflateRawRange(oldApk, range, sink)
                } else {
                    RangeInputStream(oldApk, range.offset, range.length).use { source ->
                        copy(source, sink)
                        if (source.remaining != 0L) throw HDiffPatchException("short old APK range")
                    }
                }
            }
        }
    }

    private fun inflateRawRange(oldApk: File, range: TransformRange, output: OutputStream) {
        val source = RangeInputStream(oldApk, range.offset, range.length)
        val inflater = Inflater(true)
        try {
            InflaterInputStream(source, inflater, BUFFER_SIZE).use { inflated -> copy(inflated, output) }
            val consumed = range.length - source.remaining - inflater.remaining
            if (!inflater.finished() || consumed != range.length) {
                throw HDiffPatchException("invalid raw-deflate range in old APK")
            }
        } finally {
            inflater.end()
            source.close()
        }
    }

    private fun writeOutputRanges(sourceFile: File, ranges: List<OutputRange>, outputFile: File) {
        RandomAccessFile(outputFile, "rw").use { output ->
            ranges.forEach { range ->
                requireFileRange(sourceFile, range.offset, range.length, "delta-friendly output")
                output.seek(range.outputOffset)
                RangeInputStream(sourceFile, range.offset, range.length).use { source ->
                    if (range.type == TYPE_DEFLATE) {
                        val deflater = Deflater(range.level, range.nowrap != 0)
                        try {
                            deflater.setStrategy(range.strategy)
                            DeflaterOutputStream(
                                NonClosingRandomAccessOutput(output),
                                deflater,
                                BUFFER_SIZE,
                            ).use { compressed -> copy(source, compressed) }
                        } finally {
                            deflater.end()
                        }
                    } else {
                        copy(source, NonClosingRandomAccessOutput(output))
                    }
                    if (source.remaining != 0L) throw HDiffPatchException("short delta-friendly output range")
                }
            }
        }
    }

    private fun copyExactly(input: InputStream, output: File, length: Long) {
        FileOutputStream(output, false).buffered(BUFFER_SIZE).use { sink ->
            var remaining = length
            val buffer = ByteArray(BUFFER_SIZE)
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw HDiffPatchException("truncated OFbFv2_1 delta")
                if (count == 0) continue
                sink.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (count > 0) output.write(buffer, 0, count)
        }
    }

    private fun requireFileRange(file: File, offset: Long, length: Long, label: String) {
        if (offset < 0L || length < 0L || offset > file.length() || length > file.length() - offset) {
            throw HDiffPatchException("$label range is out of bounds")
        }
    }

    private fun validateTransformType(range: TransformRange) {
        if (range.type !in TYPE_COPY..TYPE_DEFLATE) throw HDiffPatchException("unsupported old range type ${range.type}")
    }

    private fun validateOutputRange(range: OutputRange) {
        if (range.type !in TYPE_COPY..TYPE_DEFLATE) throw HDiffPatchException("unsupported new range type ${range.type}")
        if (range.type == TYPE_DEFLATE) {
            if (range.level !in 0..9) throw HDiffPatchException("invalid deflate level ${range.level}")
            if (range.strategy !in 0..2) throw HDiffPatchException("invalid deflate strategy ${range.strategy}")
            if (range.nowrap !in 0..1) throw HDiffPatchException("invalid deflate nowrap ${range.nowrap}")
        }
    }

    private fun isWholeFileCopy(ranges: List<TransformRange>, file: File): Boolean =
        ranges.size == 1 && ranges.single() == TransformRange(TYPE_COPY, 0L, file.length())

    private fun checkedCount(value: Int, label: String): Int {
        if (value < 0 || value > MAX_RECORD_COUNT) throw HDiffPatchException("invalid $label count: $value")
        return value
    }

    private fun checkedLength(value: Long, label: String): Long {
        if (value < 0L) throw HDiffPatchException("negative $label")
        return value
    }

    private data class TransformRange(val type: Int, val offset: Long, val length: Long)

    private data class OutputRange(
        val offset: Long,
        val length: Long,
        val outputOffset: Long,
        val type: Int,
        val level: Int,
        val strategy: Int,
        val nowrap: Int,
    )

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, length).also { if (it > 0) count += it }

        override fun skip(length: Long): Long = super.skip(length).also { count += it }
    }

    private class RangeInputStream(file: File, offset: Long, length: Long) : InputStream() {
        private val input = RandomAccessFile(file, "r").also { it.seek(offset) }
        var remaining = length
            private set

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = input.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count
            return count
        }

        override fun close() = input.close()
    }

    private class NonClosingRandomAccessOutput(private val output: RandomAccessFile) : OutputStream() {
        override fun write(value: Int) = output.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
        override fun close() = Unit
    }

    private val MAGIC = "OFbFv2_1".encodeToByteArray()
    private const val HEADER_LONG_COUNT = 5
    private const val TYPE_COPY = 0
    private const val TYPE_DEFLATE = 1
    private const val BUFFER_SIZE = 32 * 1024
    private const val MAX_RECORD_COUNT = 1_000_000
}
