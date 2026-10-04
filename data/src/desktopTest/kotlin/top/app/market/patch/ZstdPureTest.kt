package top.app.market.patch

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** ZstdPure 解码验证：内嵌固定样本（libzstd 生成）+ 手工构造帧。 */
class ZstdPureTest {

    @Test
    fun fixturesRoundTrip() {
        for (name in ZstdFixtures.names) {
            val expected = ZstdTestVectors.shape(ZstdTestVectors.shapeOf(name))
            assertContentEquals(expected, ZstdPure.decode(ZstdFixtures.bytes(name), expected.size), name)
        }
    }

    @Test
    fun rawFrameBoundarySizes() {
        for (size in intArrayOf(0, 1, 3, 65535, 65536, 65537, 200_000)) {
            val data = ByteArray(size) { (it % 251).toByte() }
            assertContentEquals(data, ZstdPure.decode(ZstdTestVectors.rawFrame(data), size), "size=$size")
        }
    }

    @Test
    fun skippableFrameIsIgnored() {
        val data = "payload after skippable".encodeToByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x2A, 0x4D, 0x18)) // 0x184D2A52
        out.write(byteArrayOf(3, 0, 0, 0))
        out.write(byteArrayOf(1, 2, 3))
        out.write(ZstdTestVectors.rawFrame(data))
        assertContentEquals(data, ZstdPure.decode(out.toByteArray(), data.size))
    }

    @Test
    fun rleBlockFrame() {
        val size = 70000
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()))
        out.write(0xA0) // single segment + 4 字节内容长度
        for (k in 0 until 4) out.write((size ushr (8 * k)) and 0xFF)
        val bh = (size shl 3) or 0b010 or 1 // RLE + last
        out.write(bh and 0xFF)
        out.write((bh ushr 8) and 0xFF)
        out.write((bh ushr 16) and 0xFF)
        out.write(0x5A)
        assertContentEquals(ByteArray(size) { 0x5A }, ZstdPure.decode(out.toByteArray(), size))
    }

    /** 真实线上补丁复验：依赖此前探针留下的产物，缺文件或旧包不匹配时静默跳过。 */
    @Test
    fun realPatchArtifactsIfPresent() {
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { root -> OLD_APK_CANDIDATES.any { File(root, it).isFile } } ?: return
        val patch = File(repoRoot, "data/build/delta-probe/patch.bin")
        val full = File(repoRoot, "data/build/delta-probe/new-full.apk")
        if (!patch.isFile || !full.isFile) return
        val oldSize = parseContainer(Buf.wrap(patch.readBytes())).oldSize
        val old = OLD_APK_CANDIDATES.map { File(repoRoot, it) }
            .firstOrNull { it.isFile && it.length() == oldSize } ?: return
        val out = File(repoRoot, "data/build/delta-probe/kotlin-out.apk")
        val started = System.nanoTime()
        HDiffCore.applyFiles(patch, old, out)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("[probe] pure-kotlin applyFiles in ${elapsedMs}ms md5=${md5Hex(out)}")
        kotlin.test.assertEquals(md5Hex(full), md5Hex(out))
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val OLD_APK_CANDIDATES = listOf("pdd-old.apk", "alipay-old.apk")
    }
}
