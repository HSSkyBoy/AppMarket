package top.app.market.data.remote.honor

import top.app.market.data.remote.xiaomi.arr
import top.app.market.data.remote.xiaomi.bool
import top.app.market.data.remote.xiaomi.double
import top.app.market.data.remote.xiaomi.int
import top.app.market.data.remote.xiaomi.long
import top.app.market.data.remote.xiaomi.obj
import top.app.market.data.remote.xiaomi.objAt
import top.app.market.data.remote.xiaomi.str
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppScreenshot
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.ScreenshotOrientation
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal data class HonorAppRecord(
    val value: JsonObject,
    val displayOnUpdatePage: Boolean = true,
    val priorityUpdate: Boolean = false,
) {
    val appId: Long get() = value.long("id")
    val packageName: String get() = value.str("pName")
    val versionCode: Long get() = value.long("verCode")
    val versionName: String get() = value.str("verName")

    fun toApp(installed: InstalledPackage? = null): MarketAppInfo = MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = value.str("name", packageName),
        publisherName = value.str("company"),
        versionName = versionName,
        versionCode = versionCode,
        icon = honorImageUrl(value.str("imgUrl")),
        apkSize = value.long("fileSize"),
        deltaSize = value.obj("diffApk")?.long("fileSize")?.takeIf { it > 0L } ?: 0L,
        ratingScore = value.double("stars").coerceIn(0.0, 5.0),
        changeLog = value.str("verUptDes"),
        isSystemApp = installed?.isSystemApp == true,
        openLink = "honor:$appId:$packageName",
        installedVersionName = installed?.versionName.orEmpty(),
        installedVersionCode = installed?.versionCode ?: 0L,
        installedOldApkHash = installed?.oldApkHash ?: "0",
        installedBaseApkPath = installed?.baseApkPath.orEmpty(),
        installedSplits = installed?.splits ?: "0",
        type = if (priorityUpdate) "honorPriorityUpdate" else "honor",
        isAd = value.bool("isAdRecommend") || value.bool("adIntegrationEnable"),
        source = AppSource.HONOR,
        category = value.obj("thirdLevelCategory")?.str("classifyName")
            .orEmpty().ifBlank { value.str("secondCategoryName") },
        downloadCount = value.long("downTm", value.long("downNum")),
    )

    fun toDetail(installed: InstalledPackage? = null): AppDetail {
        val app = toApp(installed)
        val screenshots = value.str("shotImg")
            .split(',')
            .map(String::trim)
            .map(::honorImageUrl)
            .filter(String::isNotBlank)
            .distinct()
            .map { url ->
                AppScreenshot(
                    url = url,
                    orientation = if (value.bool("isLandScape")) {
                        ScreenshotOrientation.LANDSCAPE
                    } else {
                        ScreenshotOrientation.PORTRAIT
                    },
                    expandedUrl = url,
                )
            }
        val category = value.arr("classifyInfo")?.objAt(0)?.str("classifyName")
            .orEmpty().ifBlank { app.category }
        return AppDetail(
            app = app,
            brief = value.str("brief"),
            introduction = value.str("desc").ifBlank { value.str("description") },
            changeLog = value.str("verUptDes"),
            category = category,
            ageClassification = value.str("ageLimit").ifBlank {
                value.int("age_limit").takeIf { it > 0 }?.toString().orEmpty()
            },
            downloadCount = value.long("downNum", value.long("downTm")),
            registrationNum = value.str("registrationNumber"),
            updateTime = honorDateMillis(value.str("verUptTime")),
            privacyUrl = value.str("privacyAgreement").ifBlank { value.str("privacyUrl") },
            screenshots = screenshots,
            commentCount = value.long("displayCommentNum", value.str("scoreNum").toLongOrNull() ?: 0L),
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }
}

internal fun HonorAppRecord.hasDownloadPayload(): Boolean = value.hasHonorDownloadPayload()

private fun JsonObject.hasHonorDownloadPayload(): Boolean {
    if (hasHonorDownloadLocation()) return true
    val parts = arr("apks") ?: return false
    return parts.indices.any { index -> parts.objAt(index)?.hasHonorDownloadLocation() == true }
}

