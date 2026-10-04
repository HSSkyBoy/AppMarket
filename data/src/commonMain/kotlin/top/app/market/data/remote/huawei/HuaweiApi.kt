package top.app.market.data.remote.huawei

import top.app.market.data.remote.xiaomi.arr
import top.app.market.data.remote.xiaomi.double
import top.app.market.data.remote.xiaomi.int
import top.app.market.data.remote.xiaomi.long
import top.app.market.data.remote.xiaomi.str
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal class HuaweiApi(
    private val protocol: HuaweiProtocol,
    private val profiles: ProfileRepository,
) {
    suspend fun search(keyword: String, page: Int, pageSize: Int = SEARCH_PAGE_SIZE): Pair<List<HuaweiAppRecord>, Boolean> {
        if (keyword.isBlank()) return emptyList<HuaweiAppRecord>() to false
        val pageNumber = page.coerceAtLeast(0) + 1
        val root = protocol.post(
            method = "client.getTabDetail",
            fields = mapOf(
                "uri" to "searchApp|$keyword",
                "inputWord" to keyword,
                "reqPageNum" to pageNumber.toString(),
                "maxResults" to pageSize.toString(),
                "isSupportPage" to "1",
            ),
        )
        val records = parseHuaweiSearchRecords(root)
            .filterNot(::isHuaweiQuickApp)
            .groupBy { it.packageName.lowercase() }
            .values
            .map { candidates ->
                candidates.maxWithOrNull(
                    compareBy<HuaweiAppRecord> { !it.isAd }
                        .thenBy(HuaweiAppRecord::versionCode)
                        .thenBy { it.completenessScore() },
                ) ?: candidates.first()
            }
        val details = tryBatchDetails(records.map(HuaweiAppRecord::packageName))
            .associateBy { it.packageName.lowercase() }
        val enriched = records.map { record ->
            val detail = details[record.packageName.lowercase()]
            detail?.copy(
                detailId = record.detailId.ifBlank { detail.detailId },
                isAd = record.isAd,
            ) ?: record
        }
        val withExactPackage = if (keyword.looksLikeAndroidPackage() &&
            enriched.none { it.packageName.equals(keyword, ignoreCase = true) }
        ) {
            listOfNotNull(tryDetail(keyword)) + enriched
        } else {
            enriched
        }
        val totalPages = root.int("totalPages", root.str("totalPages").toIntOrNull() ?: pageNumber)
        val hasMore = root.int("hasNextPage", root.str("hasNextPage").toIntOrNull() ?: 0) != 0 ||
                pageNumber < totalPages
        return withExactPackage
            .filterNot(::isHuaweiQuickApp)
            .distinctBy { it.packageName.lowercase() } to hasMore
    }

    suspend fun detail(packageName: String): HuaweiAppRecord {
        tryBatchDetails(listOf(packageName))
            .filter { it.packageName.equals(packageName, ignoreCase = true) }
            .maxByOrNull(HuaweiAppRecord::versionCode)
            ?.let { return it }
        val root = protocol.post(
            method = "client.appDetailById",
            fields = mapOf("package" to packageName),
        )
        return root.arr("detailInfo").objects()
            .mapNotNull(::parseDetailRecord)
            .filter { it.packageName.equals(packageName, ignoreCase = true) }
            .maxByOrNull(HuaweiAppRecord::versionCode)
            ?: throw MarketException("华为应用市场未收录该应用")
    }

    private suspend fun tryBatchDetails(packageNames: List<String>): List<HuaweiAppRecord> {
        if (packageNames.isEmpty()) return emptyList()
        return try {
            packageNames.distinctBy(String::lowercase).chunked(BATCH_DETAIL_SIZE).flatMap { packages ->
                val root = protocol.post(
                    method = "client.batchAppDetail",
                    fields = mapOf(
                        "idType" to "2",
                        "idList" to packages.joinToString(","),
                    ),
                )
                root.arr("appList").objects().mapNotNull(::parseDetailRecord)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: MarketException) {
            emptyList()
        }
    }

    private suspend fun tryDetail(packageName: String): HuaweiAppRecord? = try {
        detail(packageName)
    } catch (error: CancellationException) {
        throw error
    } catch (_: MarketException) {
        null
    }

    /** 快应用以 .rpk 分发，没有可安装的 APK 与详情页。 */
    private fun isHuaweiQuickApp(record: HuaweiAppRecord): Boolean =
        record.downloadUrl.substringBefore('?').endsWith(".rpk", ignoreCase = true)

    suspend fun updates(installed: List<InstalledPackage>, manual: Boolean): HuaweiUpdateResponse {
        if (installed.isEmpty()) return HuaweiUpdateResponse(emptyList(), emptySet())
        val profile = profiles.load(AppSource.HUAWEI)
        val params = buildJsonArray {
            installed.forEach { item ->
                add(buildJsonObject {
                    put("package", item.packageName)
                    put("versionCode", item.versionCode)
                    put("oldVersion", item.versionName)
                    put("targetSdkVersion", item.targetSdkVersion)
                    // Do not send sSha2. AppGallery uses it as an install-provenance gate and
                    // suppresses otherwise valid updates/deltas. AppMarket authenticates the
                    // old APK by fSha2 and verifies the synthesized target by SHA-256 instead.
                    put("fSha2", item.oldApkHash.takeIf { it.isHuaweiSha256Value() }.orEmpty())
                    put("appBits", if (profile.cpuArchitecture.contains("64")) 2 else 1)
                    put("pkgMode", 0)
                    put("isPre", if (item.isSystemApp) 2 else 0)
                    put("maple", 0)
                    put("installationFree", 0)
                    put("shellApkVer", 0)
                })
            }
        }
        val fields = mapOf(
            "json" to buildJsonObject { put("params", params) }.toString(),
            // AppMarket implements Huawei's type-0 VCDIFF locally. Type 2 is a private
            // APK-friendly container and is intentionally not negotiated until verified.
            "supportDiffTypes" to "[0]",
            "maxMem" to "4194304",
            "isWlanIdle" to "0",
            "isFetchAllGxxApps" to "0",
            "installCheck" to "0",
            "isFullUpgrade" to "0",
        )
        val root = if (manual) {
            val manualRoot = try {
                protocol.post(
                    method = "client.manualDiffUpgrade",
                    version = "1.2",
                    fields = fields,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: MarketException) {
                null
            }
            if (manualRoot == null || !manualRoot.hasHuaweiUpdateEnvelope()) {
                // Current AppGallery deployments can reject manualDiffUpgrade even though the
                // same signed session and package batch work on diffUpgrade2. Both return the
                // same UpdateResp schema. Some failures are encoded as rtnCode=0 plus rtnDesc
                // without any response collections, so validate the envelope as well as errors.
                protocol.post(
                    method = "client.diffUpgrade2",
                    version = "1.2",
                    fields = fields,
                )
            } else manualRoot
        } else {
            protocol.post(
                method = "client.diffUpgrade2",
                version = "1.2",
                fields = fields,
            )
        }
        // Huawei can return recommendation records alongside the requested update batch. Only
        // publish packages the caller actually submitted, especially for system-app checks.
        val requested = installed.mapTo(hashSetOf()) { it.packageName.lowercase() }
        return parseHuaweiUpdateResponse(root, requested)
    }

    private fun parseDetailRecord(value: JsonObject): HuaweiAppRecord? {
        val packageName = value.str("package").ifBlank { value.str("packageName") }
        if (packageName.isBlank()) return null
        val images = value.arr("images").strings().ifEmpty {
            listOf(value.str("screenUrl01"), value.str("screenUrl02")).filter(String::isNotBlank)
        }
        return HuaweiAppRecord(
            rawId = value.str("id").ifBlank { value.str("appid") },
            packageName = packageName,
            name = value.str("name").ifBlank { packageName },
            developer = value.str("developer"),
            versionName = value.str("versionName").ifBlank { value.str("version") },
            versionCode = value.long("versionCode"),
            icon = value.str("icoUri").ifBlank { value.str("icon") },
            size = value.long("size"),
            fullSize = value.long("fullSize", value.long("size")),
            rating = value.double("stars").coerceIn(0.0, 5.0),
            brief = value.str("briefDescription").ifBlank { value.str("comment") },
            description = value.str("description"),
            category = value.str("thirdKindName").ifBlank { value.str("kindName") },
            minAge = value.int("minAge"),
            screenshots = images,
            downloadUrl = value.str("url").ifBlank { value.str("downurl") },
            sha256 = value.str("sha256").lowercase(),
            signerSha256 = value.arr("sSha2").strings().map(String::lowercase),
            changeLog = value.str("newFeatures"),
            detailId = value.str("detailId"),
            downloadCount = parseDownloadCount(value.str("downCountDesc")),
            commentCount = value.long("commentCount"),
            packingType = value.int("packingType"),
            bundleSize = value.long("bundleSize"),
            disabled = value.int("btnDisable") != 0,
            nonAdaptType = value.int("nonAdaptType"),
            raw = value,
        )
    }

    private fun String.isHuaweiSha256Value(): Boolean =
        length == 64 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun HuaweiAppRecord.completenessScore(): Int = listOf(
        rawId,
        name,
        versionName,
        icon,
        downloadUrl,
        sha256,
        brief,
    ).count(String::isNotBlank) + listOf(versionCode, size, fullSize).count { it > 0L }

    private companion object {
        const val SEARCH_PAGE_SIZE = 20
        const val BATCH_DETAIL_SIZE = 50
    }
}

internal fun parseHuaweiSearchRecords(root: JsonObject): List<HuaweiAppRecord> = buildList {
    root.arr("layoutData")?.forEach { value -> value.collectHuaweiAppRecords(this) }
}

private fun JsonElement.collectHuaweiAppRecords(destination: MutableList<HuaweiAppRecord>) {
    when (this) {
        is JsonObject -> {
            parseHuaweiLayoutRecord(this)?.let(destination::add)
            values.forEach { it.collectHuaweiAppRecords(destination) }
        }

        is JsonArray -> forEach { it.collectHuaweiAppRecords(destination) }
        else -> Unit
    }
}

private fun JsonObject.hasHuaweiUpdateEnvelope(): Boolean =
    UPDATE_RESPONSE_COLLECTIONS.any(::containsKey)

private val UPDATE_RESPONSE_COLLECTIONS = setOf(
    "list",
    "notRcmList",
    "toUpdateAppInfoList",
    "allGxxApps",
    "sharedLibs",
    "updatePolicyText",
)

/**
 * Huawei labels some valid update metadata as not recommended based on install provenance.
 * AppMarket treats that as presentation policy, not a reason to hide a newer package.
 */
internal fun parseHuaweiUpdateResponse(
    root: JsonObject,
    requested: Set<String>,
): HuaweiUpdateResponse {
    val normalizedRequested = requested.mapTo(hashSetOf(), String::lowercase)
    val updates = (root.arr("list").objects() + root.arr("notRcmList").objects())
        .mapNotNull(::parseHuaweiUpdateRecord)
        .filter { it.app.packageName.lowercase() in normalizedRequested }
        .distinctBy { it.app.packageName.lowercase() }
    val recognized = buildSet {
        updates.mapTo(this) { it.app.packageName.lowercase() }
        root.arr("notRcmList").objects().mapTo(this) {
            it.str("package").ifBlank { it.str("packageName") }.lowercase()
        }
        root.arr("toUpdateAppInfoList").objects().mapTo(this) {
            it.str("package").ifBlank { it.str("packageName") }.lowercase()
        }
    }.filterTo(mutableSetOf()) { it.isNotBlank() && it in normalizedRequested }
    return HuaweiUpdateResponse(updates, recognized)
}

private fun parseHuaweiUpdateRecord(value: JsonObject): HuaweiUpdateRecord? {
    val app = parseHuaweiLayoutRecord(value) ?: return null
    val diffSize = value.long("diffSize")
    val isDiff = value.int("isDiff") != 0 || diffSize > 0L
    val direct = value.str("downurl")
    val full = value.str("fullDownUrl").ifBlank { if (!isDiff) direct else "" }
    return HuaweiUpdateRecord(
        app = app.copy(
            versionName = value.str("version").ifBlank { app.versionName },
            changeLog = value.str("newFeatures"),
            downloadUrl = direct,
            signerSha256 = value.arr("sSha2").strings().map(String::lowercase),
        ),
        oldVersionName = value.str("oldVersionName"),
        oldVersionCode = value.long("oldVersionCode"),
        fullDownloadUrl = full,
        patchUrl = if (isDiff) direct else "",
        patchSize = diffSize,
        patchSha256 = value.str("diffSha2").lowercase(),
        patchType = value.int("diffType"),
        isDiff = isDiff,
        backgroundPatchUrl = value.str("bgDownurl"),
        backgroundPatchSize = value.long("bgDiffSize"),
        backgroundPatchSha256 = value.str("bgDiffSha2").lowercase(),
        backgroundPatchType = value.int("bgDiffType"),
    )
}

private fun parseHuaweiLayoutRecord(value: JsonObject): HuaweiAppRecord? {
    val packageName = value.str("package").ifBlank { value.str("packageName") }
    if (packageName.isBlank()) return null
    return HuaweiAppRecord(
        rawId = value.str("appid").ifBlank { value.str("id") },
        packageName = packageName,
        name = value.str("name").ifBlank { value.str("appName") }.ifBlank { packageName },
        developer = value.str("developer"),
        versionName = value.str("appVersionName").ifBlank { value.str("versionName") },
        versionCode = value.long("versionCode"),
        icon = value.str("icon").ifBlank { value.str("icoUri") },
        size = value.long("size"),
        fullSize = value.long("fullSize", value.long("size")),
        rating = value.double("stars").coerceIn(0.0, 5.0),
        brief = value.str("memo").ifBlank { value.str("subTitle") }.ifBlank { value.str("intro") },
        category = value.str("kindName"),
        minAge = value.int("minAge"),
        downloadUrl = value.str("downurl").ifBlank { value.str("url") },
        sha256 = value.str("sha256").lowercase(),
        detailId = value.str("detailId"),
        downloadCount = parseDownloadCount(value.str("downCountDesc")),
        commentCount = value.long("commentCount"),
        packingType = value.int("packingType"),
        bundleSize = value.long("bundleSize"),
        disabled = value.int("btnDisable") != 0,
        nonAdaptType = value.int("nonAdaptType"),
        isAd = value.huaweiAdFlag(),
        raw = value,
    )
}

private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonArray?.strings(): List<String> = this?.mapNotNull {
    (it as? JsonPrimitive)?.contentOrNull
}?.filter(String::isNotBlank).orEmpty()

private fun parseDownloadCount(raw: String): Long {
    val normalized = raw.replace(",", "").replace("次", "").replace("安装", "").trim()
    val multiplier = when {
        normalized.contains('亿') -> 100_000_000.0
        normalized.contains('万') -> 10_000.0
        else -> 1.0
    }
    return ((normalized.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0) * multiplier).toLong()
}

private fun JsonObject.huaweiAdFlag(): Boolean {
    fun enabled(name: String): Boolean = when (val value = this[name]) {
        is JsonPrimitive -> value.contentOrNull?.trim()?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } == true

        else -> false
    }
    return enabled("adFlag") || enabled("showAdTag")
}

private fun String.looksLikeAndroidPackage(): Boolean {
    if (length !in 3..255 || startsWith('.') || endsWith('.') || '.' !in this) return false
    return split('.').all { segment ->
        segment.isNotEmpty() && (segment.first().isLetter() || segment.first() == '_') &&
                segment.all { it.isLetterOrDigit() || it == '_' }
    }
}

internal fun String.isHuaweiSha256(): Boolean =
    length == 64 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
