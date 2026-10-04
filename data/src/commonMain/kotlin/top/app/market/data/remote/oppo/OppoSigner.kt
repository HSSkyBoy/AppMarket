package top.app.market.data.remote.oppo

import top.app.market.data.platform.md5
import top.app.market.data.platform.sha1
import top.app.market.data.platform.sha256
import top.app.market.data.remote.xiaomi.epochMillis
import top.app.market.domain.model.profile.DefaultOppoRequestContext
import top.app.market.domain.model.profile.MarketProfile
import top.app.market.domain.model.profile.OppoRequestContext
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent

/**
 * Header signer for the HeyTap store protocol.
 *
 * The native OcsTool shipped by the official client uses two fixed key fragments. Both the `sign`
 * and `sg` formulas are reproduced here, without copying an account token from a captured request.
 */
internal object OppoSigner {
    const val Accept = "application/x2-protostuff; charset=UTF-8"
    private const val Oak = "cdb09c43063ea6bb"
    private const val SecondKey = "09bdc58acb383220be08f4fe8a43775179"
    private const val ObscureCode =
        "STORENEWMIICeAIBADANBgkqhkiG9w0BAQEFAASCAmIwggJeAgEAAoGBANYFY/UJGSzhIhpx6YM5KJ9yRHc7YeURxzb9tDvJvMfENHlnP3DtVkOIjERbpsSd76fjtZnMWY60TpGLGyrNkvuV40L15JQhHAo9yURpPQoI0eg3SLFmTEI/MUiPRCwfwYf2deqKKlsmMSysYYHX9JiGzQuWiYZaawxprSuiqDGvAgMBAAECgYEAtQ0QV00gGABISljNMy5aeDBBTSBWG2OjxJhxLRbndZM81OsMFysgC7dq+bUS6ke1YrDWgsoFhRxxTtx/2gDYciGp/c/h0Td5pGw7T9W6zo2xWI5oh1WyTnn0Xj17O9CmOk4fFDpJ6bapL+fyDy7gkEUChJ9+p66WSAlsfUhJ2TECQQD5sFWMGE2IiEuz4fIPaDrNSTHeFQQr/ZpZ7VzB2tcG7GyZRx5YORbZmX1jR7l3H4F98MgqCGs88w6FKnCpxDK3AkEA225CphAcfyiH0ShlZxEXBgIYt3V8nQuc/g2KJtiV6eeFkxmOMHbVTPGkARvt5VoPYEjwPTg43oqTDJVtlWagyQJBAOvEeJLno9aHNExvznyD4/pR4hec6qqLNgMyIYMfHCl6d3UodVvC1HO1/nMPl+4GvuRnxuoBtxj/PTe7AlUbYPMCQQDOkf4sVv58tqslO+I6JNyHy3F5RCELtuMUR6rG5x46FLqqwGQbO8ORq+m5IZHTV/Uhr4h6GXNwDQRh1EpVW0gBAkAp/v3tPI1riz6UuG0I6uf5er26yl5evPyPrjrD299L4Qy/1EIunayC7JYcSGlR01+EDYYgwUkec+QgrRC/NstV"

    data class Headers(
        val values: Map<String, String>,
        val timestamp: Long,
    )

    fun headers(
        method: String,
        url: String,
        profile: MarketProfile,
        requestContext: OppoRequestContext? = null,
    ): Headers {
        val timestamp = epochMillis()
        val parsed = Url(url)
        // java.net.URI.getPath()/getQuery(), used by the official client, return decoded values.
        // Signing Ktor's encoded query works for ASCII requests but fails as soon as a keyword or
        // a JSON tracking parameter contains percent escapes.
        val path = parsed.encodedPath.decodeURLQueryComponent(plusIsSpace = false)
        val query = parsed.encodedQuery.decodeURLQueryComponent(plusIsSpace = false)
        val openId = openId(profile)
        val material = profileMaterial(profile)
        val signInput = material.ocs + timestamp + openId + path + query
        val sign = sign(signInput)
        val encodedPathQuery = formUrlEncode(path + query)
        // OcsTool.Ϳ passes the already calculated `sign` as d()'s second argument. d() then
        // selects one of five fixed tables using an MD5 checksum and chooses MD5/SHA1 for the
        // final digest. This is the exact native flow; no captured request token is involved.
        val sgInput = (encodedPathQuery + Accept + method.lowercase() + openId + timestamp).lowercase()
        val sg = secondarySign(sgInput, sign)
        val values = linkedMapOf(
            "Accept" to Accept,
            "Content-Type" to Accept,
            "User-Agent" to material.userAgent,
            "oak" to Oak,
            "id" to openId,
            "ocs" to material.ocs,
            "appid" to "${material.brand}#20171#${profile.co}",
            "appversion" to CLIENT_VERSION_NAME,
            "pkg-ver" to HEYTAP_PACKAGE_VERSION,
            "romver" to HEYTAP_PACKAGE_VERSION,
            "t" to timestamp.toString(),
            "sign" to sign,
            "sg" to sg,
        )
        val context = requestContext ?: DefaultOppoRequestContext
        values["user-region"] = context.userRegion
        values["system-locale"] = context.systemLocale
        values["supported-locales"] = context.supportedLocales
        values["locale"] = context.locale
        return Headers(values = values, timestamp = timestamp)
    }

    private data class ProfileMaterial(
        val brand: String,
        val userAgent: String,
        val ocs: String,
    )

