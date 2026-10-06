package top.app.market.data.repository

import top.app.market.data.remote.huawei.HuaweiApi
import top.app.market.data.remote.huawei.HuaweiAppRecord
import top.app.market.data.remote.huawei.HuaweiUpdateRecord
import top.app.market.data.remote.huawei.isHuaweiSha256
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.download.DownloadPatch
import top.app.market.domain.model.download.DownloadPatchProtocol
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppScreenshot
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.ScreenshotOrientation
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult
import top.app.market.domain.model.update.ManualUpdateStatus
import top.app.market.domain.repository.HuaweiRepository
import top.app.market.domain.repository.InstalledApkHashRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.absoluteValue

internal class HuaweiRepositoryImpl(
    private val api: HuaweiApi,
    private val installedPackages: InstalledPackagesRepository,
    private val installedApkHash: InstalledApkHashRepository,
    private val updatePreferences: UpdatePreferencesRepository,
) : HuaweiRepository {
    private val recordsByPackage = mutableMapOf<String, HuaweiAppRecord>()
    private val recordsById = mutableMapOf<Long, HuaweiAppRecord>()
    private val updatesByPackage = mutableMapOf<String, HuaweiUpdateRecord>()

    override suspend fun search(keyword: String, page: Int): SearchPage {
        val (records, hasMore) = api.search(keyword, page)
        val items = records.map { record -> toApp(record).also { remember(it, record) } }
        return SearchPage(
            items = if (updatePreferences.currentRemoveSearchAds()) {
                items.filterNot(MarketAppInfo::isAd)
            } else {
                items
            },
            hasMore = hasMore,
        )
    }

    override suspend fun rankedApps(games: Boolean, page: Int): SearchPage {
        val (records, hasMore) = api.ranking(games, page)
        return SearchPage(
            items = records.map { record -> toApp(record).also { remember(it, record) } },
            hasMore = hasMore,
        )
    }

    override suspend fun appDetail(appId: Long, packageName: String): AppDetail {
        val resolvedPackage = packageName.ifBlank { api.packageNameOf(appId) }
        val detail = api.detail(resolvedPackage)
        val cached = recordsById[appId] ?: recordsByPackage[resolvedPackage.lowercase()]
        val record = detail.copy(
            downloadUrl = cached
                ?.takeIf { it.versionCode == detail.versionCode }
                ?.downloadUrl
                .orEmpty()
                .ifBlank { detail.downloadUrl },
        )
        val installed = installedPackages.installedPackage(resolvedPackage)
        val app = toApp(record).let { if (installed == null) it else it.withInstalled(installed) }
        remember(app, record)
        return AppDetail(
            app = app,
            brief = record.brief,
            introduction = record.description,
            changeLog = record.changeLog,
            category = record.category,
            ageClassification = record.minAge.takeIf { it > 0 }?.toString().orEmpty(),
            downloadCount = record.downloadCount,
            registrationNum = "",
            privacyUrl = "",
            screenshots = record.screenshots.map { url ->
                AppScreenshot(url, ScreenshotOrientation.PORTRAIT, url)
            },
            commentCount = record.commentCount,
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val cached = recordsByPackage[app.packageName.lowercase()]
        val detail = api.detail(app.packageName)
        val record = detail.copy(
            downloadUrl = cached
                ?.takeIf { it.versionCode == detail.versionCode }
                ?.downloadUrl
                .orEmpty()
                .ifBlank { detail.downloadUrl },
        )
        remember(toApp(record), record)
        return record.toDownloadMeta(app)
    }

    override suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta {
        val key = app.packageName.lowercase()
        val local = installedPackages.installedPackage(app.packageName)
            ?: app.toInstalledPackage().takeIf { it.versionCode > 0L }
            ?: throw MarketException("华为应用更新需要当前已安装版本")
        val prepared = prepareHashes(listOf(local)).single()
        // Update discovery deliberately omits fSha2 to avoid hashing every installed APK. Once the
        // user selects an update, query this package again with its hash so Huawei can disclose a
        // matching VCDIFF patch.
        val update = api.updates(listOf(prepared), manual = true).updates
            .firstOrNull {
                it.app.packageName.equals(app.packageName, true) && it.app.versionCode == app.versionCode
            }
            ?: updatesByPackage[key]?.takeIf { it.app.versionCode == app.versionCode }
            ?: throw MarketException("华为应用市场未返回该版本的更新包")
        updatesByPackage[key] = update
        return update.toDownloadMeta(app, prepared)
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> {
        val local = installedPackages.installed()
            .filter { it.packageName.isNotBlank() && it.versionCode > 0L }
        if (local.isEmpty()) return emptyList()
        // fSha2 is only required to negotiate a delta. Sending an empty hash still discovers full
        // updates and avoids a full read of every installed APK during each refresh.
        val prepared = local.withoutOldApkHashes()
        val byPackage = prepared.associateBy { it.packageName.lowercase() }
        val updates = prepared.chunked(UPDATE_BATCH_SIZE)
            .flatMap { api.updates(it, manual = false).updates }
        return updates.mapNotNull { update ->
            val installed = byPackage[update.app.packageName.lowercase()] ?: return@mapNotNull null
            if (update.app.versionCode <= installed.versionCode) return@mapNotNull null
            updatesByPackage[update.app.packageName.lowercase()] = update
            toApp(update.app).withInstalled(
                installed,
                delta = update.usablePatch(installed.oldApkHash)?.size ?: 0L,
            ).also { remember(it, update.app) }
        }.distinctBy { it.packageName.lowercase() }
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult {
        val actual = installedPackages.installedPackage(request.packageName)
        val local = actual ?: InstalledPackage(
            packageName = request.packageName,
            versionCode = request.versionCode,
            versionName = request.versionName,
            isSystemApp = request.isSystemApp,
            installedBy = request.installedBy,
            splits = request.splits,
            oldApkHash = request.oldApkHash,
            apkSource = request.apkSource,
        )
        val prepared = prepareHashes(listOf(local)).single()
        val response = api.updates(listOf(prepared), manual = true)
        val update = response.updates.firstOrNull { it.app.packageName.equals(request.packageName, true) }
        if (update != null) {
            val app = toApp(update.app).withInstalled(prepared)
            remember(app, update.app)
            updatesByPackage[request.packageName.lowercase()] = update
            return ManualUpdateResult(
                if (app.versionCode > request.versionCode) ManualUpdateStatus.UPDATE_AVAILABLE
                else ManualUpdateStatus.RECOGNIZED_NO_UPDATE,
                app,
            )
        }
        if (request.packageName.lowercase() in response.recognizedPackages) {
            return ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
        }
        val recognized = runCatching { api.detail(request.packageName) }.isSuccess
        return ManualUpdateResult(
            if (recognized) ManualUpdateStatus.RECOGNIZED_NO_UPDATE else ManualUpdateStatus.NOT_FOUND,
        )
    }

    private suspend fun prepareHashes(installed: List<InstalledPackage>): List<InstalledPackage> = coroutineScope {
        val semaphore = Semaphore(HASH_CONCURRENCY)
        installed.map { item ->
            async(Dispatchers.Default) {
                if (item.baseApkPath.isBlank()) return@async item.copy(oldApkHash = "0")
                val hash = semaphore.withPermit { installedApkHash.sha256(item.baseApkPath) }
                item.copy(oldApkHash = hash.takeIf(String::isHuaweiSha256) ?: "0")
            }
        }.awaitAll()
    }

    private fun List<InstalledPackage>.withoutOldApkHashes(): List<InstalledPackage> =
        map { item -> if (item.oldApkHash == "0") item else item.copy(oldApkHash = "0") }

    private fun toApp(record: HuaweiAppRecord): MarketAppInfo = MarketAppInfo(
        appId = huaweiNumericId(record.rawId.ifBlank { record.packageName }),
        packageName = record.packageName,
        displayName = record.name,
        publisherName = record.developer,
        versionName = record.versionName,
        versionCode = record.versionCode,
        icon = record.icon,
        apkSize = record.fullSize.takeIf { it > 0L } ?: record.size,
        ratingScore = record.rating,
        changeLog = record.changeLog,
        openLink = record.rawId,
        type = record.packingType.toString(),
        isAd = record.isAd,
        source = AppSource.HUAWEI,
        category = record.category,
        downloadCount = record.downloadCount,
    )

    private fun HuaweiAppRecord.toDownloadMeta(app: MarketAppInfo): DownloadMeta {
        validateFullPackage(downloadUrl, this)
        val targetSize = fullSize.takeIf { it > 0L } ?: size
        return DownloadMeta(
            appId = app.appId,
            packageName = packageName,
            displayName = name,
            versionName = versionName,
            versionCode = versionCode,
            url = downloadUrl,
            size = targetSize,
            parts = listOf(
                DownloadPart(
                    name = "",
                    type = "base",
                    url = downloadUrl,
                    size = targetSize,
                    hash = sha256,
                )
            ),
            icon = icon,
            changeLog = changeLog,
            source = AppSource.HUAWEI,
        )
    }

    private fun HuaweiUpdateRecord.toDownloadMeta(app: MarketAppInfo, local: InstalledPackage): DownloadMeta {
        val fullUrl = fullDownloadUrl.ifBlank {
            appRecordUrl().takeIf { it.startsWith("https://") && it != patchUrl }.orEmpty()
        }
        validateFullPackage(fullUrl, this.app)
        val targetSize = this.app.fullSize.takeIf { it > 0L } ?: this.app.size
        val patch = usablePatch(local.oldApkHash)
        return DownloadMeta(
            appId = app.appId,
            packageName = this.app.packageName,
            displayName = this.app.name,
            versionName = this.app.versionName,
            versionCode = this.app.versionCode,
            url = fullUrl,
            size = targetSize,
            parts = listOf(
                DownloadPart(
                    name = "",
                    type = "base",
                    url = fullUrl,
                    size = targetSize,
                    hash = this.app.sha256,
                    patch = patch,
                )
            ),
            installedBaseApkPath = local.baseApkPath,
            icon = this.app.icon,
            changeLog = this.app.changeLog,
            source = AppSource.HUAWEI,
        )
    }

    private fun HuaweiUpdateRecord.appRecordUrl(): String = app.downloadUrl

    /** Huawei diffType=0 is RFC 3284 VCDIFF and is synthesized by AppMarket itself. */
    private fun HuaweiUpdateRecord.usablePatch(oldApkHash: String = "0"): DownloadPatch? {
        if (!isDiff || patchType != HUAWEI_VCDIFF_TYPE) return null
        if (!patchUrl.startsWith("https://") || patchSize <= 0L) return null
        if (!patchSha256.isHuaweiSha256()) return null
        return DownloadPatch(
            url = patchUrl,
            size = patchSize,
            hash = patchSha256,
            version = HUAWEI_VCDIFF_PATCH_VERSION,
            oldApkHash = oldApkHash.takeIf(String::isHuaweiSha256) ?: "0",
            protocol = DownloadPatchProtocol.VCDIFF,
        )
    }

    private fun validateFullPackage(url: String, record: HuaweiAppRecord) {
        if (!url.startsWith("https://")) throw MarketException("华为应用市场未返回安全的完整包地址")
        if (record.disabled) throw MarketException("当前设备不支持该应用版本")
        if (record.packingType != 0 || record.bundleSize > 0L) {
            throw MarketException("当前版本是暂不支持的组合安装包")
        }
        val size = record.fullSize.takeIf { it > 0L } ?: record.size
        if (size <= 0L || !record.sha256.isHuaweiSha256()) {
            throw MarketException("华为应用市场返回的完整包校验信息不完整")
        }
    }

    private fun remember(app: MarketAppInfo, record: HuaweiAppRecord) {
        recordsByPackage[app.packageName.lowercase()] = record
        recordsById[app.appId] = record
    }

    private fun MarketAppInfo.withInstalled(local: InstalledPackage, delta: Long = 0L): MarketAppInfo = copy(
        installedVersionName = local.versionName,
        installedVersionCode = local.versionCode,
        deltaSize = delta,
        installedOldApkHash = local.oldApkHash,
        installedBaseApkPath = local.baseApkPath,
        installedSplits = local.splits,
        isSystemApp = local.isSystemApp,
    )

    private fun MarketAppInfo.toInstalledPackage(): InstalledPackage = InstalledPackage(
        packageName = packageName,
        versionCode = installedVersionCode,
        versionName = installedVersionName,
        isSystemApp = isSystemApp,
        splits = installedSplits,
        oldApkHash = installedOldApkHash,
        baseApkPath = installedBaseApkPath,
    )

    private fun huaweiNumericId(raw: String): Long {
        raw.substringBefore("__").substringBefore('?').removePrefix("app|").removePrefix("C")
            .toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        return raw.fold(1125899906842597L) { acc, char -> acc * 31 + char.code }
            .absoluteValue.coerceAtLeast(1L)
    }

    private companion object {
        const val UPDATE_BATCH_SIZE = 50
        const val HASH_CONCURRENCY = 3
        const val HUAWEI_VCDIFF_TYPE = 0
        const val HUAWEI_VCDIFF_PATCH_VERSION = 6
    }
}
