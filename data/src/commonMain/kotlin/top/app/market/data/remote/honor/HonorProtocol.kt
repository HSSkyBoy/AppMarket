package top.app.market.data.remote.honor

import top.app.market.data.platform.debugLog
import top.app.market.data.platform.hmac
import top.app.market.data.platform.sha256
import top.app.market.data.remote.xiaomi.int
import top.app.market.data.remote.xiaomi.parseJsonObject
import top.app.market.data.remote.xiaomi.str
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.profile.MarketProfile
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

internal class HonorProtocol(
    private val client: HttpClient,
) {
    suspend fun isUsableHttpsUrl(url: String): Boolean {
        if (!url.startsWith("https://", true)) return false
        return runCatching {
            withTimeout(HTTP_PROBE_TIMEOUT_MILLIS) {
                client.head(url).status.value in 200..399
            }
        }.getOrDefault(false)
    }

    suspend fun post(
        path: String,
        profile: MarketProfile,
        payload: JsonObject = buildJsonObject {},
    ): JsonObject = postToBase(
        baseUrl = honorApiBase(profile.deliveryCountry.ifBlank { profile.co }),
        path = path,
        profile = profile,
        payload = payload,
    )

    suspend fun postBackground(
        path: String,
        profile: MarketProfile,
        payload: JsonObject = buildJsonObject {},
    ): JsonObject = postToBase(
        baseUrl = honorBackgroundApiBase(profile.deliveryCountry.ifBlank { profile.co }),
        path = path,
        profile = profile,
        payload = payload,
    )

    private suspend fun postToBase(
        baseUrl: String,
        path: String,
        profile: MarketProfile,
        payload: JsonObject,
    ): JsonObject {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val traceId = randomHex(32)
        val isSearch = path.startsWith(HONOR_SEARCH_PREFIX)
        val bodyObject = buildJsonObject {
            payload.forEach { (name, value) -> put(name, value) }
            if (isSearch && "marketId" !in payload) put("marketId", HONOR_MARKET_ID)
            if ("tInfo" !in payload) {
                put(
                    "tInfo",
                    if (isSearch) searchTerminal(profile, timestamp, path) else terminal(profile, timestamp),
                )
            }
        }
        val body = bodyObject.toString()
        val signature = HonorSigner.sign(path, timestamp, traceId, body)
        debugLog("HonorProtocol") {
            "POST ${baseUrl.substringAfter("://").substringBefore('/')} $path " +
                    "profile=${profile.htype} magic=${profile.magicVersion} api=${profile.androidApiVersion}"
        }
        val response = client.post(baseUrl + path) {
            contentType(
                if (isSearch) ContentType.Application.Json.withCharset(Charsets.UTF_8)
                else ContentType.Application.Json,
            )
            if (!isSearch) header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.UserAgent, honorUserAgent(profile))
            header("x-uuid", profile.instanceId)
            header("apkVer", profile.apkVer.ifBlank { HONOR_APK_VERSION })
            header("flavor", "product")
            header("No-ECDH", "1")
            header("traceId", traceId)
            header("areaId", honorAreaId(profile))
            header("udid", honorUdid(profile))
            header("androidId", honorAndroidId(profile))
            header("uid", "")
            header("model", profile.htype)
            header("sysVersion", honorSysVersion(profile))
            header(
                "magicSysVersion",
                profile.honorMagicSysVersion.ifBlank { profile.osV2.ifBlank { profile.magicVersion } },
            )
            header("androidVersion", profile.osVer)
            header("launchType", "-1")
            header("caller", HONOR_CLIENT_PACKAGE)
            if (isSearch) header("dpCaller", "")
            header("language", honorLanguage(profile))
            header("userType", honorUserType(profile))
            if (isSearch) header("sandboxHostPackageName", "")
            header("market-timestamp", timestamp.toString())
            header("market-package-name", HONOR_CLIENT_PACKAGE)
            header("market-sign-type", "HmacSHA256")
            header("market-sign-value", signature)
            setBody(body)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("HonorProtocol") {
                "HTTP ${response.status.value} ${response.request.url.encodedPath}: ${text.take(200)}"
            }
            throw MarketException("荣耀服务器返回异常状态 HTTP ${response.status.value}")
        }
        val root = runCatching { parseJsonObject(text) }.getOrElse {
            debugLog("HonorProtocol") { "Invalid JSON ${response.request.url.encodedPath}: ${text.take(200)}" }
            throw MarketException("荣耀接口返回了非预期格式")
        }
        val errorCode = root.int("errorCode", root.str("errorCode").toIntOrNull() ?: 0)
        if (errorCode != 0) {
            val message = root.str("errorMessage").ifBlank { "错误码 $errorCode" }
            throw MarketException("荣耀接口请求失败：$message")
        }
        return root
    }

    suspend fun postUnsigned(
        url: String,
        payload: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): JsonObject {
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(payload.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw MarketException("荣耀服务器返回异常状态 HTTP ${response.status.value}")
        }
        val root = runCatching { parseJsonObject(text) }.getOrElse {
            throw MarketException("荣耀接口返回了非预期格式")
        }
        val errorCode = root.int("errorCode", root.str("errorCode").toIntOrNull() ?: 0)
        if (errorCode != 0) {
            val message = root.str("errorMessage").ifBlank { "错误码 $errorCode" }
            throw MarketException("荣耀接口请求失败：$message")
        }
        return root
    }

    internal fun searchTerminal(profile: MarketProfile, timestamp: Long, path: String): JsonObject {
        val cpuAbilist = profile.cpuArchitecture.ifBlank { profile.cpu.ifBlank { "arm64-v8a" } }
        val cpu = cpuAbilist.substringBefore(',').ifBlank { profile.cpu.ifBlank { "arm64-v8a" } }
        val activation = path == HONOR_SEARCH_ACTIVATION_PATH
        return buildJsonObject {
            put("accessToken", "")
            put("activationFlag", -1)
            put("ageLimit", 3)
            put("androidApiVersion", profile.androidApiVersion)
            put("androidId", honorAndroidId(profile))
            put("apkVerName", profile.apkVerName.ifBlank { HONOR_APK_VERSION_NAME })
            put("apkVer", profile.apkVer.ifBlank { HONOR_APK_VERSION }.toIntOrNull() ?: HONOR_APK_VERSION.toInt())
            put("carrier", "Unknown")
            put("chId", "HONOR_01")
            putJsonObject("compatibilityVar") {
                put("cpuAbi", cpu)
                put("bintranslatorEnable", -1)
                put("cpuAbilist", cpuAbilist)
                put("hasHCore", 1)
            }
            putJsonArray("compressScenes") { add(1) }
            put("connectionType", 2)
            put("cpu", cpu)
            put("deliveryCountry", honorDeliveryCountry(profile))
            put("deviceMode", profile.honorDeviceMode.toIntOrNull() ?: 2)
            put("dpi", profile.dpi)
            put("hman", profile.hman.ifBlank { "HONOR" })
            put("htype", profile.htype)
            put("isDark", false)
            put("isDemoType", 0)
            put("isH5", 0)
            put("kidsMode", false)
            put("isMonkey", 0)
            put("isParallelSpace", profile.honorIsParallelSpace.toIntOrNull() ?: 1)
            put("isPersonalRecommend", true)
            put("randomId", honorRandomId(profile))
            put("isRecommend", false)
            put("language", honorLanguage(profile))
            put("magicVersion", honorSysVersion(profile))
            put("marketType", 0)
            put("miniGameInstall", 0)
            put("netType", 3)
            put("oaid", honorOaid(profile))
            put("os", 0)
            put("osVer", profile.osVer)
            put("pName", HONOR_CLIENT_PACKAGE)
            put("patchSdkVersion", 0)
            put("pushToken", "")
            put("requestSource", 1)
            put("resolution", honorResolution(profile))
            put("source", "1")
            put("spreadModelName", "")
            put("subTerminalType", "1_1")
            put("supportGms", honorSupportGms(profile))
            putJsonObject("targetedParamMap") {
                put("SourcePage", if (activation) "HomePage5" else "MainSearchScene")
            }
            put("terminalType", profile.terminalType.toIntOrNull()?.takeIf { it in 1..3 } ?: 1)
            put("timestamp", timestamp)
            putJsonObject("trackingParamMap") {
                put("first_page_code", if (activation) "05" else "07")
            }
            put("udid", honorUdid(profile))
            put("userId", "")
            put("userType", honorUserType(profile))
            put("telecomOper", "")
        }
    }

    internal fun terminal(profile: MarketProfile, timestamp: Long): JsonObject = buildJsonObject {
        val cpuAbiList = profile.cpuArchitecture.ifBlank { profile.cpu.ifBlank { "arm64-v8a" } }
        val cpuAbi = cpuAbiList.substringBefore(',').ifBlank { "arm64-v8a" }
        put("accessToken", "")
        put("activationFlag", -1)
        put("ageLimit", 3)
        put("androidId", honorAndroidId(profile))
        put("androidApiVersion", profile.androidApiVersion)
        put("apkVer", profile.apkVer.ifBlank { HONOR_APK_VERSION }.toIntOrNull() ?: HONOR_APK_VERSION.toInt())
        put("apkVerName", profile.apkVerName.ifBlank { HONOR_APK_VERSION_NAME })
        put("carrier", "Unknown")
        put("chId", "HONOR_01")
        putJsonObject("compatibilityVar") {
            put("cpuAbi", cpuAbi)
            put("bintranslatorEnable", -1)
            put("cpuAbilist", cpuAbiList)
            put("hasHCore", 1)
        }
        putJsonArray("compressScenes") { add(1) }
        put("connectionType", 2)
        put("cpu", cpuAbi)
        put("deliveryCountry", honorDeliveryCountry(profile))
        put("deviceMode", profile.honorDeviceMode.toIntOrNull() ?: 2)
        put("dpi", profile.dpi)
        put("hman", profile.hman.ifBlank { "HONOR" })
        put("htype", profile.htype)
        put("isDark", false)
        put("isDemoType", 0)
        put("isH5", 0)
        put("isMonkey", 0)
        put("isParallelSpace", profile.honorIsParallelSpace.toIntOrNull() ?: 1)
        put("isPersonalRecommend", true)
        put("isRecommend", false)
        put("kidsMode", false)
        put("language", honorLanguage(profile))
        put("magicVersion", honorSysVersion(profile))
        put("marketType", 0)
        put("miniGameInstall", 0)
        put("netType", 3)
        put("os", 0)
        put("osVer", profile.osVer)
        put("pName", HONOR_CLIENT_PACKAGE)
        put("patchSdkVersion", 0)
        put("randomId", honorRandomId(profile))
        put("requestSource", 1)
        put("resolution", honorResolution(profile))
        put("source", "1")
        put("spreadModelName", profile.spreadModelName)
        put("subTerminalType", "1_1")
        put("supportGms", honorSupportGms(profile))
        put("telecomOper", "")
        put("terminalType", profile.terminalType.toIntOrNull()?.takeIf { it in 1..3 } ?: 1)
        put("timestamp", timestamp)
        put("udid", honorUdid(profile))
        put("userId", "")
        put("userType", honorUserType(profile))
        put("oaid", honorOaid(profile))
    }
}

