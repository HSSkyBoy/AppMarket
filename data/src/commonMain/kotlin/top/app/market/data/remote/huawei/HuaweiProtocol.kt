package top.app.market.data.remote.huawei

import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.platform.debugLog
import top.app.market.data.platform.gunzip
import top.app.market.data.platform.gzip
import top.app.market.data.platform.sha256
import top.app.market.data.remote.xiaomi.arr
import top.app.market.data.remote.xiaomi.int
import top.app.market.data.remote.xiaomi.parseJsonObject
import top.app.market.data.remote.xiaomi.str
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.profile.MarketProfile
import top.app.market.domain.repository.ProfileRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock

internal class HuaweiProtocol(
    private val client: HttpClient,
    private val profiles: ProfileRepository,
    private val preferences: PreferencesDataSource,
) {
    private val sessionMutex = Mutex()
    private val identityMutex = Mutex()
    private var cachedSession: CachedSession? = null
    private var cachedInstallId: String? = null
    private var retriedEmptyStorefront = false

    suspend fun post(
        method: String,
        version: String = HUAWEI_API_VERSION,
        fields: Map<String, String> = emptyMap(),
    ): JsonObject {
        val profile = profiles.load(AppSource.HUAWEI)
        var session = session(profile)
        var response = rawPost(profile, method, version, session.sign, fields)
        if (response.isSessionFailure()) {
            invalidate(session)
            session = session(profile)
            response = rawPost(profile, method, version, session.sign, fields)
        }
        response.requireSuccess(method)
        return response
    }

    /** 首页分页树（`client.front2`），随会话缓存。 */
    suspend fun tabs(): List<HuaweiTab> = session(profiles.load(AppSource.HUAWEI)).tabs

    private suspend fun session(profile: MarketProfile): HuaweiSession {
        val gateway = huaweiGateway(profile.co)
        val installId = huaweiInstallId()
        val key = SessionKey(
            gateway = gateway,
            serviceCountry = profile.co.trim().uppercase(),
            locale = huaweiLocale(profile),
            instanceId = installId,
            model = profile.model,
            androidVersion = profile.androidVersion,
            buildId = profile.buildId,
        )
        cachedSession?.takeIf { it.key == key && it.value.tabs.isNotEmpty() }?.let { return it.value }
        return sessionMutex.withLock {
            cachedSession?.takeIf { it.key == key && it.value.tabs.isNotEmpty() }?.value ?: run {
                var resolvedKey = key
                var root = rawPost(profile, HUAWEI_FRONT_METHOD, HUAWEI_API_VERSION, "", emptyMap())
                root.requireSuccess(HUAWEI_FRONT_METHOD)
                if (parseHuaweiTabs(root).isEmpty() && !retriedEmptyStorefront) {
                    // A valid-looking AppGallery session can be permanently assigned to a
                    // headless storefront if that anonymous ID was first used with incomplete
                    // client fields. Rotate only the Huawei-local ID once; no app/user data is
                    // cleared and all other source identities remain untouched.
                    retriedEmptyStorefront = true
                    val replacement = replaceHuaweiInstallId()
                    resolvedKey = key.copy(instanceId = replacement)
                    root = rawPost(profile, HUAWEI_FRONT_METHOD, HUAWEI_API_VERSION, "", emptyMap())
                    root.requireSuccess(HUAWEI_FRONT_METHOD)
                }
                val sign = root.str("sign")
                if (sign.isBlank()) throw MarketException("华为应用市场未返回有效会话")
                HuaweiSession(
                    sign = sign,
                    serviceZone = root.str("serviceZone").ifBlank { profile.co.uppercase() },
                    physicalZone = root.str("phyZone").ifBlank { profile.co.uppercase() },
                    tabs = parseHuaweiTabs(root),
                ).also { cachedSession = CachedSession(resolvedKey, it) }
            }
        }
    }

    private suspend fun rawPost(
        profile: MarketProfile,
        method: String,
        version: String,
        sign: String,
        fields: Map<String, String>,
    ): JsonObject {
        val values = commonFields(profile, method, version, sign, huaweiInstallId()) + fields
        val response = client.post(huaweiGateway(profile.co)) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.AcceptEncoding, "identity")
            header(HttpHeaders.ContentType, HUAWEI_GZIP_CONTENT_TYPE)
            header(HttpHeaders.ContentEncoding, "gzip")
            header(HttpHeaders.UserAgent, HUAWEI_USER_AGENT)
            header(HUAWEI_TRAFFIC_RETRY_HEADER, "0")
            setBody(huaweiGzipForm(values))
        }
        val bytes = response.bodyAsBytes()
        val decoded = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            gunzip(bytes)
        } else {
            bytes
        }
        val text = decoded.decodeToString()
        if (!response.status.isSuccess()) {
            debugLog("HuaweiProtocol") {
                "HTTP ${response.status.value} ${response.request.url.encodedPath} method=$method"
            }
            if (response.status.value == 401 || response.status.value == 403) {
                return JsonObject(
                    mapOf(
                        "rtnCode" to kotlinx.serialization.json.JsonPrimitive(-401),
                        "rtnDesc" to kotlinx.serialization.json.JsonPrimitive("session authorization failed"),
                    )
                )
            }
            throw MarketException("华为应用市场返回异常状态 HTTP ${response.status.value}")
        }
        return runCatching { parseJsonObject(text) }.getOrElse {
            debugLog("HuaweiProtocol") { "Invalid response method=$method" }
            throw MarketException(
                "华为应用市场返回了非预期格式（HTTP ${response.status.value}, " +
                        response.headers[HttpHeaders.ContentType].orEmpty() + "）"
            )
        }.also { root ->
            debugLog("HuaweiProtocol") { root.safeResponseSummary(method) }
        }
    }

    private fun commonFields(
        profile: MarketProfile,
        method: String,
        version: String,
        sign: String,
        installId: String,
    ): Map<String, String> = linkedMapOf(
        "method" to method,
        "ver" to version,
        "clientPackage" to HUAWEI_PACKAGE,
        "clientVersionCode" to HUAWEI_VERSION_CODE,
        "packageName" to HUAWEI_PACKAGE,
        "thirdPartyPkg" to HUAWEI_PACKAGE,
        "version" to HUAWEI_VERSION_NAME,
        "versionCode" to HUAWEI_VERSION_CODE,
        "channelId" to "background",
        "cno" to "4010001",
        "code" to "0200",
        "locale" to huaweiLocale(profile),
        "manufacturer" to "HUAWEI",
        "brand" to "HUAWEI",
        "phoneType" to profile.model,
        "firmwareVersion" to profile.androidVersion,
        "osBrand" to "emui",
        "sysBits" to if (profile.cpuArchitecture.contains("64")) "2" else "1",
        "accountZone" to profile.co.uppercase(),
        "lastPhyZone" to profile.co.uppercase(),
        "needServiceZone" to "0",
        "zone" to "1",
        "serviceType" to "0",
        "net" to "1",
        // AppGallery 16.6's regular phone storefront uses runMode=2. runMode=0 can establish a
        // protocol session but is routed to a headless/update-only surface on some Android
        // environments: front2 then returns tabInfo=[] and search omits the normal result list.
        "runMode" to "2",
        "recommendSwitch" to "1",
        "adSwitch" to "0",
        "agCapability" to "1111",
        "deviceSpecParams" to buildJsonObject {
            put("abis", profile.cpuArchitecture)
            put("dpi", profile.densityDpi)
            put("preferLan", profile.la.ifBlank { "zh" })
        }.toString(),
        "deviceId" to huaweiAnonymousDeviceId(installId),
        "deviceIdType" to "9",
        "deviceIdRealType" to "4",
        "oaidTrack" to "-2",
        "ts" to Clock.System.now().toEpochMilliseconds().toString(),
    ).apply {
        if (sign.isNotBlank()) put("sign", sign)
    }

    private fun invalidate(value: HuaweiSession) {
        if (cachedSession?.value == value) cachedSession = null
    }

    private suspend fun huaweiInstallId(): String {
        cachedInstallId?.let { return it }
        return identityMutex.withLock {
            cachedInstallId ?: preferences.read(HuaweiIdentityPreferenceKeys.InstallId)
                ?.takeIf(::isValidHuaweiInstallId)
                ?.also { cachedInstallId = it }
            ?: generateHuaweiInstallId().also { value ->
                preferences.put(HuaweiIdentityPreferenceKeys.InstallId, value)
                cachedInstallId = value
            }
        }
    }

    private suspend fun replaceHuaweiInstallId(): String = identityMutex.withLock {
        generateHuaweiInstallId().also { value ->
            preferences.put(HuaweiIdentityPreferenceKeys.InstallId, value)
            cachedInstallId = value
        }
    }

    private fun JsonObject.isSessionFailure(): Boolean {
        val code = int("rtnCode", str("rtnCode").toIntOrNull() ?: 0)
        val description = str("rtnDesc")
        return code == -401 || (code != 0 && description.contains("sign", ignoreCase = true))
    }

    private fun JsonObject.requireSuccess(method: String) {
        val code = int("rtnCode", str("rtnCode").toIntOrNull() ?: 0)
        if (code == 0) return
        val description = str("rtnDesc").ifBlank { "错误码 $code" }
        throw MarketException("华为应用市场请求失败（$method）：$description")
    }

    private data class SessionKey(
        val gateway: String,
        val serviceCountry: String,
        val locale: String,
        val instanceId: String,
        val model: String,
        val androidVersion: String,
        val buildId: String,
    )

    private data class CachedSession(val key: SessionKey, val value: HuaweiSession)
}

