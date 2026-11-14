package top.app.market.data.remote.googleplay

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import top.app.market.domain.exception.MarketException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

internal data class GooglePlayConfig(
    val dispenserUrl: String = "https://auroraoss.com/api/auth",
    val language: String = "zh-CN",
)

internal class GooglePlaySession(
    val authToken: String,
    val gsfId: String,
    val dfeCookie: String,
    val deviceConfigToken: String,
    val checkInConsistencyToken: String,
)

/** 向令牌发放服务换取匿名会话，内存缓存；令牌失效时由调用方强制刷新。 */
internal class GooglePlaySessionProvider(
    private val client: HttpClient,
    private val config: GooglePlayConfig,
) {
    private val mutex = Mutex()
    private var cached: GooglePlaySession? = null
    private var cachedAt = Clock.System.now()

    suspend fun get(forceRefresh: Boolean = false): GooglePlaySession = mutex.withLock {
        val current = cached
        if (!forceRefresh && current != null && Clock.System.now() - cachedAt < SessionTtl) return current
        fetch().also {
            cached = it
            cachedAt = Clock.System.now()
        }
    }

    private suspend fun fetch(): GooglePlaySession {
        val body = buildJsonObject {
            GooglePlayDevice.properties.forEach { (key, value) -> put(key, value) }
        }.toString()
        val response = client.post(config.dispenserUrl) {
            header(HttpHeaders.UserAgent, DispenserUserAgent)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw MarketException("Google Play 令牌发放服务不可用（HTTP ${response.status.value}）")
        }
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw MarketException("Google Play 令牌发放服务返回了无法解析的内容")
        val token = json.string("authToken")
        val gsfId = json.string("gsfId")
        if (token.isBlank() || gsfId.isBlank()) {
            throw MarketException("Google Play 令牌发放服务未签发会话")
        }
        return GooglePlaySession(
            authToken = token,
            gsfId = gsfId,
            dfeCookie = json.string("dfeCookie"),
            deviceConfigToken = json.string("deviceConfigToken"),
            checkInConsistencyToken = json.string("deviceCheckInConsistencyToken"),
        )
    }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        val SessionTtl = 6.hours
        const val DispenserUserAgent = "AppMarket"
    }
}

