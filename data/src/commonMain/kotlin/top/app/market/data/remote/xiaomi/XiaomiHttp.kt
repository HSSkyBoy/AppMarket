package top.app.market.data.remote.xiaomi

import top.app.market.data.platform.debugLog
import top.app.market.domain.exception.MarketException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

internal class XiaomiHttpClient(
    private val client: HttpClient,
    private val xiaomiClient: XiaomiClient,
) {
    suspend fun get(url: String, cookie: String): String =
        client.get(url) { xiaomiHeaders(xiaomiClient, cookie) }.requireSuccessBody()

    suspend fun postForm(
        url: String,
        body: String,
        cookie: String,
        userAgent: String = xiaomiClient.userAgent,
    ): String = client.post(url) {
        xiaomiUpdateHeaders(cookie, userAgent)
        contentType(ContentType.Application.FormUrlEncoded)
        setBody(body)
    }.requireSuccessBody()

    // 非 2xx（风控/限流/5xx）必须显式失败，否则错误体会被 lenient 解析折叠成空数据污染缓存
    private suspend fun HttpResponse.requireSuccessBody(): String {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            debugLog("XiaomiHttp") { "HTTP ${status.value} ${request.url.encodedPath}: ${text.take(200)}" }
            throw MarketException("服务器返回异常状态 HTTP ${status.value}")
        }
        return text
    }
}
