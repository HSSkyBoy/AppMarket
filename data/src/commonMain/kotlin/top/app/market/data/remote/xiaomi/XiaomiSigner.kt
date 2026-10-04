package top.app.market.data.remote.xiaomi

import top.app.market.data.platform.hmac
import top.app.market.data.platform.sha1
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

object XiaomiSigner {
    private const val SALT = "good luck!"
    private const val TABLE = "leDTKhmg4MafVFp73x6djvLiHn2G9XPruARBwS0q1OzNJt8WobZsQcYyEICk5U-_"
    private val signedKeys = setOf(
        "activedTimeInterval", "ad", "adExchangeFlag", "adFlag", "apkChannel", "appId",
        "bottomTab", "carrier", "clientId", "co", "count", "cpuArchitecture", "device",
        "deviceType", "digestParams", "downloadingAppInfo", "excludedAppIds", "ext_apkChannel",
        "ext_marketType", "flag", "folderName", "get", "gpId", "h5", "id", "imei",
        "installDay", "installError", "instance_id", "international", "keyword", "la",
        "launchDay", "lo", "marketVersion", "miuiBigVersionCode", "miuiBigVersionName",
        "model", "_n", "needLruCache", "network", "newUser", "oldApkHash", "oldVersionCode",
        "os", "packageName", "packageNameList", "page", "pageConfigVersion", "pageRef",
        "pageSize", "pageTag", "params", "pos", "posChain", "previousAppIds",
        "proxyTimeout", "query", "reason", "recentInstallCompleteAppInfo", "ref",
        "refPosition", "refresh", "refs", "resolution", "ro", "sco", "sdk", "searchScope",
        "shouldNativeInterceptRequest", "sid", "sla", "sourcePackage", "stamp",
        "targetVersionCode", "type", "update", "versionCode", "webResVersion", "zoneSuffix",
        "aiQuery"
    )

    fun signedUrl(baseUrl: String): String {
        val nonce = "${epochMillis()}_${Random.nextInt(1000)}"
        val signature = signature(baseUrl, nonce)
        val sep = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl${sep}_n=${enc(nonce)}&_s=${enc(signature)}&_v=1"
    }

    fun signFormForUrl(url: String, fields: MutableMap<String, String>) {
        val nonce = "${epochMillis()}_${Random.nextInt(1000)}"
        val signatureUrl = if (fields.isEmpty()) {
            url
        } else {
            val sep = if (url.contains("?")) "&" else "?"
            "$url$sep${query(fields)}"
        }
        fields["_n"] = nonce
        fields["_s"] = signature(signatureUrl, nonce)
        fields["_v"] = "1"
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun signedPostUrl(url: String, fields: Map<String, String>, security: String): String {
        if (security.isBlank()) return url
        val path = url.substringAfter("://").substringAfter('/').substringBefore('?')
        val parts = buildList {
            add("POST")
            add("/$path")
            fields.forEach { (key, value) ->
                if (value.isNotEmpty()) add("$key=$value")
            }
            add(security)
        }
        val signature = Base64.encode(sha1(parts.joinToString("&").encodeToByteArray()))
        val encodedOnce = enc(signature)
        val encodedTwice = enc(encodedOnce)
        val sep = if (url.contains("?")) "&" else "?"
        return "$url${sep}signature=$encodedTwice"
    }

    private fun signature(url: String, nonce: String): String {
        val timestamp = nonce.substringBefore('_').toLong()
        val arranged = arrange(urlDecode(url), nonce)
        val algorithm = when ((timestamp % 4).toInt()) {
            0 -> "HmacMD5"
            1 -> "HmacSHA256"
            2 -> "HmacSHA1"
            else -> "HmacSHA384"
        }
        return xiaomiBase64(hmac(algorithm, (SALT + nonce).encodeToByteArray(), arranged.encodeToByteArray()))
    }

    private fun arrange(decodedUrl: String, nonce: String): String {
        val queryStart = decodedUrl.indexOf('?')
        val urlPart = if (queryStart >= 0) decodedUrl.substring(0, queryStart) else decodedUrl
        val query = if (queryStart >= 0 && queryStart + 1 < decodedUrl.length) decodedUrl.substring(queryStart + 1) else ""
        var slashCount = 0
        var reversingHost = true
        val builder = StringBuilder()
        for (ch in urlPart) {
            if (ch == '/' && reversingHost) {
                slashCount++
                if (slashCount == 3) {
                    builder.append('\n').append(ch)
                    reversingHost = false
                } else {
                    builder.insert(0, ch)
                }
            } else if (reversingHost) {
                builder.insert(0, ch)
            } else {
                builder.append(ch)
            }
        }
        builder.append('\n')

        val params = query.split('&')
            .filter { it.isNotEmpty() && !it.startsWith("_n=") && !it.startsWith("_s=") && !it.startsWith("_v=") }
            .toMutableList()
        params.add("_n=$nonce")

        val plist = StringBuilder()
        params.forEachIndexed { index, p ->
            val key = p.substringBefore('=')
            if (signedKeys.contains(key)) {
                plist.append(key)
                if (index != params.lastIndex) plist.append(';')
            }
        }
        params.add("_p=$plist")
        params.sortWith { a, b -> a.compareTo(b, ignoreCase = true) }

        for ((index, param) in params.withIndex()) {
            val key = param.substringBefore('=')
            val signed = if (param.contains('=')) signedKeys.contains(key) else true
            if (!signed) {
                if (index == params.lastIndex && builder.endsWith("=")) builder.deleteCharAt(builder.lastIndex)
                continue
            }
            // 每个 '=' 变 '&' 并反转其后的值段；updateinfo 单字段可达数十 KB，不能逐字符 insert
            val segments = param.split('=')
            builder.append(segments.first())
            for (i in 1 until segments.size) {
                builder.append('&').append(segments[i].reversedChars())
            }
            if (index != params.lastIndex) builder.append('=')
        }
        val lf = builder.indexOf("\n")
        return builder.substring(lf + 1)
    }

    /** 逐 char 反转（代理对一并拆开），官方客户端如此；[reversed] 会保留代理对，emoji 会签错。 */
    private fun String.reversedChars(): String {
        val out = CharArray(length)
        for (index in indices) out[length - 1 - index] = this[index]
        return out.concatToString()
    }

    private fun xiaomiBase64(bytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i + 2 < bytes.size) {
            val b1 = bytes[i].toInt() and 0xff
            val b2 = bytes[i + 1].toInt() and 0xff
            val b3 = bytes[i + 2].toInt() and 0xff
            out.append(TABLE[(b1 shr 2) and 0x3f])
            out.append(TABLE[((b1 shl 4) or (b2 shr 4)) and 0x3f])
            out.append(TABLE[((b2 shl 2) or (b3 shr 6)) and 0x3f])
            out.append(TABLE[b3 and 0x3f])
            i += 3
        }
        val remaining = bytes.size - i
        if (remaining == 1) {
            val b1 = bytes[i].toInt() and 0xff
            out.append(TABLE[(b1 shr 2) and 0x3f])
            out.append(TABLE[(b1 shl 4) and 0x3f])
        } else if (remaining == 2) {
            val b1 = bytes[i].toInt() and 0xff
            val b2 = bytes[i + 1].toInt() and 0xff
            out.append(TABLE[(b1 shr 2) and 0x3f])
            out.append(TABLE[((b1 shl 4) or (b2 shr 4)) and 0x3f])
            out.append(TABLE[(b2 shl 2) and 0x3f])
        }
        return out.toString()
    }
}