internal object HonorSigner {
    fun sign(path: String, timestamp: Long, traceId: String, body: String): String {
        val encodedKey = HonorCompatibility.requestKeyBase64
        if (encodedKey.isBlank()) {
            throw MarketException("荣耀协议兼容配置缺失")
        }
        val key = runCatching { Base64.decode(encodedKey) }.getOrElse {
            throw MarketException("荣耀协议兼容配置格式无效")
        }
        return signWithRequestKey(key, path, timestamp, traceId, body)
    }

    internal fun signWithRequestKey(
        requestKey: ByteArray,
        path: String,
        timestamp: Long,
        traceId: String,
        body: String,
    ): String {
        val headerJson = "{\"market-timestamp\":\"$timestamp\",\"traceId\":\"$traceId\"}"
        val message = (path + headerJson + body).encodeToByteArray()
        val signKey = requestKey + sha256(message)
        return hmac(
            "HmacSHA256",
            signKey,
            message,
        ).toHex()
    }
}

/**
 * 官方客户端的 x-uuid、androidId、randomId 是三个彼此独立的持久化随机值。用带域分隔的哈希
 * 从本应用持久化的 instanceId 派生 androidId，既保持同样的稳定/独立形态，也不读取硬件标识。
 */
internal fun honorAndroidId(profile: MarketProfile): String =
    profile.honorAndroidId.ifBlank {
        sha256(("honor-android:" + profile.instanceId).encodeToByteArray()).toHex().take(16)
    }

