package top.app.market.data.remote.huawei

import kotlinx.serialization.json.JsonObject

internal data class HuaweiTab(
    val id: String,
    val name: String,
    val englishName: String = "",
    val children: List<HuaweiTab> = emptyList(),
)

internal data class HuaweiSession(
    val sign: String,
    val serviceZone: String,
    val physicalZone: String,
    val tabs: List<HuaweiTab>,
)

internal data class HuaweiAppRecord(
    val rawId: String,
    val packageName: String,
    val name: String,
    val developer: String = "",
    val versionName: String = "",
    val versionCode: Long = 0L,
    val icon: String = "",
    val size: Long = 0L,
    val fullSize: Long = 0L,
    val rating: Double = 0.0,
    val brief: String = "",
    val description: String = "",
    val category: String = "",
    val minAge: Int = 0,
    val screenshots: List<String> = emptyList(),
    val downloadUrl: String = "",
    val sha256: String = "",
    val signerSha256: List<String> = emptyList(),
    val changeLog: String = "",
    val detailId: String = "",
    val downloadCount: Long = 0L,
    val commentCount: Long = 0L,
    val packingType: Int = 0,
    val bundleSize: Long = 0L,
    val disabled: Boolean = false,
    val nonAdaptType: Int = 0,
    val isAd: Boolean = false,
    val raw: JsonObject,
)

internal data class HuaweiUpdateRecord(
    val app: HuaweiAppRecord,
    val oldVersionName: String,
    val oldVersionCode: Long,
    val fullDownloadUrl: String,
    val patchUrl: String,
    val patchSize: Long,
    val patchSha256: String,
    val patchType: Int,
    val isDiff: Boolean,
    val backgroundPatchUrl: String,
    val backgroundPatchSize: Long,
    val backgroundPatchSha256: String,
    val backgroundPatchType: Int,
)

internal data class HuaweiUpdateResponse(
    val updates: List<HuaweiUpdateRecord>,
    val recognizedPackages: Set<String>,
)