    /** Mirrors t.ԩ() in the official client. Values are URL-encoded before being sent as headers. */
    private fun profileMaterial(profile: MarketProfile): ProfileMaterial {
        val brand = brand(profile)
        val model = profile.model.ifBlank { "OPPO" }
        val osIntVersion = profile.sdk.ifBlank { profile.androidVersion.ifBlank { "0" } }
        val osName = profile.androidVersion.ifBlank { profile.os.ifBlank { "Android" } }
        val mobileRomVersion = profile.osBigVersionName
            .takeIf { it.isNotBlank() }
            ?: profile.osV2.takeIf { it.startsWith("V", ignoreCase = true) }
            ?: profile.os.takeIf { it.startsWith("V", ignoreCase = true) }
            ?: osName
        val firmwareVersion = profile.os.takeIf { it.isNotBlank() }
            ?: profile.osV2.takeIf { it.isNotBlank() }
            ?: mobileRomVersion
        val base = listOf(brand, model, osIntVersion, osName, mobileRomVersion, STAT_APP_CODE)
            .joinToString("/")
        val userAgent = formUrlEncode("$base/$CHANNEL/$CLIENT_VERSION_CODE/$CLIENT_VERSION_NAME")
        val romName = "${model}_${firmwareVersion}(CN01)"
        val ocs = formUrlEncode("$base/$romName/$CLIENT_VERSION_CODE")
        return ProfileMaterial(brand, userAgent, ocs)
    }

    private fun brand(profile: MarketProfile): String = when {
        profile.model.startsWith("RMX", ignoreCase = true) -> "realme"
        profile.model.startsWith("NE", ignoreCase = true) ||
                profile.model.startsWith("CPH", ignoreCase = true) -> "oneplus"

        else -> "oppo"
    }

    /**
     * The official client sends OpenIdHelper.buildOpenId(): /GUID/OUID/DUID. AppMarket cannot
     * access OPPO's privileged StdID service, so it derives three stable app-scoped identifiers
     * from the profile's persisted instance ID instead of reusing a captured or fixed identifier.
     */
    internal fun openId(profile: MarketProfile): String {
        val seed = profile.instanceId.trim().ifBlank {
            listOf(
                profile.model,
                profile.device,
                profile.buildId,
                profile.os,
                profile.androidVersion,
                profile.sdk,
                profile.resolution,
            ).joinToString("\u0000")
        }

        fun derive(kind: String): String = hex(
            sha256("AppMarket/oppo-open-id/v1/$kind\u0000$seed".encodeToByteArray())
        ).lowercase()
        return "/${derive("guid")}/${derive("ouid")}/${derive("duid")}"
    }

    internal fun sign(input: String): String = md5Hex(
        key2Bytes() + input.encodeToByteArray() +
                (input.length + 48).toString().encodeToByteArray() + ObscureCode.encodeToByteArray()
    )

    internal fun secondarySign(input: String, sign: String): String {
        val first = input + Oak + sign
        val firstWithLength = first + utf8Length(first).toString()
        val firstDigest = md5(firstWithLength.encodeToByteArray())
        val checksum = firstDigest.sumOf { kotlin.math.abs(it.toInt()) % 10 }
        val digit = checksum % 10
        val index = digit % SECONDARY_KEYS.size
        val selectedKey = SECONDARY_KEYS[index]
        val selectedSalt = SECONDARY_SALTS[index]
        val second = input + selectedKey + selectedSalt
        val secondWithLength = second + utf8Length(second).toString()
        val digest = if (digit < 5) {
            md5(secondWithLength.encodeToByteArray())
        } else {
            sha1(secondWithLength.encodeToByteArray())
        }
        return hex(digest)
    }

    /** java.net.URLEncoder.encode(value, "UTF-8") as used by OcsTool. */
    private fun formUrlEncode(value: String): String {
        val out = StringBuilder(value.length)
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 0xff
            when {
                code == 0x20 -> out.append('+')
                code in 'a'.code..'z'.code ||
                        code in 'A'.code..'Z'.code ||
                        code in '0'.code..'9'.code ||
                        code == '-'.code || code == '.'.code ||
                        code == '*'.code || code == '_'.code -> out.append(code.toChar())

                else -> out.append('%')
                    .append(HEX[code ushr 4])
                    .append(HEX[code and 0x0f])
            }
        }
        return out.toString()
    }

    private fun utf8Length(value: String): Int = value.encodeToByteArray().size

    private fun key2Bytes(): ByteArray =
        (Oak + SecondKey.substring(18, 34) + SecondKey.substring(2, 18)).encodeToByteArray()

    private fun md5Hex(bytes: ByteArray): String = hex(md5(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private val SECONDARY_KEYS = arrayOf(
        "cab2f5d94d4eea71",
        "3e8d4f1ce5aa6e09",
        "c5ca81b0391663db",
        "517e95007dd031cb",
        "f8730121dba1cf23",
    )

    private val SECONDARY_SALTS = arrayOf(
        "d9275c219e852eb5e60cb5ceccd523b5",
        "6bf7d491e5ab9da2931f91eb4c9a1640",
        "0ca3e6b70c4e0fc4cf53ead33d158e83",
        "464c6045b59a38069a3e585d8bfe3b90",
        "f1184b1998cccddd10aa5b8dd039ef8f",
    )

    private const val HEX = "0123456789ABCDEF"

    private const val CHANNEL = "4101"
    private const val CLIENT_VERSION_CODE = "122280"
    private const val CLIENT_VERSION_NAME = "12.22.80"
    private const val HEYTAP_PACKAGE_VERSION = "38"
    private const val STAT_APP_CODE = "210"
}
