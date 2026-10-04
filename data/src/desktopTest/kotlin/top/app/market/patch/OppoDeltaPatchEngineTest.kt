package top.app.market.patch

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals

class OppoDeltaPatchEngineTest {
    @Test
    fun officialHdiff13PatchIsCompatibleWhenFixtureIsProvided() {
        val patch = System.getenv(OFFICIAL_PATCH_ENV)?.takeIf(String::isNotBlank)?.let(::File) ?: return
        val old = System.getenv(OFFICIAL_OLD_ENV)?.takeIf(String::isNotBlank)?.let(::File)
            ?: error("$OFFICIAL_OLD_ENV is required with $OFFICIAL_PATCH_ENV")
        val expected = System.getenv(OFFICIAL_NEW_ENV)?.takeIf(String::isNotBlank)?.let(::File)
            ?: error("$OFFICIAL_NEW_ENV is required with $OFFICIAL_PATCH_ENV")
        val directory = Files.createTempDirectory("appmarket-official-hdiff13-").toFile()
        try {
            val coreOutput = directory.resolve("new-core.bin")
            HDiffCore.applyClassicFiles(patch, old, coreOutput)
            assertContentEquals(expected.readBytes(), coreOutput.readBytes())

            val container = directory.resolve("update.patch").apply {
                writeBytes(
                    oppoV2Patch(
                        oldRanges = listOf(OldRange(0, 0L, old.length())),
                        newRanges = listOf(NewRange(0L, expected.length(), 0L, 0, 0, 0, 0)),
                        delta = patch.readBytes(),
                    )
                )
            }
            val containerOutput = directory.resolve("new-container.bin")
            OppoDeltaPatchEngine.applyFiles(container, old, containerOutput)
            assertContentEquals(expected.readBytes(), containerOutput.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun standardHdiff13ZlibPatchIsAppliedByAppMarketCore() {
        val old = "an old payload that is deliberately different".encodeToByteArray()
        val expected = "the complete replacement payload produced by HDIFF13".encodeToByteArray()
        val patch = literalHdiff13(old.size, expected, compressed = true)
        val directory = Files.createTempDirectory("appmarket-hdiff13-").toFile()
        try {
            val oldFile = directory.resolve("old.bin").apply { writeBytes(old) }
            val patchFile = directory.resolve("patch.hdiff").apply { writeBytes(patch) }
            val output = directory.resolve("new.bin")

            HDiffCore.applyClassicFiles(patchFile, oldFile, output)

            assertContentEquals(expected, output.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oppoV2InflatesApkRangesAppliesHdiffAndRecompressesWithAppMarket() {
        val prefix = "APK-PREFIX:".encodeToByteArray()
        val suffix = ":APK-SUFFIX".encodeToByteArray()
        val oldPayload = buildString { repeat(100) { append("old-entry-$it|") } }.encodeToByteArray()
        val newPayload = buildString { repeat(120) { append("new-entry-$it|") } }.encodeToByteArray()
        val oldCompressed = deflateRaw(oldPayload, level = 6, strategy = Deflater.DEFAULT_STRATEGY)
        val newCompressed = deflateRaw(newPayload, level = 6, strategy = Deflater.DEFAULT_STRATEGY)
        val oldApk = prefix + oldCompressed + suffix
        val oldFriendly = prefix + oldPayload + suffix
        val newFriendly = prefix + newPayload + suffix
        val expectedApk = prefix + newCompressed + suffix
        val delta = literalHdiff13(oldFriendly.size, newFriendly, compressed = true)

        val oldRanges = listOf(
            OldRange(0, 0L, prefix.size.toLong()),
            OldRange(1, prefix.size.toLong(), oldCompressed.size.toLong()),
            OldRange(0, (prefix.size + oldCompressed.size).toLong(), suffix.size.toLong()),
        )
        val newRanges = listOf(
            NewRange(0L, prefix.size.toLong(), 0L, 0, 0, 0, 0),
            NewRange(prefix.size.toLong(), newPayload.size.toLong(), prefix.size.toLong(), 1, 6, 0, 1),
            NewRange(
                (prefix.size + newPayload.size).toLong(),
                suffix.size.toLong(),
                (prefix.size + newCompressed.size).toLong(),
                0,
                0,
                0,
                0,
            ),
        )
        val patch = oppoV2Patch(oldRanges, newRanges, delta)
        val directory = Files.createTempDirectory("appmarket-oppo-v2-").toFile()
        try {
            val oldFile = directory.resolve("old.apk").apply { writeBytes(oldApk) }
            val patchFile = directory.resolve("update.patch").apply { writeBytes(patch) }
            val output = directory.resolve("new.apk")

            OppoDeltaPatchEngine.applyFiles(patchFile, oldFile, output)

            assertContentEquals(expectedApk, output.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun literalHdiff13(oldSize: Int, newData: ByteArray, compressed: Boolean): ByteArray {
        val cover = ByteArray(0)
        val rleControl = PackBytes().tagged(newData.size.toLong() - 1L, 0, 2).bytes()
        val rleCode = ByteArray(0)
        val sections = listOf(cover, rleControl, rleCode, newData)
        val stored = if (compressed) sections.map(::zlib) else sections
        return ByteArrayOutputStream().also { output ->
            output.write(if (compressed) "HDIFF13&zlib\u0000".encodeToByteArray() else "HDIFF13&\u0000".encodeToByteArray())
            output.write(PackBytes().unsigned(newData.size.toLong()).unsigned(oldSize.toLong()).unsigned(0L).bytes())
            sections.zip(stored).forEach { (raw, encoded) ->
                output.write(
                    PackBytes()
                        .unsigned(raw.size.toLong())
                        .unsigned(if (compressed) encoded.size.toLong() else 0L)
                        .bytes(),
                )
            }
            stored.forEach(output::write)
        }.toByteArray()
    }

    private fun oppoV2Patch(oldRanges: List<OldRange>, newRanges: List<NewRange>, delta: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(oldRanges.size)
                oldRanges.forEach { range ->
                    output.writeByte(range.type)
                    output.writeLong(range.offset)
                    output.writeLong(range.length)
                }
                output.writeInt(newRanges.size)
                newRanges.forEach { range ->
                    output.writeLong(range.offset)
                    output.writeLong(range.length)
                    output.writeLong(range.outputOffset)
                    output.writeByte(range.type)
                    output.writeByte(range.level)
                    output.writeByte(range.strategy)
                    output.writeByte(range.nowrap)
                }
                output.writeByte(0)
                output.writeLong(delta.size.toLong())
                output.write(delta)
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("OFbFv2_1".encodeToByteArray())
                output.writeInt(0)
                repeat(5) { output.writeLong(0L) }
                output.writeInt(1)
                output.writeLong(body.size.toLong())
                output.write(body)
            }
        }.toByteArray()
    }

    private fun deflateRaw(data: ByteArray, level: Int, strategy: Int): ByteArray {
        val deflater = Deflater(level, true)
        return try {
            deflater.setStrategy(strategy)
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater(6, false)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private class PackBytes {
        private val output = ByteArrayOutputStream()

        fun unsigned(value: Long): PackBytes = tagged(value, 0, 0)

        fun tagged(value: Long, tag: Int, tagBits: Int): PackBytes = apply {
            require(value >= 0L)
            var continuationBytes = 0
            val firstPayloadBits = 7 - tagBits
            while ((value ushr (7 * continuationBytes)) >= (1L shl firstPayloadBits)) continuationBytes++
            var first = ((value ushr (7 * continuationBytes)).toInt() and ((1 shl firstPayloadBits) - 1))
            first = first or (tag shl (8 - tagBits))
            if (continuationBytes > 0) first = first or (1 shl firstPayloadBits)
            output.write(first)
            for (index in continuationBytes - 1 downTo 0) {
                var next = (value ushr (7 * index)).toInt() and 0x7f
                if (index > 0) next = next or 0x80
                output.write(next)
            }
        }

        fun bytes(): ByteArray = output.toByteArray()
    }

    private data class OldRange(val type: Int, val offset: Long, val length: Long)

    private data class NewRange(
        val offset: Long,
        val length: Long,
        val outputOffset: Long,
        val type: Int,
        val level: Int,
        val strategy: Int,
        val nowrap: Int,
    )

    private companion object {
        const val OFFICIAL_PATCH_ENV = "APPMARKET_OFFICIAL_HDIFF_PATCH"
        const val OFFICIAL_OLD_ENV = "APPMARKET_OFFICIAL_HDIFF_OLD"
        const val OFFICIAL_NEW_ENV = "APPMARKET_OFFICIAL_HDIFF_NEW"
    }
}
