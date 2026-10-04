package top.app.market.data.remote.huawei

import top.app.market.data.platform.DebugLogging
import top.app.market.data.repository.HuaweiRepositoryImpl
import top.app.market.di.dataModules
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.repository.InstalledApkHashRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
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

class LiveHuaweiSystemDownloadTest {
    @Test
    fun searchesAndDownloadsPublicHuaweiSystemApps() = runBlocking {
        if (System.getenv(PUBLIC_SEARCH_ENV) != "1") return@runBlocking
        val previousHome = System.getProperty("user.home")
        val cleanHome = Files.createTempDirectory("hypermarket-huawei-search-live-")
        System.setProperty("user.home", cleanHome.toString())
        DebugLogging.enabled = true
        val koin = startKoin { modules(dataModules) }.koin
        try {
            val repository = HuaweiRepositoryImpl(
                api = koin.get(),
                installedPackages = InstalledPackagesRepository { emptyList() },
                installedApkHash = object : InstalledApkHashRepository {
                    override suspend fun md5(path: String): String = ""
                    override suspend fun sha256(path: String): String = ""
                },
                updatePreferences = koin.get(),
            )
            val client = koin.get<HttpClient>()

            PUBLIC_SYSTEM_APPS.forEach { expected ->
                val app = repository.search(expected.keyword, page = 0).items
                    .singleOrNull { it.packageName.equals(expected.packageName, ignoreCase = true) }
                assertTrue(app != null, "Huawei search did not return ${expected.packageName}")

                val meta = repository.downloadMeta(app)
                assertTrue(meta.url.startsWith("https://"), "Huawei APK URL was not HTTPS")
                assertTrue(meta.size > 0L, "Huawei APK size was empty for ${expected.packageName}")
                assertTrue(
                    meta.parts.single().hash.isHuaweiSha256(),
                    "Huawei APK SHA-256 was invalid for ${expected.packageName}",
                )
                assertApkHeader(client, meta.url, expected.packageName)
            }
        } finally {
            stopKoin()
            DebugLogging.enabled = false
            System.setProperty("user.home", previousHome)
            cleanHome.toFile().deleteRecursively()
        }
    }

    @Test
    fun searchesAndResolvesInstalledSystemPackageUpdate() = runBlocking {
        val packageName = System.getenv(PACKAGE_ENV)?.takeIf(String::isNotBlank) ?: return@runBlocking
        val keyword = System.getenv(KEYWORD_ENV)?.takeIf(String::isNotBlank) ?: packageName
        val installedVersion = System.getenv(INSTALLED_VERSION_ENV)?.toLongOrNull() ?: return@runBlocking
        val previousHome = System.getProperty("user.home")
        val cleanHome = Files.createTempDirectory("hypermarket-huawei-live-")
        System.setProperty("user.home", cleanHome.toString())
        DebugLogging.enabled = true
        val koin = startKoin { modules(dataModules) }.koin
        try {
            val api = koin.get<HuaweiApi>()
            val client = koin.get<HttpClient>()
            val installedHash = System.getenv(INSTALLED_HASH_ENV).orEmpty()
            val installed = InstalledPackage(
                packageName = packageName,
                versionCode = installedVersion,
                versionName = System.getenv(INSTALLED_VERSION_NAME_ENV).orEmpty(),
                isSystemApp = true,
                oldApkHash = installedHash,
                baseApkPath = "live-system-sample.apk",
                targetSdkVersion = System.getenv(TARGET_SDK_ENV)?.toIntOrNull() ?: 0,
            )
            val repository = HuaweiRepositoryImpl(
                api = api,
                installedPackages = InstalledPackagesRepository { listOf(installed) },
                installedApkHash = object : InstalledApkHashRepository {
                    override suspend fun md5(path: String): String = ""
                    override suspend fun sha256(path: String): String = installedHash
                },
                updatePreferences = koin.get<UpdatePreferencesRepository>(),
            )
            val searchApp = repository.search(keyword, page = 0).items
                .single { it.packageName.equals(packageName, ignoreCase = true) }
            val searchMeta = repository.downloadMeta(searchApp)
            assertTrue(searchMeta.url.startsWith("https://"), "Huawei search APK URL was not HTTPS")
            assertTrue(searchMeta.size > 0L, "Huawei search APK size was empty")
            assertTrue(searchMeta.parts.single().hash.isHuaweiSha256(), "Huawei search APK SHA-256 was invalid")
            assertApkHeader(client, searchMeta.url, "search")

            val update = repository.checkUpdates()
                .single { it.packageName.equals(packageName, ignoreCase = true) }
            val meta = repository.downloadUpdateMeta(update)

            assertTrue(update.versionCode > installedVersion, "Huawei did not return a newer version")
            assertTrue(meta.url.startsWith("https://"), "Huawei full APK URL was not HTTPS")
            assertTrue(meta.size > 0L, "Huawei full APK size was empty")
            assertTrue(meta.parts.single().hash.isHuaweiSha256(), "Huawei full APK SHA-256 was invalid")
            assertEquals(packageName, meta.packageName)
            assertEquals(update.versionCode, meta.versionCode)

            assertApkHeader(client, meta.url, "update")
        } finally {
            stopKoin()
            DebugLogging.enabled = false
            System.setProperty("user.home", previousHome)
            cleanHome.toFile().deleteRecursively()
        }
    }

    private suspend fun assertApkHeader(client: HttpClient, url: String, purpose: String) {
        client.prepareGet(url) {
            header(HttpHeaders.AcceptEncoding, "identity")
            header(HttpHeaders.Range, "bytes=0-3")
        }.execute { response ->
            val bytes = ByteArray(4)
            val count = response.bodyAsChannel().readAvailable(bytes)
            assertTrue(response.status.isSuccess(), "Huawei $purpose CDN returned HTTP ${response.status.value}")
            assertEquals(4, count)
            assertTrue(
                bytes.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04)),
                "Huawei $purpose response was not an APK",
            )
        }
    }

    private companion object {
        const val PUBLIC_SEARCH_ENV = "HYPERMARKET_LIVE_HUAWEI_PUBLIC_SEARCH"
        const val PACKAGE_ENV = "HYPERMARKET_LIVE_HUAWEI_PACKAGE"
        const val KEYWORD_ENV = "HYPERMARKET_LIVE_HUAWEI_KEYWORD"
        const val INSTALLED_VERSION_ENV = "HYPERMARKET_LIVE_HUAWEI_INSTALLED_VERSION"
        const val INSTALLED_VERSION_NAME_ENV = "HYPERMARKET_LIVE_HUAWEI_INSTALLED_VERSION_NAME"
        const val INSTALLED_HASH_ENV = "HYPERMARKET_LIVE_HUAWEI_INSTALLED_HASH"
        const val TARGET_SDK_ENV = "HYPERMARKET_LIVE_HUAWEI_TARGET_SDK"

        val PUBLIC_SYSTEM_APPS = listOf(
            ExpectedApp("计算器", "com.huawei.calculator"),
            ExpectedApp("文件管理", "com.huawei.filemanager"),
            ExpectedApp("笔记", "com.huawei.hinote"),
            ExpectedApp("智慧搜索", "com.huawei.search"),
        )
    }

    private data class ExpectedApp(val keyword: String, val packageName: String)
}
