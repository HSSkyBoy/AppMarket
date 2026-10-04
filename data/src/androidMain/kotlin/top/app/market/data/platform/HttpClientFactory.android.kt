package top.app.market.data.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** Android uses the platform TLS stack required by vendor risk control. */
fun createAndroidHttpClient(): HttpClient = HttpClient(OkHttp) {
    installSharedHttp()
}