internal fun generateHuaweiInstallId(): String {
    fun part(length: Int): String = buildString(length) {
        repeat(length) { append(HUAWEI_ID_ALPHABET[Random.nextInt(HUAWEI_ID_ALPHABET.length)]) }
    }
    return "${part(8)}-${part(4)}-${part(4)}-${part(4)}-${part(12)}"
}

internal fun isValidHuaweiInstallId(value: String): Boolean =
    value.length == 36 && value.count { it == '-' } == 4 &&
            value.filterNot { it == '-' }.all { it in HUAWEI_ID_ALPHABET }

internal fun huaweiAnonymousDeviceId(installId: String): String =
    sha256(installId.encodeToByteArray()).toHuaweiHex()

internal fun huaweiGzipForm(values: Map<String, String>): ByteArray {
    val parameters = Parameters.build {
        values.toSortedMap().forEach { (name, value) -> append(name, value) }
    }
    return gzip(parameters.formUrlEncode().encodeToByteArray())
}

private fun ByteArray.toHuaweiHex(): String = joinToString(separator = "") { byte ->
    byte.toUByte().toString(radix = 16).padStart(2, '0')
}

private fun JsonObject.safeResponseSummary(method: String): String {
    fun arraySize(name: String): Int = arr(name)?.size ?: -1
    val counts = listOf("tabInfo", "results", "detailInfo", "layoutData", "list", "notRcmList")
        .mapNotNull { name -> arraySize(name).takeIf { it >= 0 }?.let { "$name=$it" } }
        .joinToString()
    val tabs = if (method == HUAWEI_FRONT_METHOD) {
        parseHuaweiTabs(this).joinToString(separator = ";", limit = 8) { tab ->
            val children = tab.children.joinToString(separator = "/", limit = 8) { it.name.ifBlank { it.englishName } }
            tab.name.ifBlank { tab.englishName } + if (children.isBlank()) "" else "[$children]"
        }
    } else {
        ""
    }
    val resultPackages = when (method) {
        "client.getTabDetail" -> parseHuaweiSearchRecords(this)
            .map(HuaweiAppRecord::packageName)
            .distinct()
            .take(6)
            .joinToString()

        "client.batchAppDetail" -> arr("appList")?.mapNotNull { value ->
            (value as? JsonObject)?.let { it.str("package").ifBlank { it.str("packageName") } }
                ?.takeIf(String::isNotBlank)
        }?.take(6)?.joinToString().orEmpty()

        else -> ""
    }
    val layouts = if (method == "client.getTabDetail") {
        arr("layoutData")?.mapNotNull { value ->
            (value as? JsonObject)?.str("layoutName")?.takeIf(String::isNotBlank)
        }?.distinct()?.take(12)?.joinToString().orEmpty()
    } else {
        ""
    }
    val updatePolicy = if (method == "client.diffUpgrade2" || method == "client.manualDiffUpgrade") {
        arr("notRcmList")?.mapNotNull { value ->
            (value as? JsonObject)?.let { item ->
                val pkg = item.str("package").ifBlank { item.str("packageName") }
                pkg.takeIf(String::isNotBlank)?.let {
                    "$it:${item.int("nonAdaptType")}:${item.str("notRcmReason").take(40)}"
                }
            }
        }?.take(12)?.joinToString().orEmpty()
    } else {
        ""
    }
    val detailStats = if (method == "client.appDetailById") {
        arr("detailInfo")?.mapNotNull { value ->
            (value as? JsonObject)?.let { item ->
                val pkg = item.str("package").ifBlank { item.str("packageName") }
                val stats = item.huaweiCommentStats()
                pkg.takeIf(String::isNotBlank)?.let { "$it:$stats" }
            }
        }?.take(6)?.joinToString().orEmpty()
    } else {
        ""
    }
    return buildString {
        append("method=").append(method)
        append(" code=").append(int("rtnCode", str("rtnCode").toIntOrNull() ?: 0))
        append(" keys=").append(keys.sorted().joinToString())
        if (counts.isNotBlank()) append(" counts=").append(counts)
        if (tabs.isNotBlank()) append(" tabs=").append(tabs)
        if (resultPackages.isNotBlank()) append(" packages=").append(resultPackages)
        if (layouts.isNotBlank()) append(" layouts=").append(layouts)
        if (updatePolicy.isNotBlank()) append(" notRcm=").append(updatePolicy)
        if (detailStats.isNotBlank()) append(" detailStats=").append(detailStats)
    }
}

