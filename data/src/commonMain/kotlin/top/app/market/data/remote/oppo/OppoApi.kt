package top.app.market.data.remote.oppo

import top.app.market.data.platform.debugLog
import top.app.market.data.platform.gunzip
import top.app.market.data.platform.gzip
import top.app.market.data.remote.urlEncodeParameters
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.download.DownloadPatch
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppScreenshot
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.ScreenshotOrientation
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.profile.MarketProfile
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.repository.ProfileRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess

internal class OppoApi(
    private val client: HttpClient,
    private val profileStore: ProfileRepository,
) {
    private val categoryCache = mutableMapOf<Boolean, List<OppoCategory>>()

    suspend fun search(keyword: String, page: Int): SearchPage {
        val profile = profileStore.load(AppSource.OPPO)
        val resources = prioritizeOppoSearchResults(
            fetchSearchResources(keyword, page, profile),
            keyword,
        )
        return SearchPage(
            items = resources.map(::toApp),
            // The protocol does not expose a reliable total count; an empty page is the only safe stop.
            hasMore = resources.isNotEmpty() && page < MAX_PAGE,
        )
    }

    private suspend fun fetchSearchResources(
        keyword: String,
        page: Int,
        profile: MarketProfile,
    ): List<OppoResource> {
        val pageSize = CN_PAGE_SIZE
        val searchUrl = url(
            "/search/v2/search", mapOf(
                "start" to (page * pageSize).toString(),
                "inputWord" to keyword,
                "size" to pageSize.toString(),
                "keyword" to keyword,
                "searchType" to "10",
            )
        )
        val response = request(HttpMethod.Post, searchUrl, byteArrayOf(0x0a, 0x00), profile)
        return parseOppoResources(response)
    }

    /**
     * 「分类」页的大类（`/card/store/v4/cat/app|game`）。响应是 protobuf 卡片列表，
     * 其中每个 CategoryCardDto 带名称（101）与大类 id（103）；结果在进程内缓存。
     */
    suspend fun categories(games: Boolean): List<OppoCategory> {
        categoryCache[games]?.let { return it }
        val profile = profileStore.load(AppSource.OPPO)
        val response = request(
            HttpMethod.Get,
            url(
                if (games) "/card/store/v4/cat/game" else "/card/store/v4/cat/app",
                mapOf("start" to "0", "size" to CN_PAGE_SIZE.toString()),
            ),
            body = null,
            profile = profile,
        )
        val root = parseOppoProto(response)
        val categories = root.all(3).mapNotNull { field ->
            val card = root.child(field) ?: return@mapNotNull null
            if (card.string(127) != OPPO_CATEGORY_CARD) return@mapNotNull null
            val id = card.long(103)
            val name = card.string(101)
            if (id <= 0L || name.isBlank()) null else OppoCategory(id = id, name = name)
        }.distinctBy(OppoCategory::id)
        if (categories.isEmpty()) throw MarketException("OPPO 未返回分类")
        categoryCache[games] = categories
        return categories
    }

    /** 大类下的应用（`/card/store/v3/cat/resources/alg/1`，参数 `cid`）；每页偏移 20，跨页不重复。 */
    suspend fun categoryApps(categoryId: Long, page: Int): SearchPage {
        val profile = profileStore.load(AppSource.OPPO)
        val pageSize = CN_PAGE_SIZE
        val response = request(
            HttpMethod.Get,
            url(
                "/card/store/v3/cat/resources/alg/1",
                mapOf(
                    "cid" to categoryId.toString(),
                    "start" to (page * pageSize).toString(),
                    "size" to pageSize.toString(),
                ),
            ),
            body = null,
            profile = profile,
        )
        val resources = parseOppoResources(response)
        return SearchPage(
            items = resources.map(::toApp),
            // 无可靠总数，空页即末页
            hasMore = resources.isNotEmpty() && page < MAX_PAGE,
        )
    }

    suspend fun appDetail(appId: Long, packageName: String, externalQuery: String?): AppDetail {
        val profile = profileStore.load(AppSource.OPPO)
        val resource = appResource(profile, appId, packageName, externalQuery)
        val app = toApp(resource)
        return AppDetail(
            app = app,
            brief = resource.brief,
            introduction = resource.description,
            changeLog = resource.changeLog,
            category = resource.category,
            ageClassification = resource.ageClassification,
            downloadCount = resource.resolvedDownloadCount(),
            registrationNum = resource.registrationNum,
            updateTime = 0L,
            privacyUrl = "",
            screenshots = resource.toScreenshots(),
            commentCount = resource.commentCount,
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    private suspend fun appResource(
        profile: MarketProfile,
        appId: Long,
        packageName: String,
        externalQuery: String?,
    ): OppoResource {
        val resolvedId = if (appId > 0L) appId else {
            val query = externalQuery?.substringAfter("id=", "").orEmpty().ifBlank { packageName }
            search(query, 0).items.firstOrNull { it.packageName == packageName || query == it.packageName }?.appId ?: 0L
        }
        if (resolvedId <= 0L) throw MarketException("OPPO 未收录该应用")
        val detailPath = "/detail/v5/$resolvedId"
        val detailParams = linkedMapOf(
            "query" to DETAIL_FIELDS,
            "installationMode" to "0",
            "notFilterVerId" to "0",
            "extAtd" to "0",
            "source" to "1",
            "appId" to resolvedId.toString(),
        )
        val response = request(
            HttpMethod.Get,
            url(detailPath, detailParams),
            body = null,
            profile = profile,
        )
        val resources = parseOppoResources(response)
        val resource = resources
            .firstOrNull { it.packageName == packageName }
            ?: resources.firstOrNull { it.appId == resolvedId }
            ?: throw MarketException("OPPO 未返回该应用的有效信息")
        return resource
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val profile = profileStore.load(AppSource.OPPO)
        val resource = runCatching { appResource(profile, app.appId, app.packageName, null) }.getOrNull()
        return downloadMetaInternal(app, profile, resource)
    }

    suspend fun downloadUpdateMeta(
        app: MarketAppInfo,
        currentInstalled: InstalledPackage? = null,
    ): DownloadMeta {
        val profile = profileStore.load(AppSource.OPPO)
        return downloadUpdateMeta(app, currentInstalled, profile)
    }

    internal suspend fun downloadUpdateMeta(
        app: MarketAppInfo,
        currentInstalled: InstalledPackage?,
        profile: MarketProfile,
    ): DownloadMeta {
        val installed = currentInstalled ?: InstalledPackage(
            packageName = app.packageName,
            versionCode = app.installedVersionCode,
            versionName = app.installedVersionName,
            isSystemApp = app.isSystemApp,
            oldApkHash = app.installedOldApkHash,
            splits = app.installedSplits,
            baseApkPath = app.installedBaseApkPath,
        )
        // Refresh once so an expiring patch URL and its matching full-APK metadata come from the
        // same upgrade response. This is a metadata lookup, not a retry loop.
        val resource = if (installed.versionCode > 0L) {
            requestUpdates(listOf(installed), profile)
                .filter { it.packageName == app.packageName && it.versionCode == app.versionCode }
                .maxByOrNull(OppoResource::versionCode)
        } else {
            null
        }
        return downloadMetaInternal(app, profile, resource, installed)
    }

    suspend fun checkUpdates(installed: List<InstalledPackage>): List<MarketAppInfo> {
        if (installed.isEmpty()) return emptyList()
        val profile = profileStore.load(AppSource.OPPO)
        return checkUpdates(installed, profile)
    }

    internal suspend fun checkUpdates(
        installed: List<InstalledPackage>,
        profile: MarketProfile,
    ): List<MarketAppInfo> {
        if (installed.isEmpty()) return emptyList()
        val byPackage = requestUpdates(installed, profile).groupBy { it.packageName }
        return installed.mapNotNull { local ->
            byPackage[local.packageName]
                .orEmpty()
                .filter { it.versionCode > local.versionCode }
                .maxByOrNull { it.versionCode }
                ?.let { toApp(it, local) }
        }
    }

    suspend fun checkManualUpdate(request: ManualUpdateRequest): top.app.market.domain.model.update.ManualUpdateResult {
        val profile = profileStore.load(AppSource.OPPO)
        val installed = InstalledPackage(
            packageName = request.packageName,
            versionCode = request.versionCode,
            versionName = request.versionName,
            isSystemApp = request.isSystemApp,
            oldApkHash = request.oldApkHash,
            splits = request.splits,
            apkSource = request.apkSource,
            installedBy = request.installedBy,
        )
        val candidates = requestUpdates(listOf(installed), profile)
            .filter { it.packageName == request.packageName }
        val latest = candidates.maxByOrNull { it.versionCode }
        if (latest != null && latest.versionCode > request.versionCode) {
            return top.app.market.domain.model.update.ManualUpdateResult(
                status = top.app.market.domain.model.update.ManualUpdateStatus.UPDATE_AVAILABLE,
                app = toApp(latest, installed),
            )
        }
        return top.app.market.domain.model.update.ManualUpdateResult(
            status = if (candidates.isEmpty()) {
                top.app.market.domain.model.update.ManualUpdateStatus.NOT_FOUND
            } else {
                top.app.market.domain.model.update.ManualUpdateStatus.RECOGNIZED_NO_UPDATE
            }
        )
    }

    suspend fun beautyFeed(page: Int, pageSize: Int): TodayFeedPage {
        require(page >= 0) { "page must be non-negative" }
        require(pageSize > 0) { "pageSize must be positive" }
        val profile = profileStore.load(AppSource.OPPO)
        val response = request(
            HttpMethod.Get,
            url(
                "/card/store/v4/beauty/weekly", mapOf(
                    "size" to pageSize.toString(),
                    "start" to (page * pageSize).toString(),
                    "pageId" to BEAUTY_PAGE_ID,
                )
            ),
            body = null,
            profile = profile,
        )
        return parseOppoBeautyFeed(response, pageSize)
    }

    suspend fun beautyArticle(snippetId: String): TodayArticle {
        val id = snippetId.toLongOrNull()?.takeIf { it > 0L }
            ?: throw MarketException("OPPO 至美奖文章 ID 无效")
        val profile = profileStore.load(AppSource.OPPO)
        val response = request(
            HttpMethod.Get,
            url("/card/store/v5/snippet/$id", mapOf("v" to "3")),
            body = null,
            profile = profile,
        )
        return parseOppoSnippetArticle(id.toString(), response)
    }

    private suspend fun requestUpdates(
        installed: List<InstalledPackage>,
        profile: MarketProfile,
    ): List<OppoResource> {
        val response = request(
            HttpMethod.Post,
            url("/update/global/v1/check"),
            body = encodeOppoUpdateRequest(installed),
            profile = profile,
        )
        return parseOppoResources(response)
    }

    private suspend fun downloadMetaInternal(
        app: MarketAppInfo,
        profile: MarketProfile,
        resource: OppoResource?,
        installed: InstalledPackage? = null,
    ): DownloadMeta {
        val requestContext = profileStore.oppoRequestContext()
        val metadataUrl = (resource?.url ?: app.openLink).takeIf { it.startsWith("http", ignoreCase = true) }
            ?: throw MarketException("OPPO 未提供该应用的完整 APK 下载地址")
        if (metadataUrl.contains("incfs", ignoreCase = true) || metadataUrl.endsWith(".dm", ignoreCase = true)) {
            throw MarketException("OPPO 返回的不是完整 APK，已停止下载以避免安装残包")
        }
        val downloadFiles = if (isOppoDownloadMetadataUrl(metadataUrl)) {
            val response = request(
                method = HttpMethod.Get,
                url = metadataUrl,
                body = null,
                profile = profile,
            )
            parseOppoDownloadFileWrap(response).also { download ->
                if (download.code != HTTP_OK || download.files.isEmpty()) {
                    throw MarketException("OPPO 未返回该应用的有效下载文件")
                }
            }
        } else {
            null
        }
        val resolvedParts = downloadFiles?.files
            ?.sortedBy { if (it.type == OPPO_BASE_FILE_TYPE) 0 else 1 }
            ?.map { file ->
                val fileUrl = file.url
                if (!fileUrl.startsWith("https://", ignoreCase = true) ||
                    fileUrl.contains("incfs", ignoreCase = true) ||
                    fileUrl.endsWith(".dm", ignoreCase = true)
                ) {
                    throw MarketException("OPPO 返回了无效的 APK 下载文件")
                }
                val isBase = file.type == OPPO_BASE_FILE_TYPE
                DownloadPart(
                    name = if (isBase) "base" else file.splitName.ifBlank { file.id },
                    type = if (isBase) "base" else "split",
                    url = fileUrl,
                    size = file.size,
                    hash = file.md5,
                )
            }
        val primaryPart = resolvedParts?.firstOrNull { it.type == "base" }
            ?: resolvedParts?.firstOrNull()
        val url = primaryPart?.url ?: metadataUrl
        val fullSize = downloadFiles?.size?.takeIf { it > 0L }
            ?: resolvedParts?.sumOf(DownloadPart::size)?.takeIf { it > 0L }
            ?: resource?.size?.takeIf { it > 0L }
            ?: app.apkSize
        val canUsePatch = installed?.canUseOppoPatch() ?: app.canUseOppoPatch()
        val oldApkHash = installed?.oldApkHash ?: app.installedOldApkHash
        val selectedPatch = resource?.preferredPatch(fullSize)
            ?.takeIf { canUsePatch }
            ?.let { patch ->
                DownloadPatch(
                    url = patch.url,
                    size = patch.size,
                    hash = patch.md5,
                    version = patch.obitVersion,
                    oldApkHash = oldApkHash,
                    requestHeaders = OppoSigner.headers("GET", patch.url, profile, requestContext)
                        .values.filterKeys { it in DOWNLOAD_HEADERS },
                )
            }
        val headers = OppoSigner.headers("GET", url, profile, requestContext)
            .values.filterKeys { it in if (downloadFiles == null) DOWNLOAD_HEADERS else CDN_DOWNLOAD_HEADERS }
        return DownloadMeta(
            appId = resource?.appId ?: app.appId,
            packageName = resource?.packageName ?: app.packageName,
            displayName = resource?.displayName?.ifBlank { app.displayName } ?: app.displayName,
            versionName = resource?.versionName?.ifBlank { app.versionName } ?: app.versionName,
            versionCode = resource?.versionCode ?: app.versionCode,
            url = url,
            size = fullSize,
            parts = resolvedParts ?: listOf(
                DownloadPart(
                    name = "",
                    type = "base",
                    url = url,
                    size = fullSize,
                    hash = resource?.md5.orEmpty(),
                    patch = selectedPatch,
                )
            ),
            installedBaseApkPath = installed?.baseApkPath ?: app.installedBaseApkPath,
            icon = resource?.icon?.ifBlank { app.icon } ?: app.icon,
            changeLog = resource?.changeLog?.ifBlank { app.changeLog } ?: app.changeLog,
            requestHeaders = headers,
            source = AppSource.OPPO,
        )
    }

    private suspend fun request(
        method: HttpMethod,
        url: String,
        body: ByteArray?,
        profile: MarketProfile,
    ): ByteArray {
        val requestContext = profileStore.oppoRequestContext()
        val signed = OppoSigner.headers(method.value, url, profile, requestContext)
        val response: HttpResponse = client.request(url) {
            this.method = method
            signed.values.forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.AcceptEncoding, "gzip")
            contentType(ContentType.parse(OppoSigner.Accept))
            if (body != null) {
                header(HttpHeaders.ContentEncoding, "gzip")
                setBody(gzip(body))
            }
        }
        val bytes = response.body<ByteArray>()
        if (!response.status.isSuccess()) {
            debugLog("OppoApi") {
                "HTTP ${response.status.value} ${response.request.url.encodedPath}: ${bytes.size} bytes"
            }
            throw MarketException("OPPO 服务器返回异常状态 HTTP ${response.status.value}")
        }
        return if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) gunzip(bytes) else bytes
    }

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val host = CN_HOST
        if (params.isEmpty()) return host + path
        return host + path + "?" + urlEncodeParameters(params)
    }

    private fun toApp(resource: OppoResource, installed: InstalledPackage? = null): MarketAppInfo = MarketAppInfo(
        appId = resource.appId,
        packageName = resource.packageName,
        displayName = resource.displayName.ifBlank { resource.packageName },
        publisherName = "",
        versionName = resource.versionName,
        versionCode = resource.versionCode,
        icon = resource.icon,
        apkSize = resource.size,
        deltaSize = resource.preferredPatch(resource.size)
            ?.takeIf { installed?.canUseOppoPatch() == true }
            ?.size ?: 0L,
        ratingScore = resource.rating.toDouble(),
        changeLog = resource.changeLog,
        isSystemApp = installed?.isSystemApp ?: false,
        openLink = resource.url,
        installedVersionName = installed?.versionName.orEmpty(),
        installedVersionCode = installed?.versionCode ?: 0L,
        installedOldApkHash = installed?.oldApkHash ?: "0",
        installedBaseApkPath = installed?.baseApkPath.orEmpty(),
        installedSplits = installed?.splits ?: "0",
        isAd = resource.isAd,
        source = AppSource.OPPO,
        category = resource.category,
        downloadCount = resource.resolvedDownloadCount(),
    )

    private fun OppoResource.toScreenshots(): List<AppScreenshot> {
        val previews = screenshots.ifEmpty { hdScreenshots }
        return previews.mapIndexed { index, preview ->
            AppScreenshot(
                url = preview,
                expandedUrl = hdScreenshots.getOrNull(index) ?: preview,
                // The v5 payload has no dimensions for this legacy URL list. OPPO phone apps are
                // portrait by default; the original URL remains available to the full-screen viewer.
                orientation = ScreenshotOrientation.PORTRAIT,
            )
        }
    }

    private companion object {
        const val CN_HOST = "https://api-cn.store.heytapmobi.com"
        const val CN_PAGE_SIZE = 20
        const val MAX_PAGE = 99
        const val OPPO_CATEGORY_CARD = "com.heytap.cdo.card.domain.dto.CategoryCardDto"
        const val BEAUTY_PAGE_ID = "629"
        const val DETAIL_FIELDS = "1,2,3,4,5,6,7,8,9,10,12,15,16,17,18,19,20,22,26,27,30,32,56,64,102,107,112,201"
        const val HTTP_OK = 200
        const val OPPO_BASE_FILE_TYPE = 0
        val DOWNLOAD_HEADERS = setOf(
            "User-Agent", "Accept", "oak", "id", "ocs", "appid", "appversion",
            "pkg-ver", "romver", "t", "sign", "sg", "user-region", "system-locale",
            "supported-locales", "locale",
        )
        val CDN_DOWNLOAD_HEADERS = setOf("User-Agent")
    }
}

