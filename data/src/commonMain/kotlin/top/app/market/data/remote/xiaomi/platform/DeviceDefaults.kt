package top.app.market.data.remote.xiaomi.platform

/** Device fingerprint fields sourced from the platform; the rest of [top.app.market.domain.model.profile.MarketProfile] uses fixed defaults. */
data class DeviceDefaults(
    val cpuArchitecture: String,
    val device: String,
    val model: String,
    val androidVersion: String,
    val sdk: String,
    val language: String,
    val manufacturer: String = "",
    val os: String = androidVersion,
    val osV2: String = "Android",
    val miuiBigVersionCode: String = "816",
    val miuiBigVersionName: String = "V816",
    val osBigVersionCode: String = "3",
    val osBigVersionName: String = "OS3.0",
    /** `Build.ID`，用于拼 User-Agent 的 `Build/<id>`，必须与真机一致，否则系统应用更新拉不到。 */
    val buildId: String = "BP2A.250605.031.A3",
    val co: String = "CN",
    val lo: String = "CN",
    val resolution: String = "1080*2400",
    val densityDpi: String = "440",
    val densityScaleFactor: String = "2.75",
    val hasGMSCore: String = "true",
    val supportedIslandVersion: String = "3",
    /** com.miui.hybrid 版本号，未安装为空串。 */
    val hybridFrameworkVersion: String = "",
    /** 是否小米设备。 */
    val isXiaomi: Boolean = false,
    /** 关键指纹字段是否齐全。 */
    val isComplete: Boolean = false,
    /** 是否 OPPO、一加或 realme 设备。 */
    val isOppoFamily: Boolean = false,
    /** OPPO 系设备请求所需的基础指纹字段是否齐全。 */
    val isOppoComplete: Boolean = false,
    /** 是否 vivo 或 iQOO 设备。 */
    val isVivoFamily: Boolean = false,
    /** vivo 请求所需的基础指纹字段是否齐全。 */
    val isVivoComplete: Boolean = false,
    /** Whether this is a Samsung Galaxy device. */
    val isSamsungFamily: Boolean = false,
    /** Samsung ODC's basic device fields are available. */
    val isSamsungComplete: Boolean = false,
    /** Whether this is an Honor device. */
    val isHonorFamily: Boolean = false,
    /** Honor App Market's basic terminal fields are available. */
    val isHonorComplete: Boolean = false,
    /** Whether this is a Huawei device. */
    val isHuaweiFamily: Boolean = false,
    /** Huawei AppGallery's basic device fields are available. */
    val isHuaweiComplete: Boolean = false,
    /** MagicOS version reported by Honor firmware, when available. */
    val magicVersion: String = "",
    /** Marketing name reported by Honor firmware, when available. */
    val honorMarketingName: String = "",
    /** Honor terminal shape: 1 phone, 2 tablet, 3 foldable. */
    val honorTerminalType: String = "1",
    /** App-scoped Android ID; blank on non-Android platforms. */
    val honorAndroidId: String = "",
    /** Hardware identifiers remain blank when the platform API does not grant access. */
    val honorUdid: String = "",
    val honorOaid: String = "",
    val honorMagicSysVersion: String = "",
    val honorUserType: String = "-1",
    val honorDeviceMode: String = "2",
    /** Honor wire value: 1 for the normal user, 2 for parallel space. */
    val honorIsParallelSpace: String = "1",
)

internal fun isOppoFamilyDevice(manufacturer: String, brand: String): Boolean =
    sequenceOf(manufacturer, brand)
        .map { it.trim().lowercase() }
        .any { it in setOf("oppo", "oneplus", "realme") }

internal fun isVivoFamilyDevice(manufacturer: String, brand: String, device: String = ""): Boolean =
    sequenceOf(manufacturer, brand, device)
        .map { it.trim().lowercase() }
        .any { it == "vivo" || it == "iqoo" || it.startsWith("vivo") || it.startsWith("iqoo") }

internal fun isSamsungFamilyDevice(manufacturer: String, brand: String): Boolean =
    sequenceOf(manufacturer, brand)
        .map { it.trim().lowercase() }
        .any { it == "samsung" }

internal fun isHuaweiFamilyDevice(manufacturer: String, brand: String): Boolean =
    sequenceOf(manufacturer, brand)
        .map { it.trim().lowercase() }
        .any { it == "huawei" }

internal fun isHonorFamilyDevice(manufacturer: String, brand: String): Boolean =
    sequenceOf(manufacturer, brand)
        .map { it.trim().lowercase() }
        .any { it == "honor" }

/** Platform source for the market profile's device-backed defaults. */
internal interface DeviceDefaultsDataSource {
    fun current(): DeviceDefaults
}
