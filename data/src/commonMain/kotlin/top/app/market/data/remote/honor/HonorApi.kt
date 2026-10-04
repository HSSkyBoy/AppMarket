package top.app.market.data.remote.honor

import top.app.market.data.platform.debugLog
import top.app.market.data.remote.xiaomi.arr
import top.app.market.data.remote.xiaomi.int
import top.app.market.data.remote.xiaomi.len
import top.app.market.data.remote.xiaomi.long
import top.app.market.data.remote.xiaomi.obj
import top.app.market.data.remote.xiaomi.objAt
import top.app.market.data.remote.xiaomi.str
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.download.DownloadPatch
import top.app.market.domain.model.download.DownloadPatchProtocol
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.profile.MarketProfile
import top.app.market.domain.repository.ProfileRepository
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

internal class HonorApi(
    private val protocol: HonorProtocol,
    private val profiles: ProfileRepository,
) {
    private val cacheMutex = Mutex()
    private val searchSessionMutex = Mutex()
    private val recordsByPackage = linkedMapOf<String, HonorAppRecord>()
    private var searchActivated = false
    private var searchTimes = 0

    suspend fun search(keyword: String, page: Int, pageSize: Int = SEARCH_PAGE_SIZE): SearchPage {
        val profile = profiles.load(AppSource.HONOR)
        val start = page.coerceAtLeast(0) * pageSize
        val trimmedKeyword = keyword.trim()
        val currentSearchTimes = prepareSearchSession(profile)
        val association = if (page == 0 && trimmedKeyword.isNotBlank() && !trimmedKeyword.looksLikePackageName()) {
            runCatching { associationApps(profile, trimmedKeyword, currentSearchTimes) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val values = mergedSearchApps(profile, trimmedKeyword, start, pageSize, currentSearchTimes)
        var records = mergeHonorRecords(values.honorApps())
        remember(records)
        if (page == 0) {
            val exact = exactSearchSupplements(trimmedKeyword, association)
            records = mergeHonorRecords(exact + records)
        }
        remember(records)
        return SearchPage(
            items = records.map(HonorAppRecord::toApp),
            hasMore = values.len >= pageSize,
        )
    }

    /**
     * 官方客户端进入搜索页时先调用 activation，再使用同一设备会话发搜索请求。直接跳过这个步骤
     * 会触发 search 专属风控（6001），即使签名和搜索参数本身都正确。
     */
    private suspend fun prepareSearchSession(profile: MarketProfile): Int = searchSessionMutex.withLock {
        if (!searchActivated) {
            protocol.post(
                SEARCH_ACTIVATION_PATH,
                profile,
                buildJsonObject {
                    put("pageSource", 0)
                    put("recommendId", "R008")
                    put("sellingPointFlag", false)
                    put("silentUser", 3)
                },
            )
            searchActivated = true
        }
        searchTimes += 1
        searchTimes
    }

    private suspend fun mergedSearchApps(
        profile: MarketProfile,
        keyword: String,
        start: Int,
        pageSize: Int,
        searchTimes: Int,
    ): JsonArray? = protocol.post(
        SEARCH_MERGED_PATH,
        profile,
        buildJsonObject {
            put("keyword", keyword)
            put("pSize", pageSize)
            put("packageName", "")
            put("recommendId", "R009")
            put("searchTimes", searchTimes)
            put("sellingPointFlag", false)
            put("specialSellingPointFlag", false)
            put("start", start)
        },
    ).obj("data")?.obj("searchAppVOList")?.arr("appLst")

    private suspend fun associationApps(
        profile: MarketProfile,
        keyword: String,
        searchTimes: Int,
    ): List<HonorAppRecord> = exactHonorAssociationApps(
        protocol.post(
            SEARCH_ASSOCIATION_PATH,
            profile,
            buildJsonObject {
                put("key", keyword)
                put("chId", "HONOR_01")
                put("searchTimes", searchTimes)
                put("sellingPointFlag", false)
            },
        ),
        keyword,
    )

    private suspend fun exactSearchSupplements(
        keyword: String,
        association: List<HonorAppRecord>,
    ): List<HonorAppRecord> {
        val packageNames = linkedSetOf<String>()
        if (keyword.looksLikePackageName()) packageNames += keyword
        association.mapTo(packageNames, HonorAppRecord::packageName)
        return packageNames.take(MAX_EXACT_SEARCH_SUPPLEMENTS).mapNotNull { packageName ->
            val candidate = association.firstOrNull { it.packageName.equals(packageName, ignoreCase = true) }
            val detail = runCatching { detailRecord(packageName) }.getOrNull()
            mergeHonorRecords(listOfNotNull(candidate, detail)).singleOrNull()
        }
    }

    suspend fun detail(packageName: String): AppDetail {
        val record = detailRecord(packageName)
        return record.toDetail()
    }

    suspend fun detailRecord(packageName: String): HonorAppRecord {
        if (packageName.isBlank()) throw MarketException("荣耀应用包名为空")
        val cached = cachedRecord(packageName)
        val direct = runCatching { requestDetailRecord(packageName) }
        val initial = mergeHonorRecords(listOfNotNull(cached, direct.getOrNull())).singleOrNull()
        val shared = if (initial == null || initial.versionCode <= 0L || initial.value.str("shotImg").isBlank()) {
            runCatching { sharedDetailRecord(packageName) }
        } else {
            null
        }
        val resolved = mergeHonorRecords(listOfNotNull(initial, shared?.getOrNull())).singleOrNull()
            ?: throw (
                    direct.exceptionOrNull()
                        ?: shared?.exceptionOrNull()
                        ?: MarketException("荣耀未收录该应用")
                    )
        remember(listOf(resolved))
        return cachedRecord(packageName) ?: resolved
    }

    private suspend fun requestDetailRecord(
        packageName: String,
        installed: InstalledPackage? = null,
    ): HonorAppRecord {
        val profile = profiles.load(AppSource.HONOR)
        val root = protocol.post(
            DETAIL_PATH,
            profile,
            honorDetailPayload(packageName, installed),
        )
        val value = root.obj("data")
            ?.takeIf { it.str("pName").equals(packageName, ignoreCase = true) }
            ?: throw MarketException("荣耀未收录该应用")
        return HonorAppRecord(value).also { remember(listOf(it)) }
    }

    private suspend fun sharedDetailRecord(packageName: String): HonorAppRecord {
        val profile = profiles.load(AppSource.HONOR)
        val link = shareLink(packageName, profile)
        val shareId = runCatching { Url(link).parameters["shareId"] }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: throw MarketException("荣耀未提供有效的应用详情链接")
        val root = protocol.postUnsigned(
            HONOR_H5_DETAIL_URL,
            buildJsonObject {
                put("shareId", shareId)
                put("shareTo", "")
                putJsonObject("tInfo") {
                    put("hman", profile.hman.ifBlank { "HONOR" })
                    put("htype", profile.htype)
                    put("osVer", profile.osVer.ifBlank { profile.os })
                    put("os", 2)
                    put("terminalType", profile.terminalType.toIntOrNull()?.takeIf { it in 1..3 } ?: 1)
                    put("resolution", profile.resolution.replace('*', '_'))
                    put("isH5", 1)
                    put("honorMarketInstalled", -1)
                    put("apkVer", HONOR_H5_APK_VERSION)
                }
            },
            mapOf(
                "apkVer" to HONOR_H5_APK_VERSION,
                "areaId" to "CN",
                "traceId" to randomTraceId(HONOR_H5_TRACE_LENGTH),
            ),
        )
        val value = root.obj("data")
            ?.takeIf { it.str("pName").equals(packageName, ignoreCase = true) }
            ?: throw MarketException("荣耀分享详情与请求应用不一致")
        return HonorAppRecord(value).also { remember(listOf(it)) }
    }

    private suspend fun shareLink(packageName: String, profile: MarketProfile): String =
        protocol.post(
            SHARE_LINK_PATH,
            profile,
            buildJsonObject {
                put("packageName", packageName)
                put("shareFrom", "appmarket")
            },
        ).obj("data")?.str("link").orEmpty()

    suspend fun cachedRecord(packageName: String): HonorAppRecord? =
        cacheMutex.withLock { recordsByPackage[packageName.lowercase()] }

    suspend fun downloadableRecord(
        app: MarketAppInfo,
        seeds: List<HonorAppRecord> = emptyList(),
        installed: InstalledPackage? = null,
    ): HonorAppRecord {
        val packageName = app.packageName
        if (packageName.isBlank()) throw MarketException("荣耀应用包名为空")
        val candidates = buildList {
            addAll(seeds.filter { it.packageName.equals(packageName, ignoreCase = true) })
            cachedRecord(packageName)?.let(::add)
        }.toMutableList()
        var resolved = candidates.honorRecordForPackage(packageName)
        if (resolved?.hasDownloadPayload() == true) return resolved

        runCatching { detailRecord(packageName) }
            .onFailure { error -> debugLog("HonorApi") { "download detail fallback failed: ${error.message}" } }
            .getOrNull()
            ?.let(candidates::add)
        resolved = candidates.honorRecordForPackage(packageName)
        if (resolved?.hasDownloadPayload() == true) return resolved

        if (installed != null) {
            runCatching { requestDetailRecord(packageName, installed = installed) }
                .onFailure { error -> debugLog("HonorApi") { "download installed detail failed: ${error.message}" } }
                .getOrNull()
                ?.let(candidates::add)
            resolved = candidates.honorRecordForPackage(packageName)
            if (resolved?.hasDownloadPayload() == true) {
                remember(listOf(resolved))
                return resolved
            }
        }

        runCatching { requestDownloadRecord(packageName, installed) }
            .onFailure { error -> debugLog("HonorApi") { "download detail endpoint failed: ${error.message}" } }
            .getOrNull()
            ?.let(candidates::add)
        resolved = candidates.honorRecordForPackage(packageName)
        if (resolved?.hasDownloadPayload() == true) {
            remember(listOf(resolved))
            return resolved
        }

        val keywords = listOf(app.displayName.trim(), packageName)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
        for (keyword in keywords) {
            val searched = runCatching { exactSearchRecord(keyword, packageName) }
                .onFailure { error -> debugLog("HonorApi") { "download search fallback failed: ${error.message}" } }
                .getOrNull()
            if (searched != null) candidates += searched
            resolved = candidates.honorRecordForPackage(packageName)
            if (resolved?.hasDownloadPayload() == true) {
                remember(listOf(resolved))
                return resolved
            }
        }
        throw MarketException("荣耀未提供可下载的 APK")
    }

    private suspend fun requestDownloadRecord(
        packageName: String,
        installed: InstalledPackage?,
    ): HonorAppRecord? {
        val profile = profiles.load(AppSource.HONOR)
        val records = protocol.post(
            DOWNLOAD_DETAIL_PATH,
            profile,
            buildJsonObject {
                putJsonArray("pNames") { add(packageName) }
                if (installed != null) {
                    putJsonArray("installedPkgInfos") {
                        addJsonObject {
                            put("pkgName", installed.packageName)
                            put("ver", installed.versionCode)
                            put("identifier", installed.oldApkHash.takeIf(String::isHonorSha256).orEmpty())
                            putJsonArray("bundleList") {}
                        }
                    }
                }
            },
        ).obj("data")?.arr("apps").honorApps()
        remember(records)
        return records.honorRecordForPackage(packageName)
    }

    private suspend fun exactSearchRecord(keyword: String, packageName: String): HonorAppRecord? {
        val profile = profiles.load(AppSource.HONOR)
        val searchTimes = prepareSearchSession(profile)
        val records = mergeHonorSearchSources(
            appList = {
                protocol.post(
                    SEARCH_APP_LIST_PATH,
                    profile,
                    buildJsonObject {
                        put("key", keyword)
                        put("pSize", SEARCH_PAGE_SIZE)
                        put("sellingPointFlag", false)
                        put("specialSellingPointFlag", false)
                        put("start", 0)
                        put("tabKeyword", "")
                    },
                ).obj("data")?.arr("appLst").honorApps()
            },
            merged = {
                mergedSearchApps(
                    profile,
                    keyword,
                    0,
                    SEARCH_PAGE_SIZE,
                    searchTimes,
                ).honorApps()
            },
            onFailure = { source, error ->
                debugLog("HonorApi") { "download $source search source failed: ${error.message}" }
            },
        )
        remember(records)
        debugLog("HonorApi") {
            "download search keywordLength=${keyword.length} returned=${records.size} " +
                    "exact=${records.count { it.packageName.equals(packageName, ignoreCase = true) }}"
        }
        return records
            .honorRecordForPackage(packageName)
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        return downloadMeta(downloadableRecord(app), app)
    }

    suspend fun downloadMeta(
        record: HonorAppRecord,
        app: MarketAppInfo,
        purpose: HonorDownloadPurpose = HonorDownloadPurpose.ACTIVE,
    ): DownloadMeta {
        val value = record.value
        val rawParts = value.arr("apks")
        val candidates = if (rawParts.len > 0) rawParts else buildJsonArray { add(value) }
        val parts = buildList {
            for (index in 0 until candidates.len) {
                val part = candidates.objAt(index) ?: continue
                val bundleType = part.str("bundle_type")
                val bundleValue = part.str("bundle_value")
                val isBase = (index == 0 && bundleType.isBlank()) || bundleType.equals("base", true)
                val directUrl = part.str("downUrl").ifBlank { if (isBase) value.str("downUrl") else "" }
                val metaPath = part.str("downloadUrlMetaPath")
                    .ifBlank { if (isBase) value.str("downloadUrlMetaPath") else "" }
                val size = part.long("fileSize", if (isBase) value.long("fileSize") else 0L)
                val url = resolveDownloadUrl(metaPath, directUrl, record.packageName, size, purpose)
                if (url.isBlank()) continue
                val targetHash = if (isBase) value.str("apkIdentifier").validSha256OrEmpty() else ""
                add(
                    DownloadPart(
                        name = if (isBase) "" else bundleValue.ifBlank { "split_$index" },
                        type = if (isBase) "base" else bundleType.ifBlank { "split" },
                        url = url,
                        size = size,
                        hash = targetHash,
                        patch = if (isBase) resolvePatch(record, app, purpose) else null,
                    )
                )
            }
        }
        if (parts.isEmpty()) throw MarketException("荣耀未提供可下载的 APK")
        val primary = parts.firstOrNull { it.type.equals("base", true) } ?: parts.first()
        return DownloadMeta(
            appId = record.appId,
            packageName = record.packageName,
            displayName = value.str("name", app.displayName),
            versionName = record.versionName.ifBlank { app.versionName },
            versionCode = record.versionCode.takeIf { it > 0L } ?: app.versionCode,
            url = primary.url,
            size = parts.sumOf(DownloadPart::size).takeIf { it > 0L } ?: value.long("fileSize"),
            parts = parts,
            installedBaseApkPath = app.installedBaseApkPath,
            icon = honorImageUrl(value.str("imgUrl", app.icon)),
            changeLog = value.str("verUptDes", app.changeLog),
            source = AppSource.HONOR,
        )
    }

    suspend fun updateConfig(): HonorUpdateConfig {
        val profile = profiles.load(AppSource.HONOR)
        val root = protocol.post(
            UPDATE_CONFIG_PATH,
            profile,
            buildJsonObject { put("userType", -1) },
        )
        val data = root.obj("data") ?: JsonObject(emptyMap())
        return HonorUpdateConfig(
            maxBatchSize = data.obj("recommendUpdate")
                ?.int("appUpdateReqMaxNum", DEFAULT_UPDATE_BATCH)
                ?.coerceIn(1, MAX_UPDATE_BATCH)
                ?: DEFAULT_UPDATE_BATCH,
            refreshIntervalSeconds = data.long("refreshInterval", DEFAULT_CONFIG_REFRESH_SECONDS),
            checkIntervalSeconds = data.long("checkInterval", DEFAULT_CHECK_INTERVAL_SECONDS),
            systemPolicies = parseHonorUpdatePolicies(data),
        )
    }

    suspend fun updates(
        installed: List<InstalledPackage>,
        policies: Map<String, HonorSystemUpdatePolicy>,
    ): List<HonorAppRecord> = requestUpdates(
        installed = installed,
        policies = policies,
        background = false,
    )

    suspend fun silentUpdates(
        installed: List<InstalledPackage>,
        policies: Map<String, HonorSystemUpdatePolicy>,
    ): List<HonorAppRecord> = requestUpdates(
        installed = installed,
        policies = policies,
        background = true,
    )

    private suspend fun requestUpdates(
        installed: List<InstalledPackage>,
        policies: Map<String, HonorSystemUpdatePolicy>,
        background: Boolean,
    ): List<HonorAppRecord> {
        if (installed.isEmpty()) return emptyList()
        val profile = profiles.load(AppSource.HONOR)
        val payload = buildJsonObject {
            put("fr", "ry000")
            putJsonArray("appLst") {
                installed.forEach { item ->
                    addJsonObject {
                        put("pName", item.packageName)
                        put("ver", item.versionCode)
                        put("versionName", item.versionName)
                        put("sha256", item.signerSha256)
                        putJsonArray("sha256List") {
                            item.signerSha256List.forEach { add(it) }
                        }
                        put("identifier", item.oldApkHash.takeIf(String::isHonorSha256) ?: EMPTY_SHA256)
                        put("installSourcePkg", item.installOrigin)
                        putJsonArray("resourceIdentifierList") {}
                        putJsonArray("bundleList") {}
                    }
                }
            }
        }
        val path = if (background) SILENT_UPDATE_PATH else UPDATE_PATH
        val root = if (background) {
            protocol.postBackground(path, profile, payload)
        } else {
            protocol.post(path, profile, payload)
        }
        val records = buildList {
            val values = root.arr("appLst")
            for (index in 0 until values.len) {
                val value = values.objAt(index) ?: continue
                val packageName = value.str("pName")
                if (packageName.isBlank()) continue
                val policy = policies[packageName.lowercase()]
                add(
                    HonorAppRecord(
                        value = value,
                        displayOnUpdatePage = policy?.displayOnUpdatePage ?: true,
                        priorityUpdate = policy?.priorityUpdate ?: false,
                    )
                )
            }
        }
        remember(records)
        debugLog("HonorApi") {
            "${if (background) "silentUpdates" else "updates"} requested=${installed.size} " +
                    "returned=${records.size} " +
                    "packages=${records.joinToString(",") { "${it.packageName}:${it.versionCode}" }}"
        }
        return records
    }

    private suspend fun resolvePatch(
        record: HonorAppRecord,
        app: MarketAppInfo,
        purpose: HonorDownloadPurpose,
    ): DownloadPatch? {
        val value = record.value
        val diff = value.obj("diffApk") ?: return null
        if (!app.installedOldApkHash.isHonorSha256() || app.installedBaseApkPath.isBlank()) return null
        if (app.installedSplits.isNotBlank() && app.installedSplits != "0") return null
        val patchSize = diff.long("fileSize")
        val fullSize = value.long("fileSize")
        val patchHash = diff.str("fileIdentifier").validSha256OrEmpty()
        if (patchSize <= 0L || patchHash.isBlank() || fullSize > 0L && patchSize >= fullSize) return null
        val requestIdentifier = value.str("requestIdentifier")
        if (requestIdentifier.isHonorSha256() && !requestIdentifier.equals(app.installedOldApkHash, true)) return null
        val type = diff.str("diffType")
        val direct = diff.str("downUrl")
        val metaPath = diff.str("downloadUrlMetaPath")
        val isSfPatch = type.equals("diffx", true) || direct.contains("diffx.patch", true) ||
                metaPath.contains("diffx.patch", true)
        if (!isSfPatch) return null
        val url = resolveDownloadUrl(metaPath, direct, record.packageName, patchSize, purpose)
        if (url.isBlank()) return null
        // Honor diffx is an SFPat21 container handled by AppMarket's existing HDiffCore path.
        return DownloadPatch(
            url = url,
            size = patchSize,
            hash = patchHash,
            version = 3,
            oldApkHash = app.installedOldApkHash,
            protocol = DownloadPatchProtocol.SFPATCH,
        )
    }

    private suspend fun resolveDownloadUrl(
        metaPath: String,
        directUrl: String,
        packageName: String,
        size: Long,
        purpose: HonorDownloadPurpose,
    ): String {
        if (metaPath.isNotBlank()) {
            val profile = profiles.load(AppSource.HONOR)
            val resolved = runCatching {
                protocol.post(
                    DISPATCH_PATH,
                    profile,
                    buildJsonObject {
                        put("biz", purpose.dispatchBiz)
                        putJsonObject("downloadObject") {
                            put("name", packageName)
                            put("path", metaPath)
                            put("size", size)
                        }
                        putJsonObject("deviceInfo") {
                            put("deviceId", "")
                            put("ip", "127.0.0.1")
                            put("accept", "http")
                        }
                        putJsonObject("scene") { put("code", "") }
                    },
                ).dispatchHttpsUrls()
                    .firstOrNull { protocol.isUsableHttpsUrl(it) }
                    .orEmpty()
            }.getOrDefault("")
            if (resolved.startsWith("https://", true)) return resolved
        }
        return directUrl.takeIf { it.startsWith("https://", true) }.orEmpty()
    }

    private suspend fun remember(records: List<HonorAppRecord>) = cacheMutex.withLock {
        records.forEach { record ->
            if (record.packageName.isBlank()) return@forEach
            val key = record.packageName.lowercase()
            val current = recordsByPackage[key]
            recordsByPackage[key] = current?.mergeWith(record) ?: record
        }
        while (recordsByPackage.size > MAX_RECORD_CACHE) {
            recordsByPackage.remove(recordsByPackage.keys.first())
        }
    }
}

internal enum class HonorDownloadPurpose(internal val dispatchBiz: String) {
    ACTIVE("AppmarketActiveDownload"),
    UPDATE("AppmarketUpdateDownload"),
}

private fun JsonObject.flag(name: String, default: Boolean): Boolean {
    val value = this[name] as? JsonPrimitive ?: return default
    return value.booleanOrNull
        ?: value.intOrNull?.let { it != 0 }
        ?: when (value.contentOrNull?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }
}

internal fun parseHonorUpdatePolicies(data: JsonObject): Map<String, HonorSystemUpdatePolicy> = buildMap {
    val defaultPolicy = HonorSystemUpdatePolicy(
        displayOnUpdatePage = true,
        priorityUpdate = false,
    )
    listOf("systemAppWhiteList", "updateAppWhiteList").forEach { field ->
        data.str(field)
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { packageName -> put(packageName.lowercase(), defaultPolicy) }
    }
    val values = data.arr("newUpdateAppWhiteList")
    for (index in 0 until values.len) {
        val item = values.objAt(index) ?: continue
        val packageName = item.str("packageName").trim().lowercase()
        if (packageName.isBlank()) continue
        put(
            packageName,
            HonorSystemUpdatePolicy(
                displayOnUpdatePage = item.flag("displayUpdatePageFlag", true),
                priorityUpdate = item.flag("priorityUpdateFlag", false),
            ),
        )
    }
}

internal fun exactHonorAssociationApps(root: JsonObject, keyword: String): List<HonorAppRecord> {
    val normalized = keyword.trim().lowercase()
    if (normalized.isBlank()) return emptyList()
    val records = buildList {
        val assemblies = root.obj("data")?.arr("assemblyVOList")
        for (assemblyIndex in 0 until assemblies.len) {
            val apps = assemblies.objAt(assemblyIndex)?.arr("appList")
            for (appIndex in 0 until apps.len) {
                val value = apps.objAt(appIndex) ?: continue
                val packageName = value.str("pName")
                val displayName = value.str("name")
                if (packageName.isBlank()) continue
                if (packageName.lowercase() == normalized || displayName.trim().lowercase() == normalized) {
                    add(HonorAppRecord(value))
                }
            }
        }
    }
    return mergeHonorRecords(records)
}

private fun String.validSha256OrEmpty(): String = trim().lowercase().takeIf(String::isHonorSha256).orEmpty()

/** Exact GetApkDetailReq wire names used by the official client. */
internal fun honorDetailPayload(
    packageName: String,
    installed: InstalledPackage?,
): JsonObject = buildJsonObject {
    put("adIntegrationScene", 0)
    put("aiRecommendType", 0)
    put("applyId", 0)
    put("isAd", false)
    put("isInternal", 0)
    put("needGameNodeList", false)
    put("needGift", false)
    put("orderSceneFlag", 0)
    put("orderType", 0)
    put("pName", packageName)
    put("pkgChannel", -1)
    put("redirectNoAvailableAppLandingPage", 0)
    put("resId", 0)
    put("resType", 1)
    put("subChannel", "")
    if (installed != null) {
        put("identifier", installed.oldApkHash.takeIf(String::isHonorSha256).orEmpty())
        put("ver", installed.versionCode)
        putJsonArray("bundleList") {}
    }
}

private fun String.looksLikePackageName(): Boolean =
    length in 3..255 && '.' in this && split('.').all { segment ->
        segment.isNotEmpty() && segment.first().let { it == '_' || it.isLetter() } &&
                segment.all { it == '_' || it.isLetterOrDigit() }
    }

internal fun String.isHonorSha256(): Boolean =
    length == 64 && all { it in "0123456789abcdefABCDEF" }

private fun JsonArray?.httpsUrls(): List<String> =
    orEmpty()
        .mapNotNull { it as? kotlinx.serialization.json.JsonPrimitive }
        .mapNotNull { it.content.takeIf { value -> value.startsWith("https://", true) } }

private fun JsonObject.dispatchHttpsUrls(): List<String> {
    val data = this["data"]
    return when (data) {
        is JsonObject -> data.arr("uriList").httpsUrls()
        is JsonArray -> data.mapNotNull { item ->
            (item as? JsonObject)?.str("link")?.takeIf { it.startsWith("https://", true) }
        }

        else -> emptyList()
    }
}

private fun randomTraceId(length: Int): String = buildString(length) {
    repeat(length) { append(TRACE_ALPHABET[Random.nextInt(TRACE_ALPHABET.length)]) }
}

private const val SEARCH_PAGE_SIZE = 16
private const val DEFAULT_UPDATE_BATCH = 200
private const val MAX_UPDATE_BATCH = 500
private const val DEFAULT_CONFIG_REFRESH_SECONDS = 21_600L
private const val DEFAULT_CHECK_INTERVAL_SECONDS = 1_800L
private const val MAX_RECORD_CACHE = 256
private const val MAX_EXACT_SEARCH_SUPPLEMENTS = 3
private const val EMPTY_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
private const val HONOR_H5_APK_VERSION = "160028301"
private const val HONOR_H5_TRACE_LENGTH = 64
private const val HONOR_H5_DETAIL_URL =
    "https://appmarket-distapi-drcn.hispace.hihonorcloud.com/distapi/market/h5/share/v1/detail/query"
private const val TRACE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

private const val SEARCH_ACTIVATION_PATH = "/api/market/search/v2/search/activation"
private const val SEARCH_MERGED_PATH = "/api/market/search/v1/search/result/page"
private const val SEARCH_APP_LIST_PATH = "/api/market/search/v2/app/list"
private const val SEARCH_ASSOCIATION_PATH = "/api/market/search/v4/association/words"
private const val DETAIL_PATH = "/api/market/app/v2/detail/byname/query"
private const val SHARE_LINK_PATH = "/api/market/share/v1/link/generate"
private const val DOWNLOAD_DETAIL_PATH = "/api/market/app/v3/detail/bynames/query"
private const val UPDATE_CONFIG_PATH = "/api/system/updater/v1/update-check/config"
private const val UPDATE_PATH = "/api/system/updater/v1/check/app/update"
private const val SILENT_UPDATE_PATH = "/api/system/updater/v1/check/app/slientupdate"
private const val DISPATCH_PATH = "/downloaddispatch/download/appmarket/getUriList"
