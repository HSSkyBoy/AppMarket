package top.app.market.data.remote.vivo

import top.app.market.data.platform.debugLog
import top.app.market.data.remote.urlEncodeParameters
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.profile.MarketProfile
import top.app.market.domain.repository.ProfileRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

internal data class VivoUpdateEntry(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val icon: String,
    val versionName: String,
    val versionCode: Long,
    val sizeKiB: Long,
    val md5: String,
    val updateDescription: String,
    val apkType: String,
    val patchs: String,
    val sfPatchs: String,
    val downloadUrl: String,
)

internal data class VivoSummaryEntry(
    val packageName: String,
    val versionCode: Long,
    val md5: String,
    val sfPatchs: String,
    val summaryDiffUrl: String,
    val apkType: String,
)

/** Response produced by vivo's app-upgrade SDK, used by built-in/system applications. */
internal data class VivoSelfUpdateEntry(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String,
    val size: Long,
    val md5: String,
    val sha256: String,
    val patchDescriptor: String,
    val patchSize: Long,
    val patchMd5: String,
    val patchSha256: String,
    val updateDescription: String,
    val updateTime: Long,
)

/** Package/version advertised by vivo's own upgrade service for the current device profile. */
internal data class VivoSupportEntry(
    val packageName: String,
    val versionCode: Long,
    val tabletVersionCode: Long,
)

internal data class VivoPatchDescriptor(
    val plan: Int,
    val targetVersion: Long,
    val baseVersion: Long,
    val sizeKiB: Long,
    val patchHash: Long,
    val oldApkHash: Long,
)

