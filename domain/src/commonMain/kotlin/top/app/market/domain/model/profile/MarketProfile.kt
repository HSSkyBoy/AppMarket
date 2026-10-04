package top.app.market.domain.model.profile

data class MarketProfile(
    val co: String,
    val la: String,
    val lo: String,
    val cpuArchitecture: String,
    val device: String,
    val model: String,
    val os: String,
    val osV2: String,
    val androidVersion: String,
    val sdk: String,
    val resolution: String,
    val densityDpi: String,
    val densityScaleFactor: String,
    val miuiBigVersionCode: String,
    val miuiBigVersionName: String,
    val osBigVersionCode: String,
    val osBigVersionName: String,
    val marketVersion: String,
    val pageConfigVersion: String,
    val webResVersion: String,
    val hybridFrameworkVersion: String,
    val buildId: String,
    val instanceId: String,
    val hasGMSCore: String,
    val supportedIslandVersion: String,
    /** Honor App Market terminal fields. */
    val hman: String = "",
    val htype: String = "",
    val spreadModelName: String = "",
    val deliveryCountry: String = "",
    val roamingCountry: String = "",
    val osVer: String = "",
    val magicVersion: String = "",
    val androidApiVersion: String = "",
    val apkVer: String = "",
    val apkVerName: String = "",
    val language: String = "",
    val dpi: String = "",
    val cpu: String = "",
    val supportGms: String = "",
    /** 1 phone, 2 tablet, 3 foldable. */
    val terminalType: String = "1",
    /** Honor identity fields are runtime-derived and intentionally not exposed as editable profile fields. */
    val honorAndroidId: String = "",
    val honorUdid: String = "",
    val honorOaid: String = "",
    /** ro.build.display.id, used by the official client's magicSysVersion header. */
    val honorMagicSysVersion: String = "",
    /** ro.logsystem.usertype; commercial Honor firmware normally reports 0. */
    val honorUserType: String = "-1",
    /** Honor uses 2 for a regular non-Honor Android runtime and 1 for Honor firmware. */
    val honorDeviceMode: String = "2",
    /** Honor wire value: 1 for the normal user, 2 for parallel space. */
    val honorIsParallelSpace: String = "1",
)