private fun JsonObject.huaweiCommentStats(): String {
    val values = mutableListOf<String>()
    fun visit(value: kotlinx.serialization.json.JsonElement, depth: Int) {
        if (depth > 3) return
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                if (key.contains("comment", true) || key.contains("rate", true) ||
                    key.contains("score", true) || key.contains("star", true)
                ) {
                    (child as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let {
                        values += "$key=$it"
                    }
                }
                visit(child, depth + 1)
            }

            is JsonArray -> value.take(3).forEach { visit(it, depth + 1) }
            else -> Unit
        }
    }
    visit(this, 0)
    return values.distinct().take(16).joinToString("|")
}

internal fun parseHuaweiTabs(root: JsonObject): List<HuaweiTab> =
    root.arr("tabInfo").toHuaweiTabs()

private fun JsonArray?.toHuaweiTabs(): List<HuaweiTab> = this
    ?.mapNotNull { value ->
        val item = value as? JsonObject ?: return@mapNotNull null
        val id = item.str("tabId").ifBlank { item.str("realTabId") }
        if (id.isBlank()) return@mapNotNull null
        HuaweiTab(
            id = id,
            name = item.str("tabName"),
            englishName = item.str("tabEnName"),
            children = item.arr("tabInfo").toHuaweiTabs(),
        )
    }
    .orEmpty()