/**
 * Honor firmware exposes a hardware UDID to the official client. AppMarket instead derives a stable,
 * app-scoped pseudonymous value so headers and tInfo keep the same protocol shape without reading or
 * persisting a hardware identifier.
 */
internal fun honorUdid(profile: MarketProfile): String =
    profile.honorUdid.ifBlank {
        sha256(("honor-udid:" + profile.instanceId).encodeToByteArray()).toHex()
    }

private fun honorRandomId(profile: MarketProfile): String {
    val hex = sha256(("honor-random:" + profile.instanceId).encodeToByteArray()).toHex()
    return "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

private fun honorOaid(profile: MarketProfile): String {
    return profile.honorOaid
}

private fun honorSupportGms(profile: MarketProfile): Int =
    profile.supportGms.toIntOrNull()?.takeIf { it == 0 || it == 1 } ?: 0

/** Mirrors the official client's normalization of ro.build.version.magic to three components. */
internal fun honorSysVersion(profile: MarketProfile): String {
    val raw = profile.magicVersion.substringAfter('_', profile.magicVersion).trim()
    if (raw.isBlank()) return ".0.0"
    val parts = raw.split('.')
    return when (parts.size) {
        1 -> "$raw.0.0"
        2 -> "$raw.0"
        else -> parts.take(3).joinToString(".")
    }
}

/** The official client reads ro.logsystem.usertype and falls back to -1 when it is absent. */
internal fun honorUserType(profile: MarketProfile): String =
    profile.honorUserType.toIntOrNull()?.toString() ?: "-1"

/**
 * Official areaId is the uppercase service country. Sending the vendor code "cn" here
 * is rejected with AreaNotService.
 */
internal fun honorAreaId(profile: MarketProfile): String =
    profile.deliveryCountry.ifBlank { profile.co.ifBlank { "CN" } }.uppercase()

/**
 * Official tInfo.deliveryCountry comes from msc.sys.country / ro.hw.country, typically
 * lowercase "cn". Release strategies compare this case-sensitively with releaseRegions.
 */
internal fun honorDeliveryCountry(profile: MarketProfile): String =
    profile.deliveryCountry.ifBlank { profile.co }.lowercase()

internal fun honorApiBase(serviceCountry: String): String = when (serviceCountry.trim().uppercase()) {
    "CN" -> "https://appmarket-api-drcn.hispace.hihonorcloud.com"
    "RU", "BY" -> "https://appmarket-api-drru.hispace.hihonorcloud.com"
    in HONOR_EUROPE_AMERICA_COUNTRIES -> "https://appmarket-api-dre.hispace.hihonorcloud.com"
    else -> "https://appmarket-api-dra.hispace.hihonorcloud.com"
}

internal fun honorBackgroundApiBase(serviceCountry: String): String = when (serviceCountry.trim().uppercase()) {
    "CN" -> "https://appmarket-bgapi-drcn.hispace.hihonorcloud.com"
    "RU", "BY" -> "https://appmarket-bgapi-drru.hispace.hihonorcloud.com"
    in HONOR_EUROPE_AMERICA_COUNTRIES -> "https://appmarket-bgapi-dre.hispace.hihonorcloud.com"
    else -> "https://appmarket-bgapi-dra.hispace.hihonorcloud.com"
}

private fun honorUserAgent(profile: MarketProfile): String {
    val buildId = profile.buildId.ifBlank { "HONOR${profile.htype}" }
    return "Dalvik/2.1.0 (Linux; U; Android ${profile.osVer}; ${profile.htype} Build/$buildId) " +
            "$HONOR_CLIENT_PACKAGE/${profile.apkVer.ifBlank { HONOR_APK_VERSION }}"
}

private fun honorLanguage(profile: MarketProfile): String =
    profile.language.ifBlank { "zh-CN" }.replace('_', '-')

private fun honorResolution(profile: MarketProfile): String =
    profile.resolution.replace('_', 'x').replace('*', 'x')

private fun randomHex(length: Int): String = buildString(length) {
    repeat(length) { append(HEX[Random.nextInt(HEX.length)]) }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

internal const val HONOR_CLIENT_PACKAGE = "com.hihonor.appmarket"
private const val HONOR_APK_VERSION = "160107301"
private const val HONOR_APK_VERSION_NAME = "16.1.7.301"
internal const val HONOR_MARKET_ID = "oz0002"
private const val HONOR_SEARCH_PREFIX = "/api/market/search/"
private const val HONOR_SEARCH_ACTIVATION_PATH = "/api/market/search/v2/search/activation"
private const val HTTP_PROBE_TIMEOUT_MILLIS = 5_000L
private const val HEX = "0123456789abcdef"

private val HONOR_EUROPE_AMERICA_COUNTRIES = setOf(
    "AL", "AD", "AT", "BE", "BA", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
    "DE", "GR", "HU", "IS", "IE", "IT", "LV", "LI", "LT", "LU", "MT", "MD", "MC",
    "ME", "NL", "MK", "NO", "PL", "PT", "RO", "SM", "RS", "SK", "SI", "ES", "SE",
    "CH", "TR", "UA", "GB", "VA", "US", "CA",
)
