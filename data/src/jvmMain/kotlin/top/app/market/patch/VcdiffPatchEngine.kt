package top.app.market.patch

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * AppMarket's own RFC 3284 VCDIFF decoder.
 *
 * It deliberately accepts only the standard instruction table and uncompressed sections. A
 * server response using an application code table or a secondary compressor fails closed and the
 * install pipeline downloads the complete APK instead.
 */
internal object VcdiffPatchEngine {
    fun applyFiles(patchFile: File, oldFile: File, outputFile: File) {
        if (!oldFile.isFile || !oldFile.canRead()) throw HDiffPatchException("VCDIFF base file is unreadable")
        try {
            BufferedInputStream(FileInputStream(patchFile), BUFFER_SIZE).use { input ->
                val reader = StreamReader(input)
                MAGIC.forEach { expected ->
                    if (reader.byte() != expected) throw HDiffPatchException("not an RFC 3284 VCDIFF patch")
                }
                val version = reader.byte()
                if (version != 0) throw HDiffPatchException("unsupported VCDIFF version: $version")
                val headerIndicator = reader.byte()
                if (headerIndicator and HEADER_RESERVED_MASK != 0) {
                    throw HDiffPatchException("unsupported VCDIFF header indicator: $headerIndicator")
                }
                if (headerIndicator and VCD_DECOMPRESS != 0) {
                    val compressor = reader.byte()
                    throw HDiffPatchException("unsupported VCDIFF secondary compressor: $compressor")
                }
                if (headerIndicator and VCD_CODETABLE != 0) {
                    throw HDiffPatchException("custom VCDIFF code tables are not supported")
                }
                outputFile.parentFile?.mkdirs()
                RandomAccessFile(oldFile, "r").use { old ->
                    RandomAccessFile(outputFile, "rw").use { output ->
                        output.setLength(0L)
                        while (reader.hasMore()) decodeWindow(reader, old, output)
                    }
                }
            }
        } catch (error: HDiffPatchException) {
            outputFile.delete()
            throw error
        } catch (error: Throwable) {
            outputFile.delete()
            throw HDiffPatchException(error.message ?: "VCDIFF patch failed", error)
        }
    }

    private fun decodeWindow(reader: StreamReader, old: RandomAccessFile, output: RandomAccessFile) {
        val windowIndicator = reader.byte()
        if (windowIndicator and WINDOW_RESERVED_MASK != 0 ||
            windowIndicator and VCD_SOURCE != 0 && windowIndicator and VCD_TARGET != 0
        ) {
            throw HDiffPatchException("invalid VCDIFF window indicator: $windowIndicator")
        }
        val sourceLength: Long
        val sourcePosition: Long
        when {
            windowIndicator and (VCD_SOURCE or VCD_TARGET) != 0 -> {
                sourceLength = reader.varInt("source segment length")
                sourcePosition = reader.varInt("source segment position")
            }

            else -> {
                sourceLength = 0L
                sourcePosition = 0L
            }
        }
        val sourceFile = if (windowIndicator and VCD_TARGET != 0) output else old
        val availableSource = if (sourceFile === output) output.length() else old.length()
        checkedRange(sourcePosition, sourceLength, availableSource, "source segment")

        val deltaLength = reader.varInt("delta encoding length")
        if (deltaLength <= 0L || deltaLength > MAX_DELTA_ENCODING) {
            throw HDiffPatchException("invalid VCDIFF delta encoding length: $deltaLength")
        }
        val deltaStart = reader.count
        val targetLength = checkedInt(reader.varInt("target window length"), "target window length")
        if (targetLength > MAX_TARGET_WINDOW) throw HDiffPatchException("VCDIFF target window is too large")
        val deltaIndicator = reader.byte()
        if (deltaIndicator != 0) {
            throw HDiffPatchException("compressed VCDIFF sections are not supported: $deltaIndicator")
        }
        val dataLength = checkedInt(reader.varInt("data section length"), "data section length")
        val instructionsLength = checkedInt(reader.varInt("instructions section length"), "instructions section length")
        val addressesLength = checkedInt(reader.varInt("addresses section length"), "addresses section length")
        val data = ByteCursor(reader.bytes(dataLength), "data")
        val instructions = ByteCursor(reader.bytes(instructionsLength), "instructions")
        val addresses = ByteCursor(reader.bytes(addressesLength), "addresses")
        val expectedDeltaEnd = checkedAdd(deltaStart, deltaLength, "delta encoding")
        if (reader.count != expectedDeltaEnd) {
            throw HDiffPatchException("VCDIFF delta length mismatch: ${reader.count - deltaStart}/$deltaLength")
        }

        val targetStart = output.length()
        val cache = AddressCache()
        var produced = 0
        while (instructions.hasMore()) {
            val entry = DEFAULT_CODE_TABLE[instructions.byte()]
            produced = execute(
                entry.first, produced, targetLength, sourceLength, sourcePosition, sourceFile,
                targetStart, output, data, instructions, addresses, cache
            )
            produced = execute(
                entry.second, produced, targetLength, sourceLength, sourcePosition, sourceFile,
                targetStart, output, data, instructions, addresses, cache
            )
        }
        if (produced != targetLength || data.hasMore() || addresses.hasMore()) {
            throw HDiffPatchException(
                "VCDIFF window did not consume its sections: target=$produced/$targetLength",
            )
        }
        output.seek(output.length())
    }

