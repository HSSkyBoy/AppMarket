package top.app.market.data.remote.oppo

import top.app.market.di.dataModules
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveOppoMobileServicesTest {
    @Test
    fun probesMobileServicesDownload() = runBlocking {
        if (System.getenv(ENABLE_ENV) != "1") return@runBlocking
        val koin = startKoin { modules(dataModules) }.koin
        try {
            val api = koin.get<OppoApi>()
            val client = koin.get<HttpClient>()
            val app = api.search("移动服务", 0).items.first { it.packageName == MOBILE_SERVICES }
            val meta = api.downloadMeta(app)
            assertTrue(meta.parts.isNotEmpty())
            assertEquals(meta.size, meta.parts.sumOf { it.size })
            assertEquals("base", meta.parts.first().type)
            meta.parts.forEachIndexed { index, part ->
                assertTrue(part.url.startsWith("https://"), "Part $index did not use HTTPS")
                assertFalse(
                    isOppoDownloadMetadataUrl(part.url),
                    "Part $index was still an OPPO metadata response",
                )
                client.prepareGet(part.url) {
                    meta.requestHeaders.forEach { (name, value) -> header(name, value) }
                    header(HttpHeaders.AcceptEncoding, "identity")
                    header(HttpHeaders.Range, "bytes=0-3")
                }.execute { response ->
                    val bytes = ByteArray(4)
                    val count = response.bodyAsChannel().readAvailable(bytes)
                    assertTrue(response.status.isSuccess(), "Part $index returned HTTP ${response.status.value}")
                    assertEquals(4, count, "Part $index did not return four APK magic bytes")
                    assertTrue(
                        bytes.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04)),
                        "Part $index was not an APK/ZIP response",
                    )
                }
                if (System.getenv(FULL_DOWNLOAD_ENV) == "1") {
                    client.prepareGet(part.url) {
                        meta.requestHeaders.forEach { (name, value) -> header(name, value) }
                        header(HttpHeaders.AcceptEncoding, "identity")
                    }.execute { response ->
                        assertTrue(response.status.isSuccess(), "Part $index returned HTTP ${response.status.value}")
                        val digest = MessageDigest.getInstance("MD5")
                        val buffer = ByteArray(128 * 1024)
                        val channel = response.bodyAsChannel()
                        var received = 0L
                        while (true) {
                            val count = channel.readAvailable(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            digest.update(buffer, 0, count)
                            received += count
                        }
                        assertEquals(part.size, received, "Part $index was incomplete")
                        if (part.hash.isNotBlank()) {
                            val actual = digest.digest().joinToString("") { "%02x".format(it) }
                            assertEquals(part.hash.lowercase(), actual, "Part $index checksum did not match")
                        }
                    }
                }
            }
        } finally {
            stopKoin()
        }
    }

    private companion object {
        const val ENABLE_ENV = "APPMARKET_LIVE_OPPO_MOBILE_SERVICES"
        const val FULL_DOWNLOAD_ENV = "APPMARKET_LIVE_OPPO_MOBILE_SERVICES_FULL"
        const val MOBILE_SERVICES = "com.heytap.htms"
    }
}
