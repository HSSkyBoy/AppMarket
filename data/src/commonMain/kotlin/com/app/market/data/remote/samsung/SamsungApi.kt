package com.app.market.data.remote.samsung

import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.platform.debugLog
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.MarketProfile
import com.app.market.domain.model.profile.SamsungRequestContext
import com.app.market.domain.repository.ProfileRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SamsungApi(
    private val client: HttpClient,
    private val profileStore: ProfileRepository,
    private val preferences: PreferencesDataSource,
) {
    private val regionMutex = Mutex()
    private var memoryRegion: SamsungRegionContext? = null

    suspend fun search(keyword: String, page: Int, pageSize: Int = PAGE_SIZE): List<SamsungProduct> {
        val (profile, region) = requestContext()
        return search(profile, region, keyword, page, pageSize)
    }

    private suspend fun search(
        profile: MarketProfile,
        region: SamsungRegionContext,
        keyword: String,
        page: Int,
        pageSize: Int,
    ): List<SamsungProduct> {
        val start = page.coerceAtLeast(0) * pageSize + 1
        val params = linkedMapOf(
            "keyword" to keyword.trim(),
            "imgWidth" to "135",
            "imgHeight" to "135",
            "alignOrder" to "bestMatch",
            "startNum" to start.toString(),
            "endNum" to (start + pageSize - 1).toString(),
            "contentType" to "all",
            "storeContentType" to "Apps",
            "status" to "0",
            "qlDomainCode" to "sa",
            "qlDeviceType" to "phone",
            "qlInputMethod" to "",
        ).withIdentity(profile).apply { put("runestoneYn", "N") }
        val response = call(profile, region, "2040", "searchProductListEx2Notc", params)
        return response.lists.mapNotNull { values ->
            if (values.value("playStoreProductYN").equals("Y", true)) return@mapNotNull null
            if (values.value("installable").equals("N", true) ||
                values.value("installableYN").equals("N", true)
            ) {
                return@mapNotNull null
            }
            val guid = values.value("GUID", "packageName")
            val productId = values.value("productID")
            if (guid.isBlank() && productId.isBlank()) return@mapNotNull null
            SamsungProduct(
                values = values,
                ref = SamsungProductRef(
                    productId = productId,
                    guid = guid,
                    generation = region.generation,
                    linkProduct = values.value("linkProductYn") == "1",
                    tencentLastInterface = values.value("usedApi", "lastInterfaceName"),
                ),
            )
        }.distinctBy { it.ref.guid.ifBlank { it.ref.productId }.lowercase() }
    }

    suspend fun detail(ref: SamsungProductRef): SamsungProductDetail = coroutineScope {
        val (profile, region) = requestContext(ref)
        val mainParams = linkedMapOf(
            "GUID" to ref.guid,
            "productID" to ref.productId,
        ).withIdentity(profile).apply {
            put("productImgWidth", "135")
            put("productImgHeight", "135")
            put("lkAppIncludedYN", "Y")
            put("predeployed", "0")
            put("triggeredFrom", "detail")
        }
        val overviewParams = linkedMapOf(
            "GUID" to ref.guid,
            "productID" to ref.productId,
            "imgWidth" to "1080",
            "imgHeight" to "1920",
        ).withIdentity(profile).apply {
            put("runestoneYn", "N")
            put("userAge", "")
        }
        val mainDeferred = async { call(profile, region, "2290", "guidProductDetailExMain", mainParams) }
        val overviewDeferred = async { call(profile, region, "2291", "guidProductDetailExOverview", overviewParams) }
        val main = mainDeferred.await().lists.firstOrNull().orEmpty()
        val overview = overviewDeferred.await().lists.firstOrNull().orEmpty()
        if (main.isEmpty() && overview.isEmpty()) throw MarketException("Samsung 未返回应用详情")
        val resolvedGuid = main.value("GUID").ifBlank { ref.guid }
        val resolvedProductId = main.value("productID").ifBlank { ref.productId }
        if (ref.guid.isNotBlank() && resolvedGuid.isNotBlank() && !ref.guid.equals(resolvedGuid, true)) {
            throw MarketException("Samsung 详情标识与搜索结果不一致")
        }
        SamsungProductDetail(
            main = main,
            overview = overview,
            ref = ref.copy(productId = resolvedProductId, guid = resolvedGuid),
        )
    }

    suspend fun updates(installed: List<InstalledPackage>): List<SamsungProduct> {
        if (installed.isEmpty()) return emptyList()
        val (profile, region) = requestContext()
        val loadApp = installed.joinToString("||") { item ->
            if (item.isSystemApp || item.installOrigin.equals("PRELOAD", true)) {
                "${item.packageName}@@${item.versionCode}@0@${item.installedBy.takeUnless { it == "0" }.orEmpty()}@N"
            } else {
                "${item.packageName}@@${item.versionCode}@1@N"
            }
        }
        val params = linkedMapOf(
            "imgWidth" to "135",
            "imgHeight" to "135",
            "loadApp" to loadApp,
            "predeployed" to "0",
            "justForCount" to "N",
            "autoUpdateYN" to "N",
        ).withIdentity(profile)
        val response = call(profile, region, "2389", "getUpdateList", params)
        return response.lists.mapNotNull { values ->
            val guid = values.value("GUID", "packageName")
            if (guid.isBlank()) return@mapNotNull null
            SamsungProduct(
                values = values,
                ref = SamsungProductRef(
                    productId = values.value("productID"),
                    guid = guid,
                    generation = region.generation,
                    linkProduct = values.value("linkProductYn") == "1",
                    tencentLastInterface = values.value("usedApi", "lastInterfaceName"),
                ),
            )
        }
    }

    suspend fun download(
        ref: SamsungProductRef,
        installed: InstalledPackage?,
        targetVersionCode: Long,
    ): SamsungDownload {
        val (profile, region) = requestContext(ref)
        if (ref.linkProduct) return tencentDownload(profile, region, ref, installed, targetVersionCode)
        val detail = detail(ref)
        val localInstalled = installed?.takeIf { it.versionCode > 0L }
        val params = if (localInstalled != null) {
            linkedMapOf(
                "GUID" to detail.ref.guid,
            ).withIdentity(profile).apply {
                put("dowloadType", "new")
                put("autoUpdateYN", "N")
                put("deepLinkSource", "N")
                put("versionCode", localInstalled.versionCode.toString())
                put("predeployed", if (localInstalled.isSystemApp) "1" else "0")
                put("resumeYN", "N")
                put("loadType", if (localInstalled.isSystemApp || localInstalled.installOrigin.equals("PRELOAD", true)) "0" else "1")
                put("productID", detail.ref.productId)
            }
        } else {
            val price = detail.main.value("sellingPrice", "price").toDoubleOrNull() ?: 0.0
            if (price > 0.0) throw MarketException("Samsung 付费应用需要 Galaxy Store 账号，当前不支持购买")
            linkedMapOf(
                "productID" to detail.ref.productId,
                "couponIssuedSEQ" to "",
            ).withIdentity(profile).apply {
                put("GUID", detail.ref.guid)
                put("paymentAmountPrice", "")
                put("testPurchaseYn", "N")
                put("autoUpdateYN", "N")
                put("deepLinkSource", "N")
                put("resumeYN", "N")
                put("userID", "")
                put("predeployed", "0")
            }
        }
        var values = try {
            if (localInstalled != null) {
                call(profile, region, "2311", "downloadEx2", params).lists.firstOrNull().orEmpty()
            } else {
                call(profile, region, "3010", "easybuyPurchase", params).lists.firstOrNull().orEmpty()
            }
        } catch (error: MarketException) {
            // A free Samsung system package can be catalogued as preloaded even
            // when it is absent on the current device.  In that case 3010 rejects
            // the purchase lookup, while 2311 can still issue the full Samsung CDN
            // package.  The stateless retry must not claim a target version as an
            // installed version (that makes ODC reject the request with 4002).
            debugLog(TAG) {
                "native download authorization rejected; trying stateless full package: ${error.message}"
            }
            try {
                statelessFullPackageDownload(profile, region, detail)
            } catch (_: MarketException) {
                debugLog(TAG) { "stateless full package rejected; trying China APK mirror" }
                try {
                    tencentDownloadInfo(profile, region, ref)
                } catch (_: MarketException) {
                    throw error
                }
            }
        }
        if (values.value("downLoadURI", "downloadURI").isBlank()) {
            debugLog(TAG) { "native download returned no URL; trying stateless full package" }
            values = try {
                statelessFullPackageDownload(profile, region, detail)
            } catch (_: MarketException) {
                emptyMap()
            }
            if (values.value("downLoadURI", "downloadURI").isBlank()) {
                debugLog(TAG) { "stateless full package returned no URL; trying China APK mirror" }
                values = try {
                    tencentDownloadInfo(profile, region, ref)
                } catch (_: MarketException) {
                    emptyMap()
                }
            }
        }
        if (values.value("downLoadURI", "downloadURI").isBlank()) {
            throw MarketException("Samsung 未返回完整安装包地址")
        }
        return SamsungDownload(values, detail.ref)
    }

    private suspend fun statelessFullPackageDownload(
        profile: MarketProfile,
        region: SamsungRegionContext,
        detail: SamsungProductDetail,
    ): Map<String, String> {
        val params = samsungStatelessFullPackageParams(
            identity = SamsungProtocol.identity(profile),
            guid = detail.ref.guid,
            productId = detail.ref.productId,
        )
        val values = call(profile, region, "2311", "downloadEx2", params)
            .lists.firstOrNull().orEmpty()
        if (values.value("downLoadURI", "downloadURI").isBlank()) {
            throw MarketException("Samsung 完整包接口未返回下载地址")
        }
        return values
    }

    suspend fun editorialList(page: Int, pageSize: Int): Pair<List<Map<String, String>>, Boolean> {
        val (profile, region) = requestContext()
        val start = page.coerceAtLeast(0) * pageSize + 1
        val params = linkedMapOf(
            "startNum" to start.toString(),
            "endNum" to (start + pageSize - 1).toString(),
            "imgWidth" to "1080",
            "imgHeight" to "1920",
        ).withIdentity(profile)
        val response = call(profile, region, "2241", "editorialList", params)
        return response.lists to (!response.endOfList && response.lists.size >= pageSize)
    }

    suspend fun editorialDetail(assetId: String): List<Map<String, String>> {
        val (profile, region) = requestContext()
        val params = linkedMapOf(
            "assetID" to assetId,
            "deviceWidth" to "1080",
            "deviceHeight" to "1920",
            "imgWidth" to "1080",
            "imgHeight" to "1920",
        ).withIdentity(profile).apply { put("userAge", "") }
        return call(profile, region, "2228", "editorialDetail", params).lists
    }

    private suspend fun tencentDownload(
        profile: MarketProfile,
        region: SamsungRegionContext,
        ref: SamsungProductRef,
        installed: InstalledPackage?,
        targetVersionCode: Long,
    ): SamsungDownload {
        val info = tencentDownloadInfo(profile, region, ref)
        val orderParams = linkedMapOf("productID" to ref.productId).withIdentity(profile).apply {
            put("GUID", ref.guid)
            put("autoUpdateYN", "N")
            put("versionCode", targetVersionCode.toString())
            installed?.let {
                put("loadType", if (it.isSystemApp || it.installOrigin.equals("PRELOAD", true)) "0" else "1")
            }
        }
        call(profile, region, "3013", "createOrderForTencent", orderParams)
        return SamsungDownload(info, ref)
    }

    /**
     * Requests the APK mirror used by China partner products.  Although the
     * endpoint is named Tencent, the China backend also serves a subset of
     * native Galaxy products when account/order authorization is unavailable.
     */
    private suspend fun tencentDownloadInfo(
        profile: MarketProfile,
        region: SamsungRegionContext,
        ref: SamsungProductRef,
    ): Map<String, String> {
        val infoParams = linkedMapOf("stduk" to SamsungProtocol.identity(profile).stduk).apply {
            put("extuk", SamsungProtocol.identity(profile).extuk)
            put("GUID", ref.guid)
            put("tencentSource", "general")
            put("lastInterfaceName", ref.tencentLastInterface.ifBlank { "searchProductListEx2Notc" })
        }
        val info = call(profile, region, "2801", "downloadInfoForTencent", infoParams)
            .lists.firstOrNull().orEmpty()
        if (info.value("downLoadURI", "downloadURI").isBlank()) {
            throw MarketException("Samsung 国区未返回可用安装包地址")
        }
        return info
    }

    private suspend fun requestContext(ref: SamsungProductRef? = null): Pair<MarketProfile, SamsungRegionContext> {
        val profile = profileStore.load(AppSource.SAMSUNG)
        val region = resolveRegion(profile)
        if (ref != null && ref.generation > 0L && ref.generation != region.generation) {
            throw MarketException("Samsung 目录版本已过期，请重新搜索后再打开或下载")
        }
        return profile to region
    }

    private suspend fun resolveRegion(profile: MarketProfile): SamsungRegionContext = regionMutex.withLock {
        val requestContext = profileStore.samsungRequestContext()
        memoryRegion?.let { return@withLock it.withRequestContext(requestContext) }
        val seed = seedContext(requestContext)
        val cachedUrl = preferences.read(SamsungRegionPreferenceKeys.CountryUrl).orEmpty()
        if (cachedUrl.isNotBlank()) {
            val cached = seed.copy(
                generation = preferences.read(SamsungRegionPreferenceKeys.Generation)?.toLongOrNull() ?: 1L,
                countryUrl = cachedUrl.secureOdcUrl(),
                mcc = preferences.read(SamsungRegionPreferenceKeys.Mcc).orEmpty().ifBlank { seed.mcc },
                countryCode = preferences.read(SamsungRegionPreferenceKeys.CountryCode).orEmpty()
                    .ifBlank { seed.countryCode },
            )
            memoryRegion = cached
            return@withLock cached.withRequestContext(requestContext)
        }
        val discovered = runCatching {
            val params = linkedMapOf(
                "accountCountry" to "",
                "accountMcc" to "",
                "latestCountryCode" to seed.mcc,
                "whoAmI" to "odc",
            )
            val response = call(profile, seed, "2300", "countrySearchEx", params)
            val values = response.lists.firstOrNull().orEmpty()
            seed.copy(
                generation = 1L,
                countryUrl = values.value("countryURL").secureOdcUrl(),
                mcc = values.value("MCC").ifBlank { seed.mcc },
                countryCode = values.value("countryCode").ifBlank { seed.countryCode },
            ).also { require(it.countryUrl.isNotBlank()) }
        }.getOrElse { error ->
            debugLog(TAG) {
                "region discovery failed; using fixed fallback: ${error.message}"
            }
            seed.copy(
                generation = 1L,
                countryUrl = CHINA_ODC,
            )
        }
        debugLog(TAG) {
            "region resolved country=${discovered.countryCode} endpoint=${discovered.countryUrl}"
        }
        preferences.put(SamsungRegionPreferenceKeys.CountryUrl, discovered.countryUrl)
        preferences.put(SamsungRegionPreferenceKeys.CountryCode, discovered.countryCode)
        preferences.put(SamsungRegionPreferenceKeys.Mcc, discovered.mcc)
        preferences.put(SamsungRegionPreferenceKeys.Generation, discovered.generation.toString())
        memoryRegion = discovered
        discovered.withRequestContext(requestContext)
    }

    private suspend fun call(
        profile: MarketProfile,
        region: SamsungRegionContext,
        id: String,
        name: String,
        params: Map<String, String>,
    ): SamsungXmlResponse {
        debugLog(TAG) {
            "request=$id/$name country=${region.countryCode} endpoint=${region.countryUrl}"
        }
        val body = SamsungProtocol.requestBody(profile, region, id, name, params)
        val httpResponse = client.post(region.countryUrl) {
            url {
                parameters.append("reqId", id)
                parameters.append("ot", "01")
                parameters.append("ct", "B")
            }
            contentType(ContentType.Text.Plain)
            header(HttpHeaders.Accept, "image/webp")
            setBody(body)
        }
        if (httpResponse.status.value !in 200..299) {
            throw MarketException("Samsung 请求失败：HTTP ${httpResponse.status.value}")
        }
        val parsed = runCatching { SamsungXml.parseResponse(httpResponse.bodyAsText()) }
            .getOrElse { throw MarketException("Samsung 返回了无法解析的协议响应") }
        if (!parsed.isSuccess) {
            val message = parsed.errorMessage.ifBlank { "协议错误 ${parsed.errorCode.ifBlank { parsed.returnCode.toString() }}" }
            debugLog(TAG) {
                "request=$id/$name rejected code=${parsed.errorCode.ifBlank { parsed.returnCode.toString() }} message=${
                    message.take(
                        160
                    )
                }"
            }
            throw MarketException("Samsung 请求失败：$message")
        }
        return parsed
    }

    private fun seedContext(
        request: SamsungRequestContext,
    ): SamsungRegionContext {
        return SamsungRegionContext(
            generation = 0L,
            countryUrl = CHINA_HUB,
            mcc = request.mcc,
            mnc = request.mnc,
            csc = request.csc,
            countryCode = request.countryCode,
            lang = request.language,
        )
    }

    private fun SamsungRegionContext.withRequestContext(request: SamsungRequestContext): SamsungRegionContext = copy(
        mcc = request.mcc,
        mnc = request.mnc,
        csc = request.csc,
        countryCode = request.countryCode,
        lang = request.language,
    )

    private fun LinkedHashMap<String, String>.withIdentity(profile: MarketProfile): LinkedHashMap<String, String> {
        val identity = SamsungProtocol.identity(profile)
        put("imei", identity.installationId)
        put("stduk", identity.stduk)
        put("extuk", identity.extuk)
        return this
    }

    private fun String.secureOdcUrl(): String = trim().replaceFirst("http://", "https://")

    private fun Map<String, String>.value(vararg keys: String): String {
        keys.forEach { key -> entries.firstOrNull { it.key.equals(key, true) }?.value?.let { return it } }
        return ""
    }

    private fun Map<String, String>.toProductOrNull(region: SamsungRegionContext): SamsungProduct? {
        val guid = value("GUID", "packageName")
        val productId = value("productID")
        if (guid.isBlank() && productId.isBlank()) return null
        return SamsungProduct(
            values = this,
            ref = SamsungProductRef(
                productId = productId,
                guid = guid,
                generation = region.generation,
                linkProduct = value("linkProductYn") == "1",
                tencentLastInterface = value("usedApi", "lastInterfaceName"),
            ),
        )
    }

    private companion object {
        const val TAG = "SamsungApi"
        const val PAGE_SIZE = 20
        const val CHINA_HUB = "https://cn-ms.galaxyappstore.com/ods.as"
        const val CHINA_ODC = "https://cn-ms.galaxyappstore.com/ods.as"
    }
}

internal fun Map<String, String>.samsungValue(vararg keys: String): String {
    keys.forEach { key -> entries.firstOrNull { it.key.equals(key, true) }?.value?.let { return it } }
    return ""
}

internal fun samsungStatelessFullPackageParams(
    identity: SamsungProtocol.Identity,
    guid: String,
    productId: String,
): LinkedHashMap<String, String> = linkedMapOf(
    "GUID" to guid,
    "imei" to identity.installationId,
    "stduk" to identity.stduk,
    "extuk" to identity.extuk,
    "dowloadType" to "new",
    "autoUpdateYN" to "N",
    "deepLinkSource" to "N",
).apply {
    put("predeployed", "0")
    put("resumeYN", "N")
    put("productID", productId)
}
