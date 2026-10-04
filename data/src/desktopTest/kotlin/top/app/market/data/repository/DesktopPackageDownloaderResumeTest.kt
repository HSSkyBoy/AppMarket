package top.app.market.data.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用本地 HTTP 服务器验证桌面端断点续传的合并、校验与降级行为。 */
@OptIn(ExperimentalPathApi::class)
class DesktopPackageDownloaderResumeTest {

    private lateinit var server: HttpServer
    private lateinit var root: Path
    private lateinit var http: HttpClient
    private val payload = ByteArray(PAYLOAD_SIZE) { index -> ((index * 31 + 7) and 0xFF).toByte() }.also {
        it[0] = 'P'.code.toByte()
        it[1] = 'K'.code.toByte()
    }
    private val receivedRanges = CopyOnWriteArrayList<String?>()
    private var rangeSupported = true

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("appmarket-download-test")
        receivedRanges.clear()
        rangeSupported = true
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/app.apk") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                receivedRanges.add(range)
                val offset = range
                    ?.takeIf { rangeSupported }
                    ?.removePrefix("bytes=")
                    ?.removeSuffix("-")
                    ?.toLongOrNull()
                    ?.toInt()
                    ?.coerceIn(0, payload.size)
                    ?: 0
                val body = payload.copyOfRange(offset, payload.size)
                if (offset > 0) {
                    exchange.responseHeaders.add(
                        "Content-Range",
                        "bytes $offset-${payload.size - 1}/${payload.size}",
                    )
                    exchange.sendResponseHeaders(206, body.size.toLong())
                } else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                }
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        http = HttpClient(CIO) { install(HttpTimeout) }
    }

    @AfterTest
    fun tearDown() {
        http.close()
        server.stop(0)
        root.deleteRecursively()
    }

    @Test
    fun freshDownloadVerifiesAndCleansStaging() = runBlocking {
        val meta = meta()
        val downloader = downloader()

        val directory = downloader.download(meta) { _, _ -> }

        assertContentEquals(payload, Files.readAllBytes(directory.resolve(fileName(meta))))
        assertFalse(Files.exists(stagingDirectory(meta)))
        assertEquals(listOf<String?>(null), receivedRanges)
    }

    @Test
    fun resumeContinuesFromExistingBytesWithRange() = runBlocking {
        val meta = meta()
        Files.createDirectories(stagingDirectory(meta))
        Files.write(stagingFile(meta), payload.copyOfRange(0, RESUME_OFFSET))

        val progress = mutableListOf<Long>()
        val directory = downloader().download(meta) { completed, _ -> progress.add(completed) }

        assertContentEquals(payload, Files.readAllBytes(directory.resolve(fileName(meta))))
        assertEquals(listOf<String?>("bytes=$RESUME_OFFSET-"), receivedRanges)
        // 已有字节的重放让进度从断点附近起步，而不是从 0 爬
        assertTrue(progress.first() > 0L)
    }

    @Test
    fun serverIgnoringRangeRestartsFromScratch() = runBlocking {
        rangeSupported = false
        val meta = meta()
        Files.createDirectories(stagingDirectory(meta))
        Files.write(stagingFile(meta), payload.copyOfRange(0, RESUME_OFFSET))

        val progress = mutableListOf<Long>()
        val directory = downloader().download(meta) { completed, _ -> progress.add(completed) }

        assertContentEquals(payload, Files.readAllBytes(directory.resolve(fileName(meta))))
        // 第一次带 Range 被 200 拒绝，重置后第二次全量
        assertEquals(listOf<String?>("bytes=$RESUME_OFFSET-", null), receivedRanges)
        assertTrue(progress.contains(0L))
    }

    @Test
    fun corruptedStagingIsDiscardedAndRedownloaded() = runBlocking {
        val meta = meta()
        Files.createDirectories(stagingDirectory(meta))
        // 与远端内容不符的残留分片：续传合并后校验失败，应自动清空重下并成功
        Files.write(stagingFile(meta), ByteArray(RESUME_OFFSET) { 0x55 })

        val progress = mutableListOf<Long>()
        val directory = downloader().download(meta) { completed, _ -> progress.add(completed) }

        assertContentEquals(payload, Files.readAllBytes(directory.resolve(fileName(meta))))
        assertEquals(listOf<String?>("bytes=$RESUME_OFFSET-", null), receivedRanges)
        assertTrue(progress.contains(0L))
    }

    @Test
    fun hashMismatchFailsAfterOneRestart() = runBlocking {
        // 服务器内容与声明的 hash 不符：清空重下一次后仍不符，判失败不产出文件
        val meta = meta(hash = md5(ByteArray(8) { 1 }))

        val failed = runCatching { downloader().download(meta) { _, _ -> } }.isFailure

        assertTrue(failed)
        assertEquals(listOf<String?>(null, null), receivedRanges)
        // 脏分片被删除，也没有落位到最终目录
        assertFalse(Files.exists(stagingFile(meta)))
        assertFalse(Files.exists(root.resolve(fileName(meta))))
    }

    @Test
    fun completeStagingSkipsNetworkEntirely() = runBlocking {
        val meta = meta()
        Files.createDirectories(stagingDirectory(meta))
        Files.write(stagingFile(meta), payload)

        val directory = downloader().download(meta) { _, _ -> }

        assertContentEquals(payload, Files.readAllBytes(directory.resolve(fileName(meta))))
        assertEquals(emptyList<String?>(), receivedRanges)
    }

    private fun downloader() = DesktopPackageDownloader(http, root, requireHttps = false)

    private fun meta(hash: String = md5(payload)) = DownloadMeta(
        appId = 1L,
        packageName = "com.example.app",
        displayName = "App",
        versionName = "1.0",
        versionCode = 1L,
        url = url(),
        size = payload.size.toLong(),
        parts = listOf(
            DownloadPart(
                name = "base",
                type = "base",
                url = url(),
                size = payload.size.toLong(),
                hash = hash,
            )
        ),
    )

    private fun url() = "http://127.0.0.1:${server.address.port}/app.apk"

    private fun fileName(meta: DownloadMeta) = desktopDownloadFileNames(meta).single()

    private fun stagingDirectory(meta: DownloadMeta): Path =
        root.resolve(".staging").resolve("${meta.packageName}-${meta.versionCode}")

    private fun stagingFile(meta: DownloadMeta): Path =
        stagingDirectory(meta).resolve("0_${fileName(meta)}.part")

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val PAYLOAD_SIZE = 512 * 1024
        const val RESUME_OFFSET = 200 * 1024
    }
}
