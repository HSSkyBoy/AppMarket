package top.app.market.data.remote.xiaomi

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Form-encodes a single value (space -> `%20`). */
internal fun enc(s: String): String = s.encodeURLParameter()

/**
 * Builds a `k=v&k=v` query string. The signer hashes the *decoded* URL (and signs the raw field map
 * for POSTs), so the exact encoding scheme does not affect the signature — only that encode/decode
 * round-trip, which Ktor's pair does.
 */
internal fun query(params: Map<String, String>): String =
    params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

/** Decodes a percent-encoded URL (`+` -> space, `%XX` -> byte); used by the signer before hashing. */
internal fun urlDecode(url: String): String = url.decodeURLQueryComponent(plusIsSpace = true)

/**
 * Resolves a package/patch [url] against [host], mirroring the official market: an already-absolute
 * http(s) URL is used verbatim, otherwise host and path are joined by a single '/'. Prepending host
 * unconditionally would double-prefix absolute URLs into `.../download/https://...`.
 */
internal fun resolvedUrl(host: String, url: String): String {
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) return url
    if (host.isEmpty()) return url
    if (url.isEmpty()) return host
    val base = if (host.last() == '/') host.dropLast(1) else host
    val path = if (url.first() == '/') url.drop(1) else url
    return "$base/$path"
}

/** Current epoch millis (multiplatform replacement for `System.currentTimeMillis()`). */
@OptIn(ExperimentalTime::class)
internal fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