internal fun huaweiGateway(serviceCountry: String): String = when (serviceCountry.trim().uppercase()) {
    "CN" -> "https://store-drcn.hispace.dbankcloud.com/hwmarket/api/clientApi"
    "RU", "BY" -> "https://store-drru.hispace.hicloud.com/hwmarket/api/clientApi"
    in HUAWEI_EUROPE_AMERICA_COUNTRIES -> "https://store3.hispace.hicloud.com/hwmarket/api/clientApi"
    else -> "https://store2.hispace.hicloud.com/hwmarket/api/clientApi"
}

private fun huaweiLocale(profile: MarketProfile): String {
    val language = profile.la.ifBlank { "zh" }.replace('-', '_')
    if ('_' in language) return language
    return "${language}_${profile.lo.ifBlank { profile.co.ifBlank { "CN" } }.uppercase()}"
}

private const val HUAWEI_FRONT_METHOD = "client.front2"
private const val HUAWEI_API_VERSION = "1.1"
private const val HUAWEI_PACKAGE = "com.huawei.appmarket"
private const val HUAWEI_VERSION_CODE = "160601300"
private const val HUAWEI_VERSION_NAME = "16.6.1"
private const val HUAWEI_ID_ALPHABET = "0123456789abcdef"
private const val HUAWEI_GZIP_CONTENT_TYPE = "application/x-gzip"
private const val HUAWEI_USER_AGENT = "Android/1.0"
private const val HUAWEI_TRAFFIC_RETRY_HEADER = "X-Traffic-Retry"

private val HUAWEI_EUROPE_AMERICA_COUNTRIES = setOf(
    "AL", "AD", "AT", "BE", "BA", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
    "DE", "GR", "HU", "IS", "IE", "IT", "LV", "LI", "LT", "LU", "MT", "MD", "MC",
    "ME", "NL", "MK", "NO", "PL", "PT", "RO", "SM", "RS", "SK", "SI", "ES", "SE",
    "CH", "TR", "UA", "GB", "VA", "US", "CA",
)
