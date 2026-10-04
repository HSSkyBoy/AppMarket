package top.app.market.domain.model.profile

/** Editable fields exposed by the device-profile UI. */
object MarketProfileFields {
    val ALL = listOf(
        "co", "la", "lo", "cpuArchitecture", "device", "model", "os", "osV2",
        "androidVersion", "sdk", "resolution", "densityDpi", "densityScaleFactor",
        "miuiBigVersionCode", "miuiBigVersionName", "osBigVersionCode", "osBigVersionName",
        "marketVersion", "pageConfigVersion", "webResVersion", "hybridFrameworkVersion",
        "buildId", "instanceId", "hasGMSCore", "supportedIslandVersion",
        "hman", "htype", "spreadModelName", "deliveryCountry", "roamingCountry",
        "osVer", "magicVersion", "androidApiVersion", "apkVer", "apkVerName",
        "language", "dpi", "cpu", "supportGms", "terminalType",
    )

    fun valueOf(profile: MarketProfile, name: String): String = when (name) {
        "co" -> profile.co
        "la" -> profile.la
        "lo" -> profile.lo
        "cpuArchitecture" -> profile.cpuArchitecture
        "device" -> profile.device
        "model" -> profile.model
        "os" -> profile.os
        "osV2" -> profile.osV2
        "androidVersion" -> profile.androidVersion
        "sdk" -> profile.sdk
        "resolution" -> profile.resolution
        "densityDpi" -> profile.densityDpi
        "densityScaleFactor" -> profile.densityScaleFactor
        "miuiBigVersionCode" -> profile.miuiBigVersionCode
        "miuiBigVersionName" -> profile.miuiBigVersionName
        "osBigVersionCode" -> profile.osBigVersionCode
        "osBigVersionName" -> profile.osBigVersionName
        "marketVersion" -> profile.marketVersion
        "pageConfigVersion" -> profile.pageConfigVersion
        "webResVersion" -> profile.webResVersion
        "hybridFrameworkVersion" -> profile.hybridFrameworkVersion
        "buildId" -> profile.buildId
        "instanceId" -> profile.instanceId
        "hasGMSCore" -> profile.hasGMSCore
        "supportedIslandVersion" -> profile.supportedIslandVersion
        "hman" -> profile.hman
        "htype" -> profile.htype
        "spreadModelName" -> profile.spreadModelName
        "deliveryCountry" -> profile.deliveryCountry
        "roamingCountry" -> profile.roamingCountry
        "osVer" -> profile.osVer
        "magicVersion" -> profile.magicVersion
        "androidApiVersion" -> profile.androidApiVersion
        "apkVer" -> profile.apkVer
        "apkVerName" -> profile.apkVerName
        "language" -> profile.language
        "dpi" -> profile.dpi
        "cpu" -> profile.cpu
        "supportGms" -> profile.supportGms
        else -> profile.terminalType
    }

    fun set(profile: MarketProfile, name: String, value: String): MarketProfile = when (name) {
        "co" -> profile.copy(co = value)
        "la" -> profile.copy(la = value)
        "lo" -> profile.copy(lo = value)
        "cpuArchitecture" -> profile.copy(cpuArchitecture = value)
        "device" -> profile.copy(device = value)
        "model" -> profile.copy(model = value)
        "os" -> profile.copy(os = value)
        "osV2" -> profile.copy(osV2 = value)
        "androidVersion" -> profile.copy(androidVersion = value)
        "sdk" -> profile.copy(sdk = value)
        "resolution" -> profile.copy(resolution = value)
        "densityDpi" -> profile.copy(densityDpi = value)
        "densityScaleFactor" -> profile.copy(densityScaleFactor = value)
        "miuiBigVersionCode" -> profile.copy(miuiBigVersionCode = value)
        "miuiBigVersionName" -> profile.copy(miuiBigVersionName = value)
        "osBigVersionCode" -> profile.copy(osBigVersionCode = value)
        "osBigVersionName" -> profile.copy(osBigVersionName = value)
        "marketVersion" -> profile.copy(marketVersion = value)
        "pageConfigVersion" -> profile.copy(pageConfigVersion = value)
        "webResVersion" -> profile.copy(webResVersion = value)
        "hybridFrameworkVersion" -> profile.copy(hybridFrameworkVersion = value)
        "buildId" -> profile.copy(buildId = value)
        "instanceId" -> profile.copy(instanceId = value)
        "hasGMSCore" -> profile.copy(hasGMSCore = value)
        "supportedIslandVersion" -> profile.copy(supportedIslandVersion = value)
        "hman" -> profile.copy(hman = value)
        "htype" -> profile.copy(htype = value)
        "spreadModelName" -> profile.copy(spreadModelName = value)
        "deliveryCountry" -> profile.copy(deliveryCountry = value)
        "roamingCountry" -> profile.copy(roamingCountry = value)
        "osVer" -> profile.copy(osVer = value)
        "magicVersion" -> profile.copy(magicVersion = value)
        "androidApiVersion" -> profile.copy(androidApiVersion = value)
        "apkVer" -> profile.copy(apkVer = value)
        "apkVerName" -> profile.copy(apkVerName = value)
        "language" -> profile.copy(language = value)
        "dpi" -> profile.copy(dpi = value)
        "cpu" -> profile.copy(cpu = value)
        "supportGms" -> profile.copy(supportGms = value)
        else -> profile.copy(terminalType = value)
    }
}
