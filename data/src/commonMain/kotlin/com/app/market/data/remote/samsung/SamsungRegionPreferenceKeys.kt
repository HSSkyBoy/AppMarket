package com.app.market.data.remote.samsung

import com.app.market.data.local.StringPreferenceKey

internal object SamsungRegionPreferenceKeys {
    private const val NS = "samsung_region"

    val CountryUrl = StringPreferenceKey(NS, "china_country_url")
    val CountryCode = StringPreferenceKey(NS, "china_country_code")
    val Mcc = StringPreferenceKey(NS, "china_mcc")
    val Generation = StringPreferenceKey(NS, "china_generation")
}
