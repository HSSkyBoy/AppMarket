package com.app.market.domain.model.profile

/** Attributes written to the SamsungProtocol envelope. */
data class SamsungRequestContext(
    val countryCode: String,
    val language: String,
    val mcc: String,
    val mnc: String,
    val csc: String,
)

val DefaultSamsungRequestContext = SamsungRequestContext(
    countryCode = "CHN",
    language = "zh_CN",
    mcc = "460",
    mnc = "00",
    csc = "CHC",
)