internal fun isOppoDownloadMetadataUrl(url: String): Boolean =
    OPPO_DOWNLOAD_METADATA_PREFIXES.any { prefix -> url.startsWith(prefix, ignoreCase = true) }

private val OPPO_DOWNLOAD_METADATA_PREFIXES = listOf(
    "https://api-cn.store.heytapmobi.com/download/v2/",
    "https://api-store-gl.heytapmobile.com/download/v2/",
    "https://api-store-gl.heytapmobile.com/download/overseas/v2/",
)

internal fun encodeOppoUpdateRequest(installed: List<InstalledPackage>): ByteArray {
    val negotiatedObitVersions = if (installed.any(InstalledPackage::canUseOppoPatch)) {
        OPPO_NEGOTIATED_OBIT_VERSIONS
    } else {
        emptyList()
    }
    val requests = installed.map { app ->
        val deltaCapable = app.canUseOppoPatch()
        val fields = mutableListOf(
            // UpgradeWrapReqV3.upgrades is a polymorphic protostuff list. The runtime type field
            // is required; without it the endpoint returns HTTP 200 but ignores the child.
            encodeOppoStringField(127, OPPO_UPGRADE_REQUEST_CLASS),
            encodeOppoStringField(1, app.packageName),
            encodeOppoLongField(2, app.versionCode),
            encodeOppoStringField(3, app.oldApkHash.takeIf { deltaCapable } ?: "0"),
            // UpgradeReqV2 uses primitive protobuf fields for uid, targetSdk,
            // lastTimeUsed and appLaunchCount. Strings make the endpoint reject the request.
            encodeOppoLongField(4, 0L),
        )
        if (deltaCapable) {
            // OPPO's backend expects the official 1/5 capability pair. AppMarket still selects
            // and applies only obit 5 below; an obit 1-only response falls back to the full APK.
            negotiatedObitVersions.forEach { version ->
                fields += encodeOppoLongField(5, version.toLong())
            }
        }
        fields += encodeOppoLongField(6, app.targetSdkVersion.toLong())
        if (app.signature.isNotBlank()) fields += encodeOppoStringField(7, app.signature)
        app.signatureList.filter(String::isNotBlank).forEach { signature ->
            fields += encodeOppoStringField(9, signature)
        }
        fields += listOf(
            encodeOppoStringField(10, ""),
            encodeOppoStringField(11, app.installOrigin),
            encodeOppoLongField(12, 0L),
            encodeOppoLongField(13, 0L),
        )
        encodeOppoMessage(*fields.toTypedArray())
    }
    return encodeOppoMessage(
        *requests.map { encodeOppoBytesField(1, it) }.toTypedArray(),
        *negotiatedObitVersions.map { encodeOppoLongField(2, it.toLong()) }.toTypedArray(),
        encodeOppoLongField(3, 0L),
        encodeOppoLongField(5, 0L),
    )
}

