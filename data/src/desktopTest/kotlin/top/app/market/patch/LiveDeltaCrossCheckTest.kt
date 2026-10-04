package top.app.market.patch

import top.app.market.data.remote.xiaomi.XiaomiApi
import top.app.market.di.dataModules
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.repository.AccountRepository
import top.app.market.domain.repository.ProfileRepository
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/** 手动探针：线上补丁经 HDiffCore 合成后与全量包逐字节比对。仓库根目录放旧版 APK、改常量后去掉 @Ignore 单跑。 */
@Ignore
class LiveDeltaCrossCheckTest {

    private companion object {
        const val OLD_APK_NAME = "pdd-old.apk"
        const val PACKAGE_NAME = "com.xunmeng.pinduoduo"
        const val OLD_VERSION_CODE = 81700L
        const val OLD_VERSION_NAME = "8.17.0"
    }

    @Test
    fun fetchAndApplyRealPatch() = runBlocking<Unit> {
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, OLD_APK_NAME).isFile }
            ?: error("$OLD_APK_NAME not found upwards from ${File("").absolutePath}")
        val oldFile = File(repoRoot, OLD_APK_NAME)
        val outDir = File(repoRoot, "data/build/delta-probe").apply { mkdirs() }

        val koin = startKoin { modules(dataModules) }.koin
        try {
            val api = koin.get<XiaomiApi>()
            val profile = koin.get<ProfileRepository>().load()
            val account = koin.get<AccountRepository>()
            val oldMd5 = md5File(oldFile)
            println("[probe] old apk size=${oldFile.length()} md5=$oldMd5")

            val check = api.checkManualUpdate(
                ManualUpdateRequest(
                    packageName = PACKAGE_NAME,
                    versionCode = OLD_VERSION_CODE,
                    versionName = OLD_VERSION_NAME,
                    isSystemApp = false,
                    oldApkHash = oldMd5,
                ),
                profile,
                account.cookie(),
                account.security(),
            )
            println(
                "[probe] manual update status=${check.status} " +
                        "target=${check.app?.versionName}(${check.app?.versionCode}) deltaSize=${check.app?.deltaSize}"
            )
            val app = requireNotNull(check.app) { "no update available: ${check.status}" }.copy(
                installedVersionCode = OLD_VERSION_CODE,
                installedVersionName = OLD_VERSION_NAME,
                installedOldApkHash = oldMd5,
            )

            val meta = api.downloadUpdateMeta(app, profile, account.cookie())
            for (p in meta.parts) {
                println("[probe] part name=${p.name} type=${p.type} size=${p.size} hash=${p.hash} patch=${p.patch}")
            }
            val part = meta.parts.firstOrNull { it.patch != null } ?: meta.parts.first()
            val patchInfo = requireNotNull(part.patch) { "server returned no diff for oldApkHash=$oldMd5" }
            println("[probe] patch url=${patchInfo.url} size=${patchInfo.size} hash=${patchInfo.hash} version=${patchInfo.version}")

            val patchFile = File(outDir, "patch.bin")
            val fullFile = File(outDir, "new-full.apk")
            download(patchInfo.url, meta.requestHeaders, patchFile)
            download(part.url, meta.requestHeaders, fullFile)
            println("[probe] downloaded patch=${patchFile.length()} md5=${md5Hex(patchFile.readBytes())} (server ${patchInfo.hash})")
            println("[probe] downloaded full=${fullFile.length()}")

            val outFile = File(outDir, "kotlin-out.apk")
            val started = System.nanoTime()
            HDiffCore.applyFiles(patchFile, oldFile, outFile)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println("[probe] kotlin applyFiles ok in ${elapsedMs}ms out=${outFile.length()} md5=${md5File(outFile)}")

            val fullMd5 = md5File(fullFile)
            println("[probe] full apk md5=$fullMd5 (server hash=${part.hash})")
            assertEquals(fullMd5, md5File(outFile), "synthesized apk differs from server full apk")
        } finally {
            stopKoin()
        }
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun md5File(file: File): String {
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

    private fun download(url: String, headers: Map<String, String>, target: File) {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        val builder = HttpRequest.newBuilder(URI.create(url))
        headers.forEach { (k, v) -> runCatching { builder.header(k, v) } }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofFile(target.toPath()))
        check(response.statusCode() in 200..299) { "download failed ${response.statusCode()} for $url" }
    }
}
