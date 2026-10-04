package top.app.market.data.repository

import top.app.market.data.remote.samsung.SamsungApi
import top.app.market.data.remote.samsung.SamsungDownload
import top.app.market.data.remote.samsung.SamsungProduct
import top.app.market.data.remote.samsung.SamsungProductDetail
import top.app.market.data.remote.samsung.SamsungProductRef
import top.app.market.data.remote.samsung.samsungValue
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
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
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.SamsungRepository
import kotlin.math.absoluteValue

internal class SamsungRepositoryImpl(
    private val api: SamsungApi,
    private val installedPackages: InstalledPackagesRepository,
) : SamsungRepository {
    private val refsByPackage = mutableMapOf<String, SamsungProductRef>()
    private val refsById = mutableMapOf<Long, SamsungProductRef>()

    override suspend fun search(keyword: String, page: Int): SearchPage {
        if (keyword.isBlank()) return SearchPage(emptyList(), false)
        val products = api.search(keyword, page)
        val apps = products.map { product ->
            toApp(product).also { remember(it, product.ref) }
        }
        return SearchPage(apps, products.size >= PAGE_SIZE)
    }

    override suspend fun appDetail(
        appId: Long,
        packageName: String,
        externalQuery: String?,
    ): AppDetail {
        val parsed = externalQuery?.let(SamsungProductRef::parse)
        val ref = parsed ?: refsById[appId] ?: refsByPackage[packageName.lowercase()]
        ?: api.search(packageName, 0).firstOrNull { product ->
            product.ref.guid.equals(packageName, true) ||
                    product.ref.productId.toLongOrNull() == appId
        }?.ref
        ?: throw MarketException("Samsung Galaxy Store 未收录该应用")
        val detail = api.detail(ref)
        val app = toApp(detail)
        remember(app, detail.ref)
        return AppDetail(
            app = app,
            brief = detail.main.samsungValue("shortDescription", "briefDescription"),
            introduction = detail.overview.samsungValue("productDescription", "description"),
            changeLog = detail.overview.samsungValue("updateDescription"),
            category = detail.main.samsungValue("categoryName", "categoryPath"),
            ageClassification = detail.main.samsungValue("restrictedAge"),
            // Galaxy Store does not expose a trustworthy public lifetime download count.
            downloadCount = 0L,
            registrationNum = detail.overview.samsungValue("sellerRegisterNum", "sellerNum"),
            privacyUrl = detail.overview.samsungValue("sellerPrivatePolicy"),
            screenshots = screenshots(detail.overview),
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
            commentCount = detail.main.samsungValue("ratingParticipants").toLongOrNull() ?: 0L,
        )
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val installed = installedPackages.installedPackage(app.packageName)
        val ref = resolveRef(app)
        return toDownloadMeta(app, api.download(ref, installed, app.versionCode))
    }

    override suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta {
        val installed = installedPackages.installedPackage(app.packageName)
            ?: app.toInstalledPackage().takeIf { it.versionCode > 0L }
            ?: throw MarketException("Samsung 更新下载需要当前已安装版本")
        val ref = resolveRef(app)
        return toDownloadMeta(app, api.download(ref, installed, app.versionCode))
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> {
        val installed = installedPackages.installed()
            .filter { it.packageName.isNotBlank() && it.versionCode > 0L }
            .associateBy { it.packageName.lowercase() }
        if (installed.isEmpty()) return emptyList()
        return installed.values.chunked(UPDATE_BATCH_SIZE)
            .flatMap { api.updates(it) }
            .mapNotNull { product ->
                val packageName = product.values.samsungValue("GUID", "packageName").lowercase()
                val local = installed[packageName] ?: return@mapNotNull null
                val app = toApp(product)
                if (app.versionCode <= local.versionCode) return@mapNotNull null
                app.withInstalled(local).also { remember(it, product.ref) }
            }
            .distinctBy { it.packageName.lowercase() }
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult {
        val installed = InstalledPackage(
            packageName = request.packageName,
            versionCode = request.versionCode,
            versionName = request.versionName,
            isSystemApp = request.isSystemApp,
            installedBy = request.installedBy,
            splits = request.splits,
            oldApkHash = request.oldApkHash,
            apkSource = request.apkSource,
        )
        val product = api.updates(listOf(installed)).firstOrNull {
            it.values.samsungValue("GUID", "packageName").equals(request.packageName, true)
        } ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        val app = toApp(product).withInstalled(installed)
        remember(app, product.ref)
        return if (app.versionCode > request.versionCode) {
            ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, app)
        } else {
            ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE, app)
        }
    }

    private suspend fun resolveRef(app: MarketAppInfo): SamsungProductRef =
        SamsungProductRef.parse(app.openLink)
            ?: refsById[app.appId]
            ?: refsByPackage[app.packageName.lowercase()]
            ?: api.search(app.packageName, 0).firstOrNull { it.ref.guid.equals(app.packageName, true) }?.ref
            ?: throw MarketException("Samsung 商品引用已失效，请重新搜索")

    private fun toApp(product: SamsungProduct): MarketAppInfo = product.values.toApp(product.ref)

    private fun toApp(detail: SamsungProductDetail): MarketAppInfo =
        (detail.overview + detail.main).toApp(detail.ref)

    private fun Map<String, String>.toApp(ref: SamsungProductRef): MarketAppInfo {
        val guid = samsungValue("GUID", "packageName").ifBlank { ref.guid }
        val productId = samsungValue("productID").ifBlank { ref.productId }
        val rawRating = samsungValue("averageRating").toDoubleOrNull() ?: 0.0
        return MarketAppInfo(
            appId = productId.toLongOrNull() ?: stableId(guid.ifBlank { productId }),
            packageName = guid,
            displayName = samsungValue("productName").ifBlank { guid },
            publisherName = samsungValue("sellerName"),
            versionName = samsungValue("version"),
            versionCode = samsungValue("versionCode").toLongOrNull() ?: 0L,
            icon = samsungValue("productImgUrl", "productImgURL"),
            apkSize = samsungValue("realContentSize", "realContentsSize", "contentsSize").toLongOrNull() ?: 0L,
            ratingScore = (rawRating / 2.0).coerceIn(0.0, 5.0),
            changeLog = samsungValue("updateDescription"),
            openLink = ref.copy(productId = productId, guid = guid).toLink(),
            source = AppSource.SAMSUNG,
            category = samsungValue("categoryName", "categoryPath"),
        )
    }

    private fun toDownloadMeta(app: MarketAppInfo, download: SamsungDownload): DownloadMeta {
        val values = download.values
        val url = values.samsungValue("downLoadURI", "downloadURI")
        if (url.isBlank()) throw MarketException("Samsung 未返回完整安装包地址")
        return DownloadMeta(
            appId = app.appId,
            packageName = app.packageName,
            displayName = values.samsungValue("productName").ifBlank { app.displayName },
            versionName = values.samsungValue("version").ifBlank { app.versionName },
            versionCode = values.samsungValue("versionCode").toLongOrNull() ?: app.versionCode,
            url = url,
            size = values.samsungValue("contentsSize", "installSize").toLongOrNull() ?: app.apkSize,
            // Samsung delta/xdelta is not compatible with AppMarket's current patch engine.
            // Leaving the base path empty makes the full-package-only contract explicit downstream.
            installedBaseApkPath = "",
            icon = app.icon,
            changeLog = app.changeLog,
            source = AppSource.SAMSUNG,
        ).samsungFullPackageOnly()
    }

    private fun screenshots(values: Map<String, String>): List<AppScreenshot> {
        val raw = values.samsungValue("screenShotImgURL")
        if (raw.isBlank()) return emptyList()
        val directUrls = raw.split('|').filter(String::isNotBlank)
        val indexes = values.samsungValue("screenShotIndex").split('|').filter(String::isNotBlank)
        val resolutions = values.samsungValue("screenShotResolution").split('|').filter(String::isNotBlank)
        val count = values.samsungValue("screenShotCount").toIntOrNull()
            ?: maxOf(indexes.size, resolutions.size, directUrls.size)
        return (0 until count).mapNotNull { offset ->
            val index = indexes.getOrNull(offset)?.toIntOrNull() ?: offset + 1
            val (width, height) = resolution(resolutions.getOrNull(offset))
            val base = directUrls.getOrNull(offset) ?: directUrls.firstOrNull() ?: return@mapNotNull null
            val url = if (directUrls.size > 1) base else screenshotUrl(base, width, height, index)
            AppScreenshot(
                url = url,
                expandedUrl = url,
                orientation = if (width > height) ScreenshotOrientation.LANDSCAPE else ScreenshotOrientation.PORTRAIT,
            )
        }.distinctBy(AppScreenshot::url)
    }

    private fun screenshotUrl(base: String, width: Int, height: Int, index: Int): String {
        if (width <= 0 || height <= 0) return base
        val dot = base.lastIndexOf('.')
        if (dot <= base.lastIndexOf('/')) return "${base}_${width}_${height}_$index"
        return base.substring(0, dot) + "_${width}_${height}_${index}" + base.substring(dot)
    }

    private fun resolution(raw: String?): Pair<Int, Int> {
        val parts = raw.orEmpty().lowercase().split('x')
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun remember(app: MarketAppInfo, ref: SamsungProductRef) {
        refsByPackage[app.packageName.lowercase()] = ref
        refsById[app.appId] = ref
    }

    private fun MarketAppInfo.withInstalled(installed: InstalledPackage): MarketAppInfo = copy(
        installedVersionName = installed.versionName,
        installedVersionCode = installed.versionCode,
        deltaSize = 0L,
        installedOldApkHash = "0",
        installedBaseApkPath = installed.baseApkPath,
        installedSplits = installed.splits,
        isSystemApp = installed.isSystemApp,
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

    private fun stableId(value: String): Long {
        val hash = value.fold(1125899906842597L) { acc, char -> acc * 31 + char.code }
        return hash.absoluteValue.coerceAtLeast(1L)
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val UPDATE_BATCH_SIZE = 120
    }
}

/** Samsung delta/xdelta needs a different binary identity and patch engine; keep only full APK sources. */
internal fun DownloadMeta.samsungFullPackageOnly(): DownloadMeta = copy(
    parts = parts.map { it.copy(patch = null) },
    installedBaseApkPath = "",
)
