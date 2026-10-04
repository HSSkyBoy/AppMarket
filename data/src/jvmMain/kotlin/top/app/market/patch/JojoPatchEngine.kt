package top.app.market.patch

import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile

/**
 * 本项目的流式 JojoDiff 解码器，用于 TapTap 未声明专有 SDK 时下发的补丁。
 * 格式参考：https://github.com/JiapengLi/mJPatch#jojodiff-patch-file-structure
 * 仅随机读取旧 APK，输出顺序写入；不把 APK 整体载入内存，不调用外部合并库。
 */
internal object JojoPatchEngine {
    fun applyFiles(patchFile: File, oldFile: File, outputFile: File) {
        try {
            PushbackInputStream(patchFile.inputStream().buffered(BUFFER_SIZE), 2).use { patch ->
                outputFile.parentFile?.mkdirs()
                RandomAccessFile(oldFile, "r").use { old ->
                    outputFile.outputStream().buffered(BUFFER_SIZE).use { output ->
                        var cursor = 0L
                        val buffer = ByteArray(BUFFER_SIZE)
                        fun move(offset: Long) {
                            if (offset < -cursor || offset > old.length() - cursor) {
                                fail("JojoDiff base range out of bounds")
                            }
                            cursor += offset
                        }
                        fun literals(advanceSource: Boolean) {
                            while (true) {
                                val byte = patch.read()
                                if (byte < 0) return
                                if (byte != ESC) {
                                    if (advanceSource) move(1)
                                    output.write(byte)
                                    continue
                                }
                                val escaped = requiredByte(patch)
                                if (escaped in BKT..MOD) {
                                    patch.unread(escaped)
                                    patch.unread(ESC)
                                    return
                                }
                                if (advanceSource) move(if (escaped == ESC) 1 else 2)
                                output.write(ESC)
                                if (escaped != ESC) output.write(escaped)
                            }
                        }
                        while (true) {
                            val byte = patch.read()
                            if (byte < 0) return
                            val command = if (byte == ESC) {
                                requiredByte(patch)
                            } else {
                                // JojoDiff 0.8.5+ omits MOD for the common case.
                                patch.unread(byte)
                                MOD
                            }
                            when (command) {
                                MOD -> literals(advanceSource = true)
                                INS -> literals(advanceSource = false)
                                DEL -> move(length(patch))
                                BKT -> move(-length(patch))
                                EQL -> {
                                    var remaining = length(patch)
                                    old.seek(cursor)
                                    move(remaining)
                                    while (remaining > 0L) {
                                        val count = minOf(remaining, buffer.size.toLong()).toInt()
                                        old.readFully(buffer, 0, count)
                                        output.write(buffer, 0, count)
                                        remaining -= count
                                    }
                                }
                                else -> fail("invalid JojoDiff operation: $command")
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            outputFile.delete()
            if (error is HDiffPatchException) throw error
            throw HDiffPatchException(error.message ?: "JojoDiff patch failed", error)
        }
    }

    private fun length(input: InputStream): Long {
        val first = requiredByte(input)
        val value = when {
            first < 252 -> first + 1L
            first == 252 -> requiredByte(input) + 253L
            else -> {
                val count = when (first) { 253 -> 2; 254 -> 4; else -> 8 }
                var result = 0L
                repeat(count) {
                    if (result > (Long.MAX_VALUE ushr 8)) fail("JojoDiff length overflow")
                    result = (result shl 8) or requiredByte(input).toLong()
                }
                result
            }
        }
        if (value <= 0L) fail("invalid JojoDiff length")
        return value
    }

    private fun requiredByte(input: InputStream): Int = input.read().also {
        if (it < 0) fail("truncated JojoDiff patch")
    }

    private fun fail(message: String): Nothing = throw HDiffPatchException(message)
    private const val BUFFER_SIZE = 128 * 1024
    private const val ESC = 0xa7
    private const val MOD = 0xa6
    private const val INS = 0xa5
    private const val DEL = 0xa4
    private const val EQL = 0xa3
    private const val BKT = 0xa2
}