private fun OppoResource.preferredPatch(fullSize: Long): OppoPatch? = patches.asSequence()
    .filter { it.obitVersion == OPPO_FILE_BY_FILE_OBIT_VERSION }
    .filter { it.url.startsWith("https://", ignoreCase = true) && it.size > 0L }
    .filter { fullSize <= 0L || it.size < fullSize }
    .minByOrNull(OppoPatch::size)

private fun InstalledPackage.canUseOppoPatch(): Boolean =
    baseApkPath.isNotBlank() && !hasSplits(splits) && oldApkHash.isOppoMd5()

private fun MarketAppInfo.canUseOppoPatch(): Boolean =
    installedBaseApkPath.isNotBlank() && !hasSplits(installedSplits) && installedOldApkHash.isOppoMd5()

private fun hasSplits(value: String): Boolean = value.isNotBlank() && value != "0"

private fun String.isOppoMd5(): Boolean = length == 32 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

private const val OPPO_FILE_BY_FILE_OBIT_VERSION = 5
private val OPPO_NEGOTIATED_OBIT_VERSIONS = listOf(1, OPPO_FILE_BY_FILE_OBIT_VERSION)
private const val OPPO_UPGRADE_REQUEST_CLASS = "com.heytap.cdo.global.update.domain.UpgradeReqV2"
