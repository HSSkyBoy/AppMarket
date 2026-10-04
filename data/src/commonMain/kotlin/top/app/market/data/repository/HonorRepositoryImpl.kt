package top.app.market.data.repository

import top.app.market.data.platform.debugLog
import top.app.market.data.remote.honor.HonorApi
import top.app.market.data.remote.honor.HonorAppRecord
import top.app.market.data.remote.honor.HonorDownloadPurpose
import top.app.market.data.remote.honor.HonorSystemUpdatePolicy
import top.app.market.data.remote.honor.HonorUpdateConfig
import top.app.market.data.remote.honor.isHonorSha256
import top.app.market.data.remote.honor.mergeHonorRecords
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult
import top.app.market.domain.model.update.ManualUpdateStatus
import top.app.market.domain.repository.HonorRepository
import top.app.market.domain.repository.InstalledApkHashRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal class HonorRepositoryImpl(
    private val api: HonorApi,
    private val installedPackages: InstalledPackagesRepository,
    private val installedApkHash: InstalledApkHashRepository,
    private val updatePreferences: UpdatePreferencesRepository,
) : HonorRepository {
    private val configMutex = Mutex()
    private var cachedConfig: HonorUpdateConfig? = null
    private var configLoadedAt = 0L

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        val pageResult = api.search(keyword, page)
        if (updatePreferences.currentRemoveSearchAds()) {
            pageResult.copy(items = pageResult.items.filterNot(MarketAppInfo::isAd))
        } else {
            pageResult
        }
    }

    override suspend fun appDetail(appId: Long, packageName: String): AppDetail =
        withContext(Dispatchers.Default) {
            val installed = runCatching { installedPackages.installedPackage(packageName) }.getOrNull()
            val prepared = installed?.withBaseSha256()
            api.detailRecord(packageName).toDetail(prepared)
        }

    override suspend fun downloadMeta(app: MarketAppInfo) = withContext(Dispatchers.Default) {
        val record = api.downloadableRecord(app)
        api.downloadMeta(record, app)
    }

    override suspend fun downloadUpdateMeta(app: MarketAppInfo) = withContext(Dispatchers.Default) {
        val local = installedPackages.installedPackage(app.packageName)?.withBaseSha256()
        val config = updateConfig()
        val record = if (local != null) {
            mergeHonorRecords(
                runCatching { api.silentUpdates(listOf(local), config.systemPolicies) }.getOrDefault(emptyList()) +
                        api.updates(listOf(local), config.systemPolicies),
            )
                .filter { it.packageName.equals(app.packageName, true) }
                .maxByOrNull(HonorAppRecord::versionCode)
        } else {
            null
        }
        val resolved = api.downloadableRecord(app, listOfNotNull(record), local)
        val appWithInstalled = if (local == null) app else app.copy(
            installedVersionName = local.versionName,
            installedVersionCode = local.versionCode,
            installedOldApkHash = local.oldApkHash,
            installedBaseApkPath = local.baseApkPath,
            installedSplits = local.splits,
            isSystemApp = local.isSystemApp,
        )
        api.downloadMeta(resolved, appWithInstalled, HonorDownloadPurpose.UPDATE)
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        val installed = installedPackages.installed()
        if (installed.isEmpty()) return@withContext emptyList()
        val config = updateConfig()
        val eligible = installed.filter { item ->
            item.versionCode > 0L && item.packageName.isNotBlank() &&
                    (!item.isSystemApp || item.packageName.lowercase() in config.systemPolicies)
        }
        // identifier is only needed when resolving the downloadable delta. An empty identity is
        // sufficient for update discovery and avoids hashing all eligible base APKs up front.
        val prepared = eligible.withoutOldApkHashes()
        val silentRecords = prepared.chunked(config.maxBatchSize).flatMap { batch ->
            runCatching { api.silentUpdates(batch, config.systemPolicies) }.getOrDefault(emptyList())
        }
        val foregroundRecords = prepared.chunked(config.maxBatchSize).flatMap { batch ->
            api.updates(batch, config.systemPolicies)
        }
        val batchRecords = mergeHonorRecords(silentRecords + foregroundRecords)
        val supplements = fetchDetailSupplements(
            honorDetailSupplementCandidates(prepared, config.systemPolicies, batchRecords),
            config.systemPolicies,
        )
        val records = mergeHonorRecords(batchRecords + supplements)
        val localByPackage = prepared.associateBy { it.packageName.lowercase() }
        val updates = records.asSequence()
            .filter(HonorAppRecord::displayOnUpdatePage)
            .filter { record ->
                val local = localByPackage[record.packageName.lowercase()] ?: return@filter false
                record.versionCode > local.versionCode && signaturesCompatible(local, record)
            }
            .groupBy { it.packageName.lowercase() }
            .mapNotNull { (packageName, values) ->
                val local = localByPackage[packageName] ?: return@mapNotNull null
                mergeHonorRecords(values).maxByOrNull(HonorAppRecord::versionCode)?.toApp(local)
            }
            .sortedWith(compareByDescending<MarketAppInfo> { it.type == HONOR_PRIORITY_TYPE }
                .thenBy { it.displayName })
            .toList()
        debugLog("HonorRepository") {
            val decisions = records.joinToString(",") { record ->
                val local = localByPackage[record.packageName.lowercase()]
                val state = when {
                    local == null -> "not-installed"
                    !record.displayOnUpdatePage -> "hidden"
                    record.versionCode <= local.versionCode -> "not-newer"
                    !signaturesCompatible(local, record) -> "signature-mismatch"
                    else -> "accepted"
                }
                "${record.packageName}:${local?.versionCode ?: 0}->${record.versionCode}:$state"
            }
            "update decisions silent=${silentRecords.size} foreground=${foregroundRecords.size} " +
                    "batch=${batchRecords.size} supplements=${supplements.size} " +
                    "accepted=${updates.size} records=[$decisions]"
        }
        updates
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult =
        withContext(Dispatchers.Default) {
            val installed = installedPackages.installedPackage(request.packageName)
            var local = (installed ?: InstalledPackage(
                packageName = request.packageName,
                versionCode = request.versionCode,
                versionName = request.versionName,
                isSystemApp = request.isSystemApp,
                oldApkHash = request.oldApkHash,
                splits = request.splits,
                apkSource = request.apkSource,
                installedBy = request.installedBy,
            )).copy(
                versionCode = request.versionCode,
                versionName = request.versionName.ifBlank { installed?.versionName.orEmpty() },
            ).withBaseSha256()
            val config = updateConfig()
            val packageKey = local.packageName.lowercase()
            val listed = runCatching { api.detailRecord(request.packageName) }.getOrNull()
            if (local.signerSha256List.isEmpty() && local.signerSha256.isBlank() && listed != null) {
                val signers = targetSigners(listed)
                local = local.copy(
                    signerSha256 = signers.firstOrNull().orEmpty(),
                    signerSha256List = signers,
                )
            }
            val batchLatest = mergeHonorRecords(
                runCatching { api.silentUpdates(listOf(local), config.systemPolicies) }.getOrDefault(emptyList()) +
                        api.updates(listOf(local), config.systemPolicies),
            )
                .filter { it.packageName.equals(request.packageName, true) }
                .filter { signaturesCompatible(local, it) }
                .maxByOrNull(HonorAppRecord::versionCode)
            val supplemental = if (batchLatest == null) {
                listed?.copy(
                    displayOnUpdatePage = config.systemPolicies[packageKey]?.displayOnUpdatePage ?: true,
                    priorityUpdate = config.systemPolicies[packageKey]?.priorityUpdate ?: false,
                )
            } else {
                null
            }
            val latest = mergeHonorRecords(listOfNotNull(batchLatest, supplemental))
                .maxByOrNull(HonorAppRecord::versionCode)
            if (latest != null && latest.versionCode > request.versionCode) {
                return@withContext ManualUpdateResult(
                    ManualUpdateStatus.UPDATE_AVAILABLE,
                    latest.toApp(local),
                )
            }
            val recognized = latest != null || listed != null ||
                    (local.isSystemApp && packageKey in config.systemPolicies)
            ManualUpdateResult(
                if (recognized) ManualUpdateStatus.RECOGNIZED_NO_UPDATE else ManualUpdateStatus.NOT_FOUND,
                latest?.toApp(local),
            )
        }

    private suspend fun updateConfig(): HonorUpdateConfig {
        val now = Clock.System.now().toEpochMilliseconds()
        val current = cachedConfig
        if (current != null && now - configLoadedAt < current.refreshIntervalSeconds * 1000L) return current
        return configMutex.withLock {
            val refreshedNow = Clock.System.now().toEpochMilliseconds()
            val cached = cachedConfig
            if (cached != null && refreshedNow - configLoadedAt < cached.refreshIntervalSeconds * 1000L) {
                return@withLock cached
            }
            runCatching { api.updateConfig() }.getOrElse { error -> cached ?: throw error }.also {
                cachedConfig = it
                configLoadedAt = refreshedNow
            }
        }
    }

    private suspend fun fetchDetailSupplements(
        candidates: List<InstalledPackage>,
        policies: Map<String, HonorSystemUpdatePolicy>,
    ): List<HonorAppRecord> {
        val slots = Semaphore(MAX_DETAIL_CONCURRENCY)
        return coroutineScope {
            candidates.map { local ->
                async {
                    slots.withPermit {
                        val policy = policies[local.packageName.lowercase()] ?: return@withPermit null
                        runCatching { api.detailRecord(local.packageName) }.getOrNull()?.copy(
                            displayOnUpdatePage = policy.displayOnUpdatePage,
                            priorityUpdate = policy.priorityUpdate,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun InstalledPackage.withBaseSha256(): InstalledPackage {
        if (oldApkHash.isHonorSha256()) return this
        if (baseApkPath.isBlank()) return copy(oldApkHash = "0")
        return copy(oldApkHash = installedApkHash.sha256(baseApkPath).lowercase().ifBlank { "0" })
    }

    private fun List<InstalledPackage>.withoutOldApkHashes(): List<InstalledPackage> =
        map { item -> if (item.oldApkHash == "0") item else item.copy(oldApkHash = "0") }

    private fun signaturesCompatible(local: InstalledPackage, record: HonorAppRecord): Boolean {
        val localSigners = (local.signerSha256List + local.signerSha256)
            .map(String::lowercase)
            .filter(String::isHonorSha256)
            .toSet()
        if (localSigners.isEmpty()) return true
        val target = targetSigners(record).toSet()
        return target.isEmpty() || target.any(localSigners::contains)
    }

    private fun targetSigners(record: HonorAppRecord): List<String> =
        (record.value.string("apkSignMultiple") + "," + record.value.string("apkSign"))
            .split(',')
            .map(String::trim)
            .map(String::lowercase)
            .filter(String::isHonorSha256)
            .distinct()

    private companion object {
        const val MAX_DETAIL_CONCURRENCY = 3
        const val HONOR_PRIORITY_TYPE = "honorPriorityUpdate"
    }
}

internal fun honorDetailSupplementCandidates(
    installed: List<InstalledPackage>,
    policies: Map<String, HonorSystemUpdatePolicy>,
    batchRecords: List<HonorAppRecord>,
): List<InstalledPackage> {
    val returnedPackages = batchRecords.mapTo(hashSetOf()) { it.packageName.lowercase() }
    return installed.filter { local ->
        val packageName = local.packageName.lowercase()
        packageName in policies && packageName !in returnedPackages
    }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