    private fun execute(
        instruction: Instruction,
        currentProduced: Int,
        targetLength: Int,
        sourceLength: Long,
        sourcePosition: Long,
        sourceFile: RandomAccessFile,
        targetStart: Long,
        output: RandomAccessFile,
        data: ByteCursor,
        instructions: ByteCursor,
        addresses: ByteCursor,
        cache: AddressCache,
    ): Int {
        if (instruction.type == NOOP) return currentProduced
        val size = if (instruction.size == 0) {
            checkedInt(instructions.varInt("instruction size"), "instruction size")
        } else instruction.size
        if (size <= 0 || size > targetLength - currentProduced) {
            throw HDiffPatchException("invalid VCDIFF instruction size: $size")
        }
        when (instruction.type) {
            ADD -> output.write(data.bytes(size))
            RUN -> {
                val value = data.byte()
                val chunk = ByteArray(minOf(size, BUFFER_SIZE)) { value.toByte() }
                var remaining = size
                while (remaining > 0) {
                    val count = minOf(remaining, chunk.size)
                    output.write(chunk, 0, count)
                    remaining -= count
                }
            }

            COPY -> {
                val here = checkedAdd(sourceLength, currentProduced.toLong(), "COPY here")
                val address = cache.decode(instruction.mode, here, addresses)
                if (address < 0L || address >= checkedAdd(
                        sourceLength,
                        currentProduced.toLong(),
                        "COPY available range",
                    )
                ) throw HDiffPatchException("VCDIFF COPY address is unavailable")
                copyFromAddress(
                    address, size, sourceLength, sourcePosition, sourceFile, targetStart, output,
                )
            }

            else -> throw HDiffPatchException("invalid VCDIFF instruction type")
        }
        return currentProduced + size
    }

