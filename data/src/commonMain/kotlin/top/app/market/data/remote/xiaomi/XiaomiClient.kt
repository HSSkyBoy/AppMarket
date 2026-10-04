package top.app.market.data.remote.xiaomi

import top.app.market.domain.model.profile.MarketProfile
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import kotlin.concurrent.Volatile

/**
 * Identity of the Xiaomi Market client we impersonate. Single source of truth for the request
 * headers shared across the market API and the APK downloader.
 */
internal class XiaomiClient {
    // UA 的机型/Build 必须与请求体 profile 保持一致；设备资料页保存的 profile 是唯一来源。
    // 后台线程写、请求线程读，volatile 保证切换 profile 后立刻可见。
    @Volatile
    var userAgent = runtimeUserAgent()
        private set

    fun updateUserAgent(profile: MarketProfile) {
        userAgent = buildUserAgent(profile.androidVersion, profile.model, profile.buildId)
    }

    fun downloadHeaders(ref: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "ref" to ref,
    )

    private fun runtimeUserAgent(): String =
        buildUserAgent(androidVersion = "16", model = "25128PNA1C", buildId = "BP2A.250605.031.A3")

    private fun buildUserAgent(androidVersion: String, model: String, buildId: String): String =
        "Dalvik/2.1.0 (Linux; U; Android $androidVersion; $model Build/$buildId)"
}

/** Xiaomi Market's /apm client sends its system UA and, only when logged in, its account cookie. */
internal fun HttpRequestBuilder.xiaomiHeaders(client: XiaomiClient, cookie: String = "") {
    header("User-Agent", client.userAgent)
    if (cookie.isNotBlank()) header("Cookie", cookie)
}

/** Official updateinfo/v2 requests only carry UA/Cookie/Content-Type; no x-pkg/version headers. */
internal fun HttpRequestBuilder.xiaomiUpdateHeaders(cookie: String = "", userAgent: String) {
    header("User-Agent", userAgent)
    if (cookie.isNotBlank()) header("Cookie", cookie)
}

/** Protocol baseline taken from the Xiaomi Market APK used to mirror the current request contract. */
internal object XiaomiProtocol {
    const val VERSION_NAME = "4.121.s.00"
    const val VERSION_CODE = "40007460"
}
