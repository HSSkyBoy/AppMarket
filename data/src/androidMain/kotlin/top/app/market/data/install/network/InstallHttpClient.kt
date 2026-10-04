package top.app.market.data.install.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

internal class InstallHttpClient {
    val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 20_000L
            socketTimeoutMillis = 60_000L
        }
        install(HttpRequestRetry) {
            retryOnExceptionOrServerErrors(maxRetries = 3)
            exponentialDelay(base = 2.0, maxDelayMs = 8_000)
        }
    }
}