    private fun copyFromAddress(
        address: Long,
        size: Int,
        sourceLength: Long,
        sourcePosition: Long,
        sourceFile: RandomAccessFile,
        targetStart: Long,
        output: RandomAccessFile,
    ) {
        var readAddress = address
        var remaining = size
        val buffer = ByteArray(minOf(size, BUFFER_SIZE))
        val writePosition = output.filePointer
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size)
            if (readAddress < sourceLength) {
                val sourceCount = minOf(count.toLong(), sourceLength - readAddress).toInt()
                sourceFile.seek(sourcePosition + readAddress)
                sourceFile.readFully(buffer, 0, sourceCount)
                output.seek(writePosition + size - remaining)
                output.write(buffer, 0, sourceCount)
                readAddress += sourceCount
                remaining -= sourceCount
            } else {
                // COPY may overlap bytes being produced by this instruction. Read only bytes that
                // already exist, then loop so repeated strings expand with RFC 3284 semantics.
                val targetAddress = targetStart + readAddress - sourceLength
                val available = output.length() - targetAddress
                if (available <= 0L) throw HDiffPatchException("VCDIFF COPY references future target data")
                val targetCount = minOf(count.toLong(), available, remaining.toLong()).toInt()
                output.seek(targetAddress)
                output.readFully(buffer, 0, targetCount)
                output.seek(writePosition + size - remaining)
                output.write(buffer, 0, targetCount)
                readAddress += targetCount
                remaining -= targetCount
            }
        }
        output.seek(writePosition + size)
    }

    private class AddressCache {
        private val near = LongArray(NEAR_SIZE)
        private val same = LongArray(SAME_SIZE * 256)
        private var nextNear = 0

        fun decode(mode: Int, here: Long, input: ByteCursor): Long {
            val address = when {
                mode == 0 -> input.varInt("COPY address")
                mode == 1 -> here - input.varInt("COPY here address")
                mode in 2 until 2 + NEAR_SIZE ->
                    checkedAdd(near[mode - 2], input.varInt("COPY near address"), "COPY address")

                mode in 2 + NEAR_SIZE until 2 + NEAR_SIZE + SAME_SIZE ->
                    same[(mode - 2 - NEAR_SIZE) * 256 + input.byte()]

                else -> throw HDiffPatchException("invalid VCDIFF COPY mode: $mode")
            }
            if (address < 0L) throw HDiffPatchException("negative VCDIFF COPY address")
            near[nextNear] = address
            nextNear = (nextNear + 1) % NEAR_SIZE
            same[(address % same.size).toInt()] = address
            return address
        }
    }

    private data class Instruction(val type: Int, val size: Int, val mode: Int = 0)
    private data class CodeEntry(val first: Instruction, val second: Instruction = Instruction(NOOP, 0))

    private class ByteCursor(private val value: ByteArray, private val label: String) {
        private var position = 0
        fun hasMore(): Boolean = position < value.size
        fun byte(): Int {
            if (!hasMore()) throw HDiffPatchException("truncated VCDIFF $label section")
            return value[position++].toInt() and 0xff
        }

        fun bytes(length: Int): ByteArray {
            if (length < 0 || length > value.size - position) {
                throw HDiffPatchException("truncated VCDIFF $label section")
            }
            return value.copyOfRange(position, position + length).also { position += length }
        }

        fun varInt(name: String): Long = readVarInt(::byte, name)
    }

    private class StreamReader(private val input: BufferedInputStream) {
        var count: Long = 0L
            private set

        fun hasMore(): Boolean {
            input.mark(1)
            val value = input.read()
            input.reset()
            return value >= 0
        }

        fun byte(): Int {
            val value = input.read()
            if (value < 0) throw EOFException("truncated VCDIFF patch")
            count++
            return value
        }

        fun bytes(length: Int): ByteArray {
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(result, offset, length - offset)
                if (read < 0) throw EOFException("truncated VCDIFF patch")
                offset += read
                count += read
            }
            return result
        }

        fun skip(length: Long) {
            var remaining = length
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                    count += skipped
                } else {
                    byte()
                    remaining--
                }
            }
        }

        fun varInt(name: String): Long = readVarInt(::byte, name)
    }

    private fun readVarInt(next: () -> Int, name: String): Long {
        var result = 0L
        repeat(10) {
            val value = next()
            if (result > (Long.MAX_VALUE ushr 7)) throw HDiffPatchException("VCDIFF $name overflows")
            result = (result shl 7) or (value and 0x7f).toLong()
            if (value and 0x80 == 0) return result
        }
        throw HDiffPatchException("VCDIFF $name is too long")
    }

    private fun checkedInt(value: Long, name: String): Int {
        if (value < 0L || value > Int.MAX_VALUE) throw HDiffPatchException("invalid VCDIFF $name: $value")
        return value.toInt()
    }

    private fun checkedAdd(left: Long, right: Long, name: String): Long {
        if (right < 0L || left > Long.MAX_VALUE - right) throw HDiffPatchException("VCDIFF $name overflows")
        return left + right
    }

    private fun checkedRange(position: Long, length: Long, available: Long, name: String) {
        if (position < 0L || length < 0L || position > available || length > available - position) {
            throw HDiffPatchException("VCDIFF $name is out of bounds")
        }
    }

    private val DEFAULT_CODE_TABLE: List<CodeEntry> = buildList {
        add(CodeEntry(Instruction(RUN, 0)))
        add(CodeEntry(Instruction(ADD, 0)))
        for (size in 1..17) add(CodeEntry(Instruction(ADD, size)))
        for (mode in 0..8) {
            add(CodeEntry(Instruction(COPY, 0, mode)))
            for (size in 4..18) add(CodeEntry(Instruction(COPY, size, mode)))
        }
        for (mode in 0..5) for (addSize in 1..4) for (copySize in 4..6) {
            add(CodeEntry(Instruction(ADD, addSize), Instruction(COPY, copySize, mode)))
        }
        for (mode in 6..8) for (addSize in 1..4) {
            add(CodeEntry(Instruction(ADD, addSize), Instruction(COPY, 4, mode)))
        }
        for (mode in 0..8) {
            add(CodeEntry(Instruction(COPY, 4, mode), Instruction(ADD, 1)))
        }
        check(size == 256)
    }

    private val MAGIC = intArrayOf(0xD6, 0xC3, 0xC4)
    private const val VCD_DECOMPRESS = 0x01
    private const val VCD_CODETABLE = 0x02
    private const val HEADER_RESERVED_MASK = 0xfc
    private const val VCD_SOURCE = 0x01
    private const val VCD_TARGET = 0x02
    private const val WINDOW_RESERVED_MASK = 0xfc
    private const val NOOP = 0
    private const val ADD = 1
    private const val RUN = 2
    private const val COPY = 3
    private const val NEAR_SIZE = 4
    private const val SAME_SIZE = 3
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_TARGET_WINDOW = 256 * 1024 * 1024
    private const val MAX_DELTA_ENCODING = 512L * 1024L * 1024L
}