internal class VivoUpdateApi(
    private val client: HttpClient,
    private val profileStore: ProfileRepository,
) {
    suspend fun checkUpdates(installed: List<InstalledPackage>): List<VivoUpdateEntry> {
        if (installed.isEmpty()) return emptyList()
        val profile = profileStore.load(AppSource.VIVO)
        return installed.chunked(MAX_PACKAGES_PER_REQUEST).flatMap { requestUpdates(it, profile) }
    }

    suspend fun checkManualUpdate(installed: InstalledPackage): List<VivoUpdateEntry> {
        val profile = profileStore.load(AppSource.VIVO)
        return requestUpdates(listOf(installed), profile)
    }

    /**
     * The update endpoint hides an entry when the supplied version is already newer. Querying
     * version 0 lets us resolve vivo's non-public/system catalogue without offering a downgrade.
     */
    suspend fun catalog(installed: List<InstalledPackage>): List<VivoUpdateEntry> {
        if (installed.isEmpty()) return emptyList()
        val profile = profileStore.load(AppSource.VIVO)
        return installed.chunked(MAX_PACKAGES_PER_REQUEST).flatMap { packages ->
            requestUpdates(packages.map { it.copy(versionCode = 0L, oldApkHash = "0") }, profile)
        }
    }

    suspend fun checkSelfUpdate(
        installed: InstalledPackage,
        manual: Boolean,
        appSha256: String = "",
    ): VivoSelfUpdateEntry? {
        val profile = profileStore.load(AppSource.VIVO)
        return requestSelfUpdate(installed, profile, manual, appSha256)
    }

    suspend fun checkSelfUpdates(
        installed: List<InstalledPackage>,
        manual: Boolean = false,
    ): List<VivoSelfUpdateEntry> {
        if (installed.isEmpty()) return emptyList()
        val profile = profileStore.load(AppSource.VIVO)
        val slots = Semaphore(MAX_SELF_UPDATE_REQUESTS)
        return coroutineScope {
            installed.map { app ->
                async {
                    slots.withPermit {
                        runCatching { requestSelfUpdate(app, profile, manual) }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Returns vivo's server-side system/update catalogue. This is deliberately not intersected
     * with a local prefix list: the server, rather than AppMarket, decides which packages exist.
     */
    suspend fun supportPackages(): List<VivoSupportEntry> {
        val profile = profileStore.load(AppSource.VIVO)
        val response = client.post(SUPPORT_PACKAGES_API) {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.UserAgent, vivoNativeUserAgent(profile))
            setBody(urlEncodeParameters(vivoSupportPackageParams(profile)))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("VivoUpdateApi") {
                "support packages HTTP ${response.status.value} ${response.request.url.encodedPath}: ${text.take(200)}"
            }
            throw MarketException("vivo 系统应用目录接口返回异常 HTTP ${response.status.value}")
        }
        val root = parseVivoObject(text)
        if (root.int("retcode", -1) != 0) {
            throw MarketException("vivo 系统应用目录请求被拒绝")
        }
        return parseVivoSupportPackages(root)
    }

    suspend fun summary(packageNames: List<String>): List<VivoSummaryEntry> {
        val names = packageNames.filter(String::isNotBlank).distinct()
        if (names.isEmpty()) return emptyList()
        val url = "$MAIN_API/interfaces/diff/get-summary-file?packageNames=" +
                names.joinToString(",").encodeURLParameter()
        val response = client.get(url)
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("VivoUpdateApi") { "HTTP ${response.status.value} ${response.request.url.encodedPath}" }
            throw MarketException("vivo summary 接口返回异常 HTTP ${response.status.value}")
        }
        return parseVivoSummaryResponse(parseVivoObject(text))
    }

    /**
     * appDownload currently redirects SFPat21 requests to an HTTP CDN URL, although that same
     * object is served over HTTPS. Resolve the signed entry URL, then upgrade the final vivo CDN
     * URL before handing it to the HTTPS-only installer.
     */
    suspend fun resolveSecureDownloadUrl(url: String): String? = runCatching {
        if (!url.startsWith("https://", ignoreCase = true)) return@runCatching null
        resolveVivoDownloadAsset(client, url).url
    }.getOrNull()

    private suspend fun requestUpdates(
        installed: List<InstalledPackage>,
        profile: MarketProfile,
    ): List<VivoUpdateEntry> {
        val packageValue = vivoPackagesValue(installed)
        val appVersion = profile.marketVersion.ifBlank { DEFAULT_APP_VERSION }
        val screenSize = profile.resolution.replace('*', '_')
        val response = client.post(UPDATE_API) {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.UserAgent, vivoNativeUserAgent(profile))
            setBody(
                urlEncodeParameters(
                    mapOf(
                        "packages" to packageValue,
                        "querySource" to "2",
                        "dbversion" to "0",
                        "downgrade" to "0",
                        "n" to "0",
                        "suggest64Bit" to "1",
                        "gpInstalled" to "false",
                        "gpLogin" to "false",
                        "secondInstallList" to "",
                        "patch_sup" to "2",
                        "cpuInfo" to profile.cpuArchitecture,
                        "app_version" to appVersion,
                        "appversion" to appVersion,
                        "build_number" to appVersion,
                        "model" to profile.model,
                        "deviceType" to profile.device,
                        // The original vivo APK sends av=SDK_INT and an=Android release.
                        "av" to profile.sdk,
                        "an" to profile.androidVersion,
                        "android_version" to profile.sdk,
                        "android_name" to profile.androidVersion,
                        "sys_build_id" to profile.buildId,
                        "density" to profile.densityScaleFactor,
                        "screensize" to screenSize,
                        "mfr" to "vivo",
                        "pictype" to "webp",
                        "cs" to "0",
                    )
                )
            )
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("VivoUpdateApi") { "HTTP ${response.status.value} ${response.request.url.encodedPath}: ${text.take(200)}" }
            throw MarketException("vivo 更新接口返回异常 HTTP ${response.status.value}")
        }
        return parseVivoUpdateResponse(parseVivoObject(text))
    }

    private suspend fun requestSelfUpdate(
        installed: InstalledPackage,
        profile: MarketProfile,
        manual: Boolean,
        appSha256: String = "",
    ): VivoSelfUpdateEntry? {
        val response = client.post(SELF_UPDATE_API) {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.UserAgent, vivoNativeUserAgent(profile))
            setBody(urlEncodeParameters(vivoSelfUpdateParams(installed, profile, manual, appSha256)))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("VivoUpdateApi") {
                "self update HTTP ${response.status.value} ${response.request.url.encodedPath}: ${text.take(200)}"
            }
            throw MarketException("vivo 系统应用更新接口返回异常 HTTP ${response.status.value}")
        }
        return parseVivoSelfUpdateResponse(parseVivoObject(text))
    }

    private companion object {
        const val UPDATE_API = "https://update.appstore.vivo.com.cn/port/packages_update/"
        const val SELF_UPDATE_API = "https://appupgrade.vivo.com.cn/appSelfUpgrade"
        const val SUPPORT_PACKAGES_API = "https://appupgrade.vivo.com.cn/querySupportAppList"
        const val MAIN_API = "https://main.appstore.vivo.com.cn"
        const val DEFAULT_APP_VERSION = "61510"
        const val MAX_PACKAGES_PER_REQUEST = 100
        const val MAX_SELF_UPDATE_REQUESTS = 6
    }
}

internal fun vivoSupportPackageParams(profile: MarketProfile): Map<String, String> {
    val country = profile.co.ifBlank { "CN" }
    val language = profile.la.ifBlank { "zh" }
    val abiList = profile.cpuArchitecture.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(prefix = "[", postfix = "]", separator = ", ")
    val appVersion = profile.marketVersion.ifBlank { "61510" }
    return linkedMapOf(
        "model" to profile.model,
        "av" to profile.sdk,
        "an" to profile.androidVersion,
        "mfr" to "vivo",
        "vaid" to "",
        "imei" to "",
        "countrycode" to country,
        "deviceType" to "phone",
        "osVersion" to "-1.0",
        "abiList" to abiList,
        "origin" to "10",
        "supPatch" to "3",
        "sdkType" to "1",
        "locale" to language,
        "language" to "${language}_$country",
        "country" to country,
        "elapsedtime" to Clock.System.now().toEpochMilliseconds().toString(),
        "nt" to "WIFI",
        "build_number" to profile.os,
        "supPadding" to "1",
        "ssv" to "0",
        "versionCode" to appVersion,
        "pkgName" to "com.bbk.appstore",
        "romVersion" to profile.osBigVersionName.ifBlank { "OriginOS" },
    )
}

internal fun vivoSelfUpdateParams(
    installed: InstalledPackage,
    profile: MarketProfile,
    manual: Boolean,
    appSha256: String = "",
): Map<String, String> {
    val country = profile.co.ifBlank { "CN" }
    val language = profile.la.ifBlank { "zh" }
    val abiList = profile.cpuArchitecture.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(prefix = "[", postfix = "]", separator = ", ")
    val normalizedSha256 = appSha256.trim().lowercase()
        .takeIf { it.length == 64 && it.all { character -> character in "0123456789abcdef" } }
        .orEmpty()
    val patchCapable = normalizedSha256.isNotEmpty()
    return linkedMapOf(
        "model" to profile.model,
        "av" to profile.sdk,
        "an" to profile.androidVersion,
        "versionName" to installed.versionName,
        "sdkVersion" to SELF_UPDATE_SDK_VERSION,
        "versionCode" to installed.versionCode.coerceAtLeast(0L).toString(),
        "mfr" to "vivo",
        // These blank identifier fields are still mandatory in the plaintext fallback protocol.
        "vaid" to "",
        "imei" to "",
        "countrycode" to country,
        "deviceType" to "phone",
        // FtBuild is unavailable on a non-vivo host; the official SDK uses -1.0 in that case.
        "osVersion" to "-1.0",
        "abiList" to abiList,
        "pkgName" to installed.packageName,
        // The service only discloses SFPat21 metadata when the exact installed APK is identified.
        "appSha256" to normalizedSha256.ifBlank { "000000" },
        "origin" to "1",
        "supPatch" to if (patchCapable) "3" else "0",
        "manual" to if (manual) "1" else "0",
        "sdkType" to "1",
        "locale" to language,
        "language" to "${language}_$country",
        "country" to country,
        "elapsedtime" to Clock.System.now().toEpochMilliseconds().toString(),
        "nt" to "WIFI",
        // vivo's SDK calls this ro.build.version.bbk; 模板1 stores that value in os.
        "build_number" to profile.os,
        "supPadding" to if (patchCapable) "1" else "0",
    )
}

private const val SELF_UPDATE_SDK_VERSION = "6650"

internal fun vivoPackagesValue(installed: List<InstalledPackage>): String =
    installed.joinToString(",") { app ->
        "${app.packageName}|${app.versionCode.coerceAtLeast(0L)}|0"
    }

internal fun parseVivoUpdateResponse(json: JsonObject): List<VivoUpdateEntry> {
    val value = json.arr("value") ?: return emptyList()
    return (0 until value.size).mapNotNull { index ->
        val item = value.objAt(index) ?: return@mapNotNull null
        val packageName = item.str("package_name").trim()
        if (packageName.isBlank()) return@mapNotNull null
        VivoUpdateEntry(
            appId = item.long("id"),
            packageName = packageName,
            displayName = item.str("title_zh", packageName),
            icon = vivoHttpsUrl(item.str("icon_url")),
            versionName = item.str("version_name"),
            versionCode = item.long("version_code"),
            sizeKiB = item.long("size"),
            md5 = item.str("md5", item.str("originalMd5")).lowercase(),
            updateDescription = item.str("update_des"),
            apkType = item.str("apkType"),
            patchs = item.str("patchs"),
            sfPatchs = item.str("sfPatchs"),
            downloadUrl = item.str("download_url"),
        )
    }
}

internal fun parseVivoSelfUpdateResponse(json: JsonObject): VivoSelfUpdateEntry? {
    if (json.int("retcode", -1) != 0) return null
    val data = json.obj("data") ?: return null
    val packageName = data.str("pkgName").trim()
    val versionCode = data.long("versionCode")
    val downloadUrl = vivoHttpsUrl(data.str("downloadUrl"))
    if (packageName.isBlank() || versionCode <= 0L || downloadUrl.isBlank()) return null
    return VivoSelfUpdateEntry(
        packageName = packageName,
        versionName = data.str("versionName"),
        versionCode = versionCode,
        downloadUrl = downloadUrl,
        size = data.long("apkSize"),
        md5 = data.str("apkMd5").lowercase(),
        sha256 = data.str("apkSha256").lowercase(),
        patchDescriptor = data.str("patch"),
        patchSize = data.long("patchSize"),
        patchMd5 = data.str("patchMd5").lowercase(),
        patchSha256 = data.str("patchSha256").lowercase(),
        updateDescription = data.str("notifyContent"),
        updateTime = data.long("updateDate"),
    )
}

internal fun parseVivoSupportPackages(json: JsonObject): List<VivoSupportEntry> {
    val data = json.arr("data") ?: return emptyList()
    return (0 until data.size).mapNotNull { index ->
        val item = data.objAt(index) ?: return@mapNotNull null
        val packageName = item.str("pkgName").trim()
        if (packageName.isBlank()) return@mapNotNull null
        VivoSupportEntry(
            packageName = packageName,
            versionCode = item.long("versionCode"),
            tabletVersionCode = item.long("tabletVersionCode"),
        )
    }.distinctBy { it.packageName.lowercase() }
}

internal fun parseVivoSummaryResponse(json: JsonObject): List<VivoSummaryEntry> {
    val value = json.arr("value") ?: return emptyList()
    return (0 until value.size).mapNotNull { index ->
        val item = value.objAt(index) ?: return@mapNotNull null
        val packageName = item.str("packageName").trim()
        if (packageName.isBlank()) return@mapNotNull null
        VivoSummaryEntry(
            packageName = packageName,
            versionCode = item.long("versionCode"),
            md5 = item.str("md5").lowercase(),
            sfPatchs = item.str("sfPatchs"),
            summaryDiffUrl = item.str("summaryDiffUrl"),
            apkType = item.str("apkType"),
        )
    }
}

internal fun parseVivoPatchDescriptors(raw: String): List<VivoPatchDescriptor> =
    raw.split(',').mapNotNull { token ->
        val parts = token.trim().split(':')
        if (parts.size != 4) return@mapNotNull null
        val head = parts[0]
        val versions = head.substringAfter('_', "").split('_')
        if (versions.size != 2) return@mapNotNull null
        val plan = head.substringBefore('_')
            .takeIf { it.startsWith('v') }
            ?.removePrefix("v")
            ?.toIntOrNull()
            ?: 2
        val target = versions[0].toLongOrNull() ?: return@mapNotNull null
        val base = versions[1].toLongOrNull() ?: return@mapNotNull null
        val size = parts[1].toLongOrNull() ?: return@mapNotNull null
        val patchHash = parts[2].toLongOrNull() ?: return@mapNotNull null
        val oldHash = parts[3].toLongOrNull() ?: return@mapNotNull null
        if (target <= 0L || base < 0L || size <= 0L) return@mapNotNull null
        VivoPatchDescriptor(plan, target, base, size, patchHash, oldHash)
    }

internal fun selectVivoPatch(raw: String, targetVersion: Long, baseVersion: Long, oldApkHash: Long): VivoPatchDescriptor? =
    parseVivoPatchDescriptors(raw).firstOrNull {
        it.targetVersion == targetVersion && it.baseVersion == baseVersion && it.oldApkHash == oldApkHash
    }

internal fun secureVivoDownloadUrl(candidate: String): String? = runCatching {
    vivoHttpsUrl(candidate).takeIf { secure ->
        secure.startsWith("https://", ignoreCase = true) &&
                Url(secure).host.lowercase().let { it == "vivo.com.cn" || it.endsWith(".vivo.com.cn") }
    }
}.getOrNull()

/** HEAD 跟随后的 vivo CDN 直链与真实大小 / OSS md5。 */
internal data class ResolvedVivoAsset(
    val url: String,
    val size: Long = 0L,
    val md5: String = "",
)

/**
 * vivo 的下载地址是 302 入口，最终 CDN 是明文 http。下载器只接受 https 直链且不跟随重定向，
 * 这里跟随并把 http 升级为 https（校验 vivo 域名），同时取回真实 Content-Length 与 OSS md5。
 */
internal suspend fun resolveVivoDownloadAsset(
    client: HttpClient,
    url: String,
): ResolvedVivoAsset {
    var current = url
    repeat(MAX_REDIRECTS) {
        val response = runCatching { client.head(current) }.getOrElse {
            debugLog("VivoDownload") { "HEAD failed for $current: ${it.message}" }
            return ResolvedVivoAsset(current)
        }
        when (response.status.value) {
            301, 302, 303, 307, 308 -> {
                val location = response.headers[HttpHeaders.Location]?.takeIf(String::isNotBlank)
                    ?: return ResolvedVivoAsset(current)
                current = secureVivoDownloadUrl(vivoResolveLocation(current, location)) ?: current
            }

            in 200..299 -> return ResolvedVivoAsset(
                url = current,
                size = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L,
                md5 = response.headers["X-Oss-Meta-Md5"].orEmpty(),
            )

            else -> {
                debugLog("VivoDownload") { "HEAD ${response.status.value} for $current" }
                return ResolvedVivoAsset(current)
            }
        }
    }
    return ResolvedVivoAsset(current)
}

private fun vivoResolveLocation(base: String, location: String): String = when {
    location.startsWith("https://", ignoreCase = true) -> location
    location.startsWith("http://", ignoreCase = true) -> "https://${location.substring(7)}"
    location.startsWith("//", ignoreCase = true) -> "https:$location"
    location.startsWith("/") -> {
        val origin = Regex("^https?://[^/]+").find(base)?.value
        if (origin == null) base else origin + location
    }

    else -> base
}

private const val MAX_REDIRECTS = 5
