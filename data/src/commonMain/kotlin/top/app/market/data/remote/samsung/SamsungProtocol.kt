package top.app.market.data.remote.samsung

import top.app.market.data.platform.sha256
import top.app.market.domain.model.profile.MarketProfile
import kotlin.time.Clock

internal object SamsungProtocol {
    const val VERSION_NAME = "4.6.08.11"
    const val VERSION_CODE = "460811300"

    data class Identity(val installationId: String, val stduk: String, val extuk: String)

    fun identity(profile: MarketProfile): Identity {
        val installationId = profile.instanceId.ifBlank { "appmarket-anonymous" }
        return Identity(
            installationId = installationId,
            stduk = hex(sha256((installationId + "kjk3syk6wkj5").encodeToByteArray())),
            extuk = installationId,
        )
    }

    fun requestBody(
        profile: MarketProfile,
        region: SamsungRegionContext,
        id: String,
        name: String,
        params: Map<String, String>,
    ): String {
        val identity = identity(profile)
        val now = Clock.System.now()
        val systemId = now.toEpochMilliseconds().toString()
        val locale = region.lang
        val abi64 = profile.cpuArchitecture.split(',').joinToString(":") { it.trim() }
        return buildString(1024) {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>")
            append("<SamsungProtocol")
            attribute("networkType", "0")
            attribute("version2", VERSION_NAME)
            attribute("lang", locale)
            attribute("openApiVersion", profile.sdk.ifBlank { "32" })
            attribute("deviceModel", profile.model.ifBlank { "SM-S9280" })
            attribute("deviceMakerName", "samsung")
            attribute("deviceMakerType", "0")
            attribute("mcc", region.mcc)
            attribute("mnc", region.mnc)
            attribute("csc", region.csc)
            attribute("odcVersion", VERSION_NAME)
            attribute("storeFilter", "")
            attribute("supportFeature", "")
            attribute("version", "7.8")
            attribute("filter", "1")
            attribute("odcType", "01")
            attribute("storeMode", "0")
            attribute("cacheVersion", "1")
            attribute("systemId", systemId)
            attribute("sessionId", profile.instanceId)
            attribute("logId", identity.stduk.take(16))
            attribute("deviceFeature", "locale=$locale||abi64=$abi64||oneUiVersion=60100")
            attribute("userMode", "0")
            append('>')
            append("<request")
            attribute("name", name)
            attribute("id", id)
            attribute("numParam", params.size.toString())
            attribute("transactionId", transactionId(identity.stduk, now.toString()))
            append('>')
            params.forEach { (key, value) ->
                append("<param name=\"").append(SamsungXml.escape(key)).append("\">")
                append(SamsungXml.escape(value))
                append("</param>")
            }
            append("</request></SamsungProtocol>")
        }
    }

    private fun transactionId(stduk: String, instant: String): String {
        val hour = instant.take(13).filter(Char::isDigit).padEnd(10, '0')
        val day = instant.drop(8).take(2).toIntOrNull()?.toString() ?: "1"
        return day + hex(sha256((stduk + hour + "GalaxyApps").encodeToByteArray())).take(7)
    }

    private fun StringBuilder.attribute(name: String, value: String) {
        append(' ').append(name).append("=\"").append(SamsungXml.escape(value)).append('"')
    }

    private fun hex(value: ByteArray): String = value.joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}