private fun JsonObject.hasHonorDownloadLocation(): Boolean =
    str("downloadUrlMetaPath").isNotBlank() || str("downUrl").startsWith("https://", ignoreCase = true)

internal fun honorImageUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith('/') -> HONOR_IMAGE_BASE + trimmed
        else -> HONOR_IMAGE_BASE + "/$trimmed"
    }
}

internal data class HonorSystemUpdatePolicy(
    val displayOnUpdatePage: Boolean,
    val priorityUpdate: Boolean,
)

internal data class HonorUpdateConfig(
    val maxBatchSize: Int,
    val refreshIntervalSeconds: Long,
    val checkIntervalSeconds: Long,
    val systemPolicies: Map<String, HonorSystemUpdatePolicy>,
)


internal fun JsonArray?.honorApps(): List<HonorAppRecord> = buildList {
    val source = this@honorApps ?: return@buildList
    for (index in source.indices) {
        val value = source.objAt(index) ?: continue
        if (value.str("pName").isNotBlank() && value.long("verCode") > 0L) add(HonorAppRecord(value))
    }
}

internal fun mergeHonorRecords(records: Iterable<HonorAppRecord>): List<HonorAppRecord> {
    val merged = linkedMapOf<String, HonorAppRecord>()
    records.forEach { candidate ->
        val key = candidate.packageName.lowercase()
        if (key.isBlank()) return@forEach
        merged[key] = merged[key]?.mergeWith(candidate) ?: candidate
    }
    return merged.values.toList()
}

/**
 * 搜索的两个线上来源互为补充，任何一个业务错误都不能阻断另一个来源。
 */
internal suspend fun mergeHonorSearchSources(
    appList: suspend () -> List<HonorAppRecord>,
    merged: suspend () -> List<HonorAppRecord>,
    onFailure: (source: String, error: Throwable) -> Unit = { _, _ -> },
): List<HonorAppRecord> {
    val appListRecords = runCatching { appList() }
        .onFailure { onFailure("v2", it) }
        .getOrDefault(emptyList())
    val mergedRecords = runCatching { merged() }
        .onFailure { onFailure("v1", it) }
        .getOrDefault(emptyList())
    return mergeHonorRecords(appListRecords + mergedRecords)
}

internal fun Iterable<HonorAppRecord>.honorRecordForPackage(packageName: String): HonorAppRecord? =
    filter { it.packageName.equals(packageName, ignoreCase = true) }
        .let(::mergeHonorRecords)
        .maxByOrNull(HonorAppRecord::versionCode)

internal fun HonorAppRecord.mergeWith(candidate: HonorAppRecord): HonorAppRecord {
    if (!packageName.equals(candidate.packageName, ignoreCase = true)) {
        return if (candidate.versionCode > versionCode) candidate else this
    }
    if (candidate.versionCode != versionCode) {
        return if (candidate.versionCode > versionCode) candidate else this
    }
    val preferred = if (candidate.metadataScore() >= metadataScore()) candidate else this
    val fallback = if (preferred === this) candidate else this
    return preferred.copy(
        value = JsonObject(fallback.value + preferred.value),
        displayOnUpdatePage = displayOnUpdatePage && candidate.displayOnUpdatePage,
        priorityUpdate = priorityUpdate || candidate.priorityUpdate,
    )
}

private fun honorDateMillis(value: String): Long = runCatching {
    LocalDate.parse(value.take(10)).atStartOfDayIn(TimeZone.of("Asia/Shanghai")).toEpochMilliseconds()
}.getOrDefault(0L)

private fun HonorAppRecord.metadataScore(): Int = buildList {
    add(value.long("fileSize") > 0L)
    add(value.str("downUrl").isNotBlank())
    add(value.str("downloadUrlMetaPath").isNotBlank())
    add(value.str("apkIdentifier").isNotBlank())
    add(value.arr("apks")?.isNotEmpty() == true)
    add(value.obj("diffApk") != null)
    add(value.str("shotImg").isNotBlank())
    add(value.str("desc").isNotBlank() || value.str("description").isNotBlank())
    add(value.str("company").isNotBlank())
}.count { it }

private const val HONOR_IMAGE_BASE = "https://appimg-drcn.hihonorcdn.com"
