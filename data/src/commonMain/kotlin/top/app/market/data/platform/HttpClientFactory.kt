package top.app.market.data.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

/** API 与图片加载共用的唯一客户端。Android 端另有使用平台 TLS 的 OkHttp 版本。 */
fun createHttpClient(): HttpClient = HttpClient(CIO) {
    installSharedHttp()
}

internal fun HttpClientConfig<*>.installSharedHttp() {
    install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    // 地铁/电梯里的一次瞬断不该把整次检查更新或搜索打成失败态；这些请求语义上都是幂等的
    install(HttpRequestRetry) {
        retryOnExceptionOrServerErrors(maxRetries = 2)
        exponentialDelay(base = 2.0, maxDelayMs = 4_000)
    }
}
