package top.app.market.data.remote.honor

import top.app.market.data.platform.DebugLogging
import top.app.market.di.dataModules
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveHonorSystemDownloadTest {
    @Test
    fun resolvesExactSystemPackageThroughTheProductionDownloadPath() = runBlocking {
        val packageName = System.getenv(PACKAGE_ENV)?.takeIf(String::isNotBlank) ?: return@runBlocking
        val displayName = System.getenv(DISPLAY_NAME_ENV)?.takeIf(String::isNotBlank) ?: return@runBlocking
        val previousHome = System.getProperty("user.home")
        val cleanHome = Files.createTempDirectory("hypermarket-honor-live-")
        System.setProperty("user.home", cleanHome.toString())
        DebugLogging.enabled = true
        val koin = startKoin { modules(dataModules) }.koin
        try {
            val api = koin.get<HonorApi>()
            val client = koin.get<HttpClient>()
            val app = MarketAppInfo(
                appId = 0,
                packageName = packageName,
                displayName = displayName,
                publisherName = "",
                versionName = "",
                versionCode = 0,
                icon = "",
                apkSize = 0,
                ratingScore = 0.0,
                isSystemApp = true,
                source = AppSource.HONOR,
            )
            val installed = System.getenv(INSTALLED_VERSION_ENV)?.toLongOrNull()?.let { version ->
                val signer = System.getenv(INSTALLED_SIGNER_ENV).orEmpty()
                InstalledPackage(
                    packageName = packageName,
                    versionCode = version,
                    versionName = System.getenv(INSTALLED_VERSION_NAME_ENV).orEmpty(),
                    isSystemApp = true,
                    oldApkHash = System.getenv(INSTALLED_HASH_ENV).orEmpty(),
                    signerSha256 = signer,
                    signerSha256List = listOf(signer).filter(String::isNotBlank),
                )
            }
            val policies = runCatching { api.updateConfig().systemPolicies }.getOrDefault(emptyMap())
            val updateSeeds = if (installed == null) emptyList() else mergeHonorRecords(
                runCatching { api.silentUpdates(listOf(installed), policies) }.getOrDefault(emptyList()) +
                        api.updates(listOf(installed), policies),
            )

            val record = api.downloadableRecord(app, updateSeeds, installed)
            assertTrue(record.packageName.equals(packageName, ignoreCase = true))
            assertTrue(record.hasDownloadPayload(), "Exact Honor lookup did not return download metadata")
            val meta = api.downloadMeta(record, app, HonorDownloadPurpose.UPDATE)
            assertTrue(meta.packageName.equals(packageName, ignoreCase = true))
            assertTrue(meta.url.startsWith("https://"), "Honor download URL was not HTTPS")
            assertTrue(meta.size > 0L, "Honor download size was empty")

            client.prepareGet(meta.url) {
                header(HttpHeaders.AcceptEncoding, "identity")
                header(HttpHeaders.Range, "bytes=0-3")
            }.execute { response ->
                val bytes = ByteArray(4)
                val count = response.bodyAsChannel().readAvailable(bytes)
                assertTrue(response.status.isSuccess(), "Honor CDN returned HTTP ${response.status.value}")
                assertEquals(4, count)
                assertTrue(bytes.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04)), "Response was not an APK")
            }
        } finally {
            stopKoin()
            DebugLogging.enabled = false
            System.setProperty("user.home", previousHome)
            cleanHome.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val PACKAGE_ENV = "HYPERMARKET_LIVE_HONOR_PACKAGE"
        const val DISPLAY_NAME_ENV = "HYPERMARKET_LIVE_HONOR_DISPLAY_NAME"
        const val INSTALLED_VERSION_ENV = "HYPERMARKET_LIVE_HONOR_INSTALLED_VERSION"
        const val INSTALLED_VERSION_NAME_ENV = "HYPERMARKET_LIVE_HONOR_INSTALLED_VERSION_NAME"
        const val INSTALLED_HASH_ENV = "HYPERMARKET_LIVE_HONOR_INSTALLED_HASH"
        const val INSTALLED_SIGNER_ENV = "HYPERMARKET_LIVE_HONOR_INSTALLED_SIGNER"
    }
}
