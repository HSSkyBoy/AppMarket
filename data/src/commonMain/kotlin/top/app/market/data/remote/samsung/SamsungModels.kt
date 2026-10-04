package top.app.market.data.remote.samsung

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

internal data class SamsungRegionContext(
    val generation: Long,
    val countryUrl: String,
    val mcc: String,
    val mnc: String,
    val csc: String,
    val countryCode: String,
    val lang: String,
)

internal data class SamsungProductRef(
    val productId: String,
    val guid: String,
    val generation: Long,
    val linkProduct: Boolean = false,
    val tencentLastInterface: String = "",
) {
    fun toLink(): String = buildString {
        append("appmarket-samsung://product?")
        append("productId=").append(productId.encodeURLParameter())
        append("&guid=").append(guid.encodeURLParameter())
        append("&generation=").append(generation)
        append("&linkProduct=").append(if (linkProduct) "1" else "0")
        if (tencentLastInterface.isNotBlank()) {
            append("&last=").append(tencentLastInterface.encodeURLParameter())
        }
    }

    companion object {
        fun parse(raw: String): SamsungProductRef? = runCatching {
            val url = Url(raw)
            if (url.protocol.name != "appmarket-samsung" || url.host != "product") return null
            val productId = url.parameters["productId"].orEmpty()
            val guid = url.parameters["guid"].orEmpty()
            if (productId.isBlank() && guid.isBlank()) return null
            SamsungProductRef(
                productId = productId,
                guid = guid,
                generation = url.parameters["generation"]?.toLongOrNull() ?: 0L,
                linkProduct = url.parameters["linkProduct"] == "1",
                tencentLastInterface = url.parameters["last"].orEmpty(),
            )
        }.getOrNull()
    }
}

internal data class SamsungProduct(
    val values: Map<String, String>,
    val ref: SamsungProductRef,
)

internal data class SamsungProductDetail(
    val main: Map<String, String>,
    val overview: Map<String, String>,
    val ref: SamsungProductRef,
)

internal data class SamsungDownload(
    val values: Map<String, String>,
    val ref: SamsungProductRef,
)
