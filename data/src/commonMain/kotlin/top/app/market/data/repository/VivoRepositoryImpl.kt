package top.app.market.data.repository

import top.app.market.data.remote.vivo.VivoApi
import top.app.market.data.remote.vivo.VivoAuroraApi
import top.app.market.data.remote.vivo.VivoPackageSnapshot
import top.app.market.data.remote.vivo.VivoPatchDescriptor
import top.app.market.data.remote.vivo.VivoSelfUpdateEntry
import top.app.market.data.remote.vivo.VivoSupportEntry
import top.app.market.data.remote.vivo.VivoUpdateApi
import top.app.market.data.remote.vivo.VivoUpdateEntry
import top.app.market.data.remote.vivo.selectVivoPatch
import top.app.market.data.remote.vivo.vivoHttpsUrl
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.download.DownloadPatch
import top.app.market.domain.model.download.DownloadPatchProtocol
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult
import top.app.market.domain.model.update.ManualUpdateStatus
import top.app.market.domain.repository.InstalledApkHashRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.InstallerPreferencesRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import top.app.market.domain.repository.VivoRepository
import io.ktor.http.encodeURLParameter
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

internal class VivoRepositoryImpl(
    private val api: VivoApi,
    private val updateApi: VivoUpdateApi,
    private val auroraApi: VivoAuroraApi,
    private val installedPackages: InstalledPackagesRepository,
    private val installedApkHash: InstalledApkHashRepository,
    private val installerPreferences: InstallerPreferencesRepository,
    private val updatePreferences: UpdatePreferencesRepository,
) : VivoRepository {
    private val serverCatalogueMutex = Mutex()
    private var serverCatalogueKey = ""
    private var serverCatalogueAt = 0L
    private var serverCatalogue = emptyList<VivoPackageSnapshot>()

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        val native = api.search(keyword, page)
        val hidden = if (page == 0) searchServerCatalogue(keyword) else emptyList()
        val items = mergeVivoSearchItems(
            native = native.items,
            serverCatalogue = hidden,
            removeAds = updatePreferences.currentRemoveSearchAds(),
        )
        native.copy(items = items)
    }

    /**
     * vivo's keyword endpoint deliberately excludes some built-in packages. The upgrade service
     * supplies the complete device-specific package set, and /port/package supplies names/details.
     * Both sets come from vivo; no local package prefix or package-name whitelist participates.
     */
    private suspend fun searchServerCatalogue(keyword: String): List<MarketAppInfo> {
        val normalized = keyword.trim()
        if (normalized.isBlank()) return emptyList()
        val support = runCatching { updateApi.supportPackages() }.getOrDefault(emptyList())
        if (support.isEmpty()) return emptyList()
        return serverCatalogue(support)
            .filter { it.matchesVivoKeyword(normalized) }
            .map { it.toApp() }
    }

    private suspend fun serverCatalogue(
        support: List<VivoSupportEntry>,
    ): List<VivoPackageSnapshot> {
        val now = Clock.System.now().toEpochMilliseconds()
        val key = support.sortedBy { it.packageName }.joinToString("\n") {
            "${it.packageName}:${it.versionCode}:${it.tabletVersionCode}"
        }
        if (key == serverCatalogueKey && now - serverCatalogueAt < SERVER_CATALOGUE_TTL_MS) {
            return serverCatalogue
        }
        return serverCatalogueMutex.withLock {
            val refreshedAt = Clock.System.now().toEpochMilliseconds()
            if (key == serverCatalogueKey && refreshedAt - serverCatalogueAt < SERVER_CATALOGUE_TTL_MS) {
                return@withLock serverCatalogue
            }
            val refreshed = api.packageSnapshots(support.map { it.packageName })
            if (refreshed.isNotEmpty()) {
                serverCatalogue = refreshed
                serverCatalogueKey = key
                serverCatalogueAt = refreshedAt
            }
            serverCatalogue
        }
    }

    override suspend fun appDetail(vivoAppId: Long, packageName: String): AppDetail = withContext(Dispatchers.Default) {
        if (isVivoSyntheticAppId(vivoAppId)) {
            val installed = installedPackages.installedPackage(packageName) ?: InstalledPackage(
                packageName = packageName,
                versionCode = 0L,
                versionName = "",
                isSystemApp = true,
                displayName = packageName,
            )
            val selfUpdate = updateApi.checkSelfUpdate(installed, manual = true)
            if (selfUpdate != null) return@withContext selfUpdate.toDetail(installed)
            val snapshot = api.packageSnapshot(packageName)
                ?: throw IllegalStateException("vivo 未返回该系统应用的详情")
            return@withContext snapshot.toDetail(installed)
        }
        val detail = runCatching { api.appDetail(vivoAppId) }.getOrElse { error ->
            val snapshot = api.packageSnapshot(packageName) ?: throw error
            val local = installedPackages.installedPackage(packageName) ?: InstalledPackage(
                packageName = packageName,
                versionCode = 0L,
                versionName = "",
                isSystemApp = true,
                displayName = snapshot.displayName,
            )
            return@withContext snapshot.toDetail(local)
        }
        val installed = runCatching { installedPackages.installedPackage(detail.app.packageName) }.getOrNull()
            ?: return@withContext detail
        val installedApp = detail.app.copy(
            installedVersionName = installed.versionName,
            installedVersionCode = installed.versionCode,
            installedOldApkHash = installed.oldApkHash,
            installedBaseApkPath = installed.baseApkPath,
            installedSplits = installed.splits,
            isSystemApp = installed.isSystemApp,
        )
        val selfUpdate = runCatching { updateApi.checkSelfUpdate(installed, manual = true) }.getOrNull()
        if (selfUpdate != null && selfUpdate.versionCode > installedApp.versionCode) {
            detail.copy(
                app = selfUpdate.toApp(installed, installedApp),
                brief = selfUpdate.updateDescription.ifBlank { detail.brief },
                changeLog = selfUpdate.updateDescription.ifBlank { detail.changeLog },
                updateTime = selfUpdate.updateTime.takeIf { it > 0L } ?: detail.updateTime,
            )
        } else {
            detail.copy(app = installedApp)
        }
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        when {
            app.isVivoSelfUpdate() -> selfDownloadMeta(app)
            app.isVivoPackageSnapshot() -> {
                val snapshot = api.packageSnapshot(app.packageName)
                    ?: throw IllegalStateException("vivo 未返回 ${app.displayName} 的下载信息")
                api.downloadMeta(snapshot, app)
            }

            else -> api.downloadMeta(app)
        }
    }

    override suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        if (app.isVivoSelfUpdate()) return@withContext selfDownloadMeta(app)
        val local = resolveInstalled(app)
        val entry = updateApi.checkManualUpdate(local)
            .filter { it.packageName == app.packageName && it.versionCode >= app.versionCode }
            .maxByOrNull(VivoUpdateEntry::versionCode)
            ?: VivoUpdateEntry(
                appId = app.appId,
                packageName = app.packageName,
                displayName = app.displayName,
                icon = app.icon,
                versionName = app.versionName,
                versionCode = app.versionCode,
                sizeKiB = app.apkSize / 1024L,
                md5 = "",
                updateDescription = app.changeLog,
                apkType = "NORMAL",
                patchs = "",
                sfPatchs = "",
                downloadUrl = "",
            )
        val target = entry.toApp(local)
        val full = api.downloadMeta(target)
        withPatch(full, entry, null)
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        val installed = installedPackages.installed()
        if (installed.isEmpty()) return@withContext emptyList()
        // optionpkg is only vivo's preferred silent-update list. It is not a complete system-app
        // catalogue, so filtering by it prevents legitimate packages from ever reaching the API.
        val eligible = vivoUpdateCandidates(installed)
        if (eligible.isEmpty()) return@withContext emptyList()
        coroutineScope {
            val regularDeferred = async { updateApi.checkUpdates(eligible) }
            // Do not gate the vivo server behind local package prefixes or system-app flags.
            val selfCandidates = eligible
            val selfDeferred = async { updateApi.checkSelfUpdates(selfCandidates) }
            val regularEntries = regularDeferred.await()
                .groupBy(VivoUpdateEntry::packageName)
                .mapValues { (_, values) -> values.maxByOrNull(VivoUpdateEntry::versionCode) }
            val discoveredSelfEntries = selfDeferred.await()
                .filter { entry ->
                    val local = selfCandidates.firstOrNull { it.packageName == entry.packageName }
                    local != null && entry.versionCode > local.versionCode
                }
            val slots = Semaphore(MAX_CONCURRENT_SELF_PATCH_QUERIES)
            val selfEntries = discoveredSelfEntries.mapNotNull { discovered ->
                val local = selfCandidates.firstOrNull { it.packageName == discovered.packageName }
                    ?: return@mapNotNull null
                async {
                    slots.withPermit {
                        val prepared = prepareHash(local)
                        val sha256 = selfPatchSha256(prepared)
                        val enriched = if (sha256.isBlank()) {
                            null
                        } else {
                            runCatching {
                                updateApi.checkSelfUpdate(prepared, manual = false, appSha256 = sha256)
                            }.getOrNull()?.takeIf {
                                it.packageName == discovered.packageName && it.versionCode >= discovered.versionCode
                            }
                        }
                        (enriched ?: discovered) to prepared
                    }
                }
            }.awaitAll()
            val catalog = if (selfEntries.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    val packages = selfEntries.map { (_, local) -> local }
                    updateApi.catalog(packages).associateBy(VivoUpdateEntry::packageName)
                }.getOrDefault(emptyMap())
            }
            val regularApps = eligible.mapNotNull { local ->
                val entry = regularEntries[local.packageName] ?: return@mapNotNull null
                if (entry.versionCode <= local.versionCode) return@mapNotNull null
                entry.toApp(local)
            }
            val selfApps = selfEntries.map { (entry, local) ->
                entry.toApp(local, catalog[entry.packageName]?.toApp(local))
            }
            mergeVivoUpdates(regularApps, selfApps)
        }
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult =
        withContext(Dispatchers.Default) {
            val local = runCatching { installedPackages.installedPackage(request.packageName) }.getOrNull()
            val installed = (local ?: InstalledPackage(
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
                versionName = request.versionName.ifBlank { local?.versionName.orEmpty() },
            )
            val selfUpdate = runCatching { updateApi.checkSelfUpdate(installed, manual = true) }.getOrNull()
            if (selfUpdate != null && selfUpdate.versionCode > request.versionCode) {
                val catalog = runCatching { updateApi.catalog(listOf(installed)) }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.packageName == request.packageName }
                    ?.toApp(installed)
                return@withContext ManualUpdateResult(
                    status = ManualUpdateStatus.UPDATE_AVAILABLE,
                    app = selfUpdate.toApp(installed, catalog),
                )
            }
            val candidates = updateApi.checkManualUpdate(installed)
                .filter { it.packageName == request.packageName }
            val catalog = if (candidates.isEmpty()) {
                updateApi.catalog(listOf(installed)).filter { it.packageName == request.packageName }
            } else {
                emptyList()
            }
            val latest = (candidates + catalog).maxByOrNull(VivoUpdateEntry::versionCode)
            if (latest != null && latest.versionCode > request.versionCode) {
                ManualUpdateResult(
                    status = ManualUpdateStatus.UPDATE_AVAILABLE,
                    app = latest.toApp(installed),
                )
            } else {
                val recognizedBySummary = if (latest == null) {
                    runCatching { updateApi.summary(listOf(request.packageName)) }
                        .getOrDefault(emptyList())
                        .any { it.packageName == request.packageName }
                } else {
                    false
                }
                ManualUpdateResult(
                    status = if (selfUpdate != null || latest != null || recognizedBySummary) {
                        ManualUpdateStatus.RECOGNIZED_NO_UPDATE
                    } else {
                        ManualUpdateStatus.NOT_FOUND
                    },
                )
            }
        }

    override suspend fun auroraFeed(page: Int, pageSize: Int): TodayFeedPage =
        withContext(Dispatchers.Default) { auroraApi.feed(page, pageSize) }

    override suspend fun auroraArticle(rId: String): TodayArticle =
        withContext(Dispatchers.Default) { auroraApi.article(rId) }

    private suspend fun resolveInstalled(app: MarketAppInfo): InstalledPackage =
        installedPackages.installedPackage(app.packageName) ?: InstalledPackage(
            packageName = app.packageName,
            versionCode = app.installedVersionCode,
            versionName = app.installedVersionName,
            isSystemApp = app.isSystemApp,
            oldApkHash = app.installedOldApkHash,
            splits = app.installedSplits,
            baseApkPath = app.installedBaseApkPath,
        )

    private suspend fun prepareHash(app: InstalledPackage): InstalledPackage {
        val deltaEnabled = installerPreferences.deltaUpdateEnabled()
        if (!deltaEnabled || app.splits != "0" || app.baseApkPath.isBlank()) return app.copy(oldApkHash = "0")
        if (app.oldApkHash.isVivoHash32()) return app
        val md5 = installedApkHash.md5(app.baseApkPath)
        return app.copy(oldApkHash = vivoHash32FromMd5(md5)?.toString() ?: "0")
    }

    private suspend fun selfPatchSha256(app: InstalledPackage): String {
        if (!app.oldApkHash.isVivoHash32() || app.splits != "0" || app.baseApkPath.isBlank()) return ""
        return installedApkHash.sha256(app.baseApkPath).trim().lowercase()
            .takeIf(String::isSha256Hex)
            .orEmpty()
    }

    private suspend fun selfDownloadMeta(app: MarketAppInfo): DownloadMeta {
        val local = resolveInstalled(app)
        val prepared = prepareHash(local)
        val sha256 = selfPatchSha256(prepared)
        val entry = updateApi.checkSelfUpdate(prepared, manual = true, appSha256 = sha256)
            ?.takeIf { it.packageName == app.packageName && it.versionCode >= app.versionCode }
            ?: throw IllegalStateException("vivo 未返回 ${app.displayName} 的系统更新下载信息")
        val hash = entry.sha256.ifBlank { entry.md5 }
        val patchCandidate = vivoSelfPatch(entry, prepared)
        val (fullUrl, patch) = coroutineScope {
            val fullDeferred = async { updateApi.resolveSecureDownloadUrl(entry.downloadUrl) }
            val patchDeferred = async {
                patchCandidate?.let { candidate ->
                    updateApi.resolveSecureDownloadUrl(candidate.url)?.let { candidate.copy(url = it) }
                }
            }
            (fullDeferred.await() ?: entry.downloadUrl) to patchDeferred.await()
        }
        return DownloadMeta(
            appId = app.appId,
            packageName = entry.packageName,
            displayName = app.displayName,
            versionName = entry.versionName,
            versionCode = entry.versionCode,
            url = fullUrl,
            size = entry.size,
            parts = listOf(DownloadPart("", "base", fullUrl, entry.size, hash, patch)),
            installedBaseApkPath = prepared.baseApkPath,
            icon = app.icon,
            changeLog = entry.updateDescription,
            source = AppSource.VIVO,
        )
    }

    private fun withPatch(
        full: DownloadMeta,
        entry: VivoUpdateEntry,
        patch: DownloadPatch?,
    ): DownloadMeta {
        val part = full.parts.firstOrNull() ?: DownloadPart("", "base", full.url, full.size)
        return full.copy(
            displayName = entry.displayName.ifBlank { full.displayName },
            versionName = entry.versionName.ifBlank { full.versionName },
            versionCode = entry.versionCode.takeIf { it > 0L } ?: full.versionCode,
            size = full.size,
            icon = entry.icon.ifBlank { full.icon },
            changeLog = entry.updateDescription,
            parts = listOf(
                part.copy(
                    size = full.size,
                    hash = entry.md5.ifBlank { part.hash },
                    patch = patch,
                )
            ),
            source = AppSource.VIVO,
        )
    }

    private fun VivoUpdateEntry.toApp(
        local: InstalledPackage,
        patch: DownloadPatch? = null,
    ): MarketAppInfo = MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = displayName.ifBlank { packageName },
        publisherName = "",
        versionName = versionName,
        versionCode = versionCode,
        icon = icon,
        apkSize = sizeKiB.takeIf { it > 0L }?.times(1024L) ?: 0L,
        deltaSize = patch?.size ?: 0L,
        ratingScore = 0.0,
        changeLog = updateDescription,
        isSystemApp = local.isSystemApp,
        installedVersionName = local.versionName,
        installedVersionCode = local.versionCode,
        installedOldApkHash = local.oldApkHash,
        installedBaseApkPath = local.baseApkPath,
        installedSplits = local.splits,
        openLink = vivoHttpsUrl(downloadUrl),
        source = AppSource.VIVO,
    )

    private fun VivoSelfUpdateEntry.toApp(
        local: InstalledPackage,
        fallback: MarketAppInfo? = null,
    ): MarketAppInfo = MarketAppInfo(
        appId = fallback?.appId?.takeIf { it > 0L } ?: vivoSyntheticAppId(packageName),
        packageName = packageName,
        displayName = local.displayName.ifBlank {
            fallback?.displayName?.takeIf(String::isNotBlank) ?: packageName
        },
        publisherName = fallback?.publisherName.orEmpty(),
        versionName = versionName,
        versionCode = versionCode,
        icon = fallback?.icon.orEmpty(),
        apkSize = size,
        deltaSize = vivoSelfPatch(this, local)?.size ?: 0L,
        ratingScore = fallback?.ratingScore ?: 0.0,
        changeLog = updateDescription,
        isSystemApp = local.isSystemApp,
        installedVersionName = local.versionName,
        installedVersionCode = local.versionCode,
        installedOldApkHash = local.oldApkHash,
        installedBaseApkPath = local.baseApkPath,
        installedSplits = local.splits,
        openLink = downloadUrl,
        type = VIVO_SELF_UPDATE_TYPE,
        source = AppSource.VIVO,
    )

    private fun VivoSelfUpdateEntry.toDetail(local: InstalledPackage): AppDetail {
        val app = toApp(local)
        return AppDetail(
            app = app,
            brief = updateDescription,
            introduction = updateDescription,
            changeLog = updateDescription,
            category = "系统应用",
            ageClassification = "",
            downloadCount = 0L,
            registrationNum = "",
            updateTime = updateTime,
            privacyUrl = "",
            screenshots = emptyList(),
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    private fun VivoPackageSnapshot.toApp(local: InstalledPackage): MarketAppInfo = MarketAppInfo(
        appId = appId.takeIf { it > 0L } ?: vivoSyntheticAppId(packageName),
        packageName = packageName,
        displayName = displayName.ifBlank { local.displayName.ifBlank { packageName } },
        publisherName = publisherName,
        versionName = versionName.ifBlank { local.versionName },
        versionCode = versionCode.takeIf { it > 0L } ?: local.versionCode,
        icon = icon,
        apkSize = sizeKiB.takeIf { it > 0L }?.times(1024L) ?: 0L,
        ratingScore = score,
        isSystemApp = local.isSystemApp,
        installedVersionName = local.versionName,
        installedVersionCode = local.versionCode,
        installedOldApkHash = local.oldApkHash,
        installedBaseApkPath = local.baseApkPath,
        installedSplits = local.splits,
        openLink = downloadUrl,
        type = VIVO_PACKAGE_SNAPSHOT_TYPE,
        isAd = isAd,
        source = AppSource.VIVO,
        downloadCount = downloadCount,
    )

    private fun VivoPackageSnapshot.toApp(): MarketAppInfo = MarketAppInfo(
        appId = appId.takeIf { it > 0L } ?: vivoSyntheticAppId(packageName),
        packageName = packageName,
        displayName = displayName.ifBlank { packageName },
        publisherName = publisherName,
        versionName = versionName,
        // The support version may not have a downloadable release for this profile. The native
        // package response remains the source of version/download truth.
        versionCode = versionCode,
        icon = icon,
        apkSize = sizeKiB.takeIf { it > 0L }?.times(1024L) ?: 0L,
        ratingScore = score,
        openLink = downloadUrl,
        type = VIVO_PACKAGE_SNAPSHOT_TYPE,
        isAd = isAd,
        source = AppSource.VIVO,
        downloadCount = downloadCount,
    )

    private fun VivoPackageSnapshot.toDetail(local: InstalledPackage): AppDetail = AppDetail(
        app = toApp(local),
        brief = introduction.lineSequence().firstOrNull().orEmpty(),
        introduction = introduction,
        changeLog = "",
        category = "系统应用",
        ageClassification = "",
        downloadCount = downloadCount,
        registrationNum = "",
        updateTime = uploadTime,
        privacyUrl = "",
        screenshots = screenshots.map { url ->
            top.app.market.domain.model.market.AppScreenshot(
                url = url,
                orientation = top.app.market.domain.model.market.ScreenshotOrientation.PORTRAIT,
                expandedUrl = url,
            )
        },
        commentCount = commentCount,
        comments = emptyList(),
        sameDeveloperApps = emptyList(),
    )


    private companion object {
        const val MAX_CONCURRENT_SELF_PATCH_QUERIES = 3
        const val SERVER_CATALOGUE_TTL_MS = 30L * 60L * 1000L
    }
}

internal fun String.isVivoHash32(): Boolean = toLongOrNull()?.let { it > 0L } == true

internal fun VivoPackageSnapshot.matchesVivoKeyword(keyword: String): Boolean {
    val normalized = keyword.trim()
    if (normalized.isBlank()) return false
    return packageName.contains(normalized, ignoreCase = true) ||
            displayName.contains(normalized, ignoreCase = true)
}

/** vivo's optionpkg is not exhaustive; every installed package must be allowed onto the wire. */
internal fun vivoUpdateCandidates(installed: List<InstalledPackage>): List<InstalledPackage> = installed

internal fun vivoSelfUpdateCandidates(installed: List<InstalledPackage>): List<InstalledPackage> =
    installed.filter { it.versionCode > 0L }

internal fun mergeVivoUpdates(
    regular: List<MarketAppInfo>,
    selfUpdates: List<MarketAppInfo>,
): List<MarketAppInfo> {
    val merged = LinkedHashMap<String, MarketAppInfo>(regular.size + selfUpdates.size)
    (regular + selfUpdates).forEach { app ->
        val current = merged[app.packageName]
        if (current == null || app.versionCode > current.versionCode) merged[app.packageName] = app
    }
    return merged.values.toList()
}

/**
 * Put exact server-catalogue matches first, while retaining the native record when both
 * endpoints describe the same package so vivo's promotion label is not lost.
 */
internal fun mergeVivoSearchItems(
    native: List<MarketAppInfo>,
    serverCatalogue: List<MarketAppInfo>,
    removeAds: Boolean,
): List<MarketAppInfo> {
    val nativeByPackage = native.associateBy { it.packageName.lowercase() }
    var merged = serverCatalogue.map { nativeByPackage[it.packageName.lowercase()] ?: it } + native
    if (removeAds) merged = merged.filterNot(MarketAppInfo::isAd)
    return merged.distinctBy { it.packageName.lowercase() }
}

private const val VIVO_SYNTHETIC_APP_ID_BASE = 9_000_000_000L

internal fun vivoSyntheticAppId(packageName: String): Long =
    VIVO_SYNTHETIC_APP_ID_BASE + packageName.hashCode().toUInt().toLong()

internal fun isVivoSyntheticAppId(appId: Long): Boolean = appId >= VIVO_SYNTHETIC_APP_ID_BASE

private fun MarketAppInfo.isVivoSelfUpdate(): Boolean =
    type == VIVO_SELF_UPDATE_TYPE ||
            openLink.contains("appupgrade.vivo.com.cn/appDownload", ignoreCase = true)

private const val VIVO_SELF_UPDATE_TYPE = "vivoSelfUpdate"

private const val VIVO_PACKAGE_SNAPSHOT_TYPE = "vivoPackageSnapshot"

private fun MarketAppInfo.isVivoPackageSnapshot(): Boolean = type == VIVO_PACKAGE_SNAPSHOT_TYPE

internal fun vivoHash32FromMd5(md5: String): Long? {
    val normalized = md5.trim().lowercase()
    if (normalized.length != 32 || normalized.any { it !in "0123456789abcdef" }) return null
    val middle = normalized.substring(8, 24)
    val left = middle.substring(0, 8).toLongOrNull(16) ?: return null
    val right = middle.substring(8, 16).toLongOrNull(16) ?: return null
    return (left + right) and 0xffffffffL
}

/** Builds a directly downloadable SFPat21 patch; synthesis is performed by AppMarket's HDiffCore. */
internal fun vivoSelfPatch(
    entry: VivoSelfUpdateEntry,
    local: InstalledPackage,
): DownloadPatch? {
    val oldApkHash = local.oldApkHash.toLongOrNull()?.takeIf { it > 0L } ?: return null
    if (local.versionCode <= 0L || local.splits != "0" || local.baseApkPath.isBlank()) return null
    if (entry.patchSize <= 0L || (entry.size > 0L && entry.patchSize >= entry.size)) return null
    val descriptor = selectVivoPatch(
        raw = entry.patchDescriptor,
        targetVersion = entry.versionCode,
        baseVersion = local.versionCode,
        oldApkHash = oldApkHash,
    )?.takeIf { it.plan == SFPATCH_VERSION } ?: return null
    if (entry.downloadUrl.isBlank()) return null
    val separator = if ('?' in entry.downloadUrl) '&' else '?'
    val patchUrl = entry.downloadUrl + separator +
            "patchFullInfo=${descriptor.wireValue().encodeURLParameter()}"
    val patchChecksum = entry.patchSha256.takeIf(String::isSha256Hex)
        ?: entry.patchMd5.takeIf(String::isMd5Hex)
        ?: ""
    return DownloadPatch(
        url = patchUrl,
        size = entry.patchSize,
        hash = patchChecksum,
        version = descriptor.plan,
        oldApkHash = descriptor.oldApkHash.toString(),
        protocol = DownloadPatchProtocol.SFPATCH,
    )
}

private fun VivoPatchDescriptor.wireValue(): String =
    "v${plan}_${targetVersion}_${baseVersion}:${sizeKiB}:${patchHash}:${oldApkHash}"

private fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in "0123456789abcdef" }

private fun String.isMd5Hex(): Boolean =
    length == 32 && all { it in "0123456789abcdef" }

private const val SFPATCH_VERSION = 3
