package top.app.market.data.remote.fdroid

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.preference.FDroidMirror
import java.io.File
import java.util.zip.ZipFile

private const val IndexJarName = "index-v1.jar"
private const val IndexJsonEntry = "index-v1.json"
private const val CacheFileName = "index-v2.jsonl"
private const val MetaFileName = "index.meta"

/** 缓存多久内不再向镜像确认更新（镜像约每天同步一次）。 */
private const val RecheckIntervalMillis = 12L * 60 * 60 * 1000

/**
 * 从镜像源下载 `index-v1.jar`（约 16MB，内含完整仓库索引），解析成精简索引后落盘缓存。
 *
 * 缓存与镜像无关（各镜像索引内容一致），只有下载时才用到所选镜像；
 * 超过 [RecheckIntervalMillis] 后先读几 KB 的 `entry.json` 比对时间戳，未更新则不重新下载。
 */
internal class JvmFDroidIndexSource(
    private val client: HttpClient,
    cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : FDroidIndexSource {
    private val dir = File(cacheDir, "fdroid")
    private val cacheFile = File(dir, CacheFileName)
    private val metaFile = File(dir, MetaFileName)
    private val mutex = Mutex()

    @Volatile
    private var memory: FDroidIndex? = null

    override suspend fun load(mirror: FDroidMirror): FDroidIndex = mutex.withLock {
        val cached = memory ?: readCache()?.also { memory = it }
        if (cached != null && !needsRecheck()) return cached

        try {
            val remoteTimestamp = fetchRemoteTimestamp(mirror)
            if (cached != null && remoteTimestamp == cachedTimestamp()) {
                touchMeta()
                return cached
            }
            downloadAndBuild(mirror, remoteTimestamp).also { memory = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 镜像暂时不可用时宁可用旧索引，也不让搜索整体失败
            cached ?: throw MarketException("F-Droid 索引下载失败：${e.message ?: e::class.simpleName}")
        }
    }

    private fun needsRecheck(): Boolean {
        if (!metaFile.exists()) return true
        return clock() - metaFile.lastModified() >= RecheckIntervalMillis
    }

    private fun cachedTimestamp(): Long = runCatching { metaFile.readText().trim().toLong() }.getOrDefault(-1L)

    private fun touchMeta() {
        metaFile.setLastModified(clock())
    }

    private suspend fun fetchRemoteTimestamp(mirror: FDroidMirror): Long {
        val response = client.get("${mirror.repoBaseUrl}/entry.json")
        if (!response.status.isSuccess()) throw MarketException("HTTP ${response.status.value}")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("timestamp").jsonPrimitive.long
    }

    private suspend fun downloadAndBuild(mirror: FDroidMirror, timestamp: Long): FDroidIndex = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val jar = File(dir, "$IndexJarName.part")
        val tmpCache = File(dir, "$CacheFileName.part")
        try {
            downloadJar(mirror, jar)
            val index = ZipFile(jar).use { zip ->
                val entry = zip.getEntry(IndexJsonEntry) ?: throw MarketException("索引包缺少 $IndexJsonEntry")
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use(FDroidIndexParser::parse)
            }
            tmpCache.bufferedWriter(Charsets.UTF_8).use { out ->
                index.apps.forEach { out.appendLine(FDroidIndexParser.encode(it)) }
            }
            // 先写缓存再写 meta：中途失败时 meta 仍是旧时间戳，下次会重新确认
            check(tmpCache.renameTo(cacheFile) || (cacheFile.delete() && tmpCache.renameTo(cacheFile))) {
                "Failed to replace index cache"
            }
            metaFile.writeText(timestamp.toString())
            touchMeta()
            index
        } finally {
            jar.delete()
            tmpCache.delete()
        }
    }

    private suspend fun downloadJar(mirror: FDroidMirror, target: File) {
        client.prepareGet("${mirror.repoBaseUrl}/$IndexJarName") {
            // 16MB 的整包下载不能套用共用客户端的 60s 总超时；仅保留空闲超时
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 30_000
            }
        }.execute { response ->
            if (!response.status.isSuccess()) throw MarketException("HTTP ${response.status.value}")
            target.outputStream().buffered().use { out -> response.bodyAsChannel().copyTo(out) }
        }
    }

    private fun readCache(): FDroidIndex? {
        if (!cacheFile.exists()) return null
        return runCatching {
            val apps = cacheFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.map(FDroidIndexParser::decode).toList()
            }
            FDroidIndex(apps).takeIf { apps.isNotEmpty() }
        }.getOrNull() // 缓存损坏当作没有缓存，重新下载
    }
}
