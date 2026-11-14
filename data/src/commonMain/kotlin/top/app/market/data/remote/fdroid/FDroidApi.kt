package top.app.market.data.remote.fdroid

import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppScreenshot
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.ScreenshotOrientation
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.preference.FDroidMirror

private const val SearchPageSize = 20
private val Whitespace = Regex("""\s+""")
private val HtmlTag = Regex("<[^>]+>")

/**
 * F-Droid 数据层：搜索、详情、下载地址全部基于镜像源的仓库索引在本地完成，
 * 不访问 f-droid.org 官网，这样镜像源可用时整条链路都可用。
 */
internal class FDroidApi(
    private val index: FDroidIndexSource,
) {
    /**
     * 搜索 F-Droid 开源应用。关键词按空白切分，所有词都需出现在包名 / 名称 / 简介中。
     */
    suspend fun search(keyword: String, page: Int = 0, mirror: FDroidMirror = FDroidMirror.Default): SearchPage {
        val query = keyword.trim().lowercase()
        val tokens = query.split(Whitespace).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return SearchPage(emptyList(), hasMore = false)

        val matches = index.load(mirror).apps
            .filter { app -> tokens.all { app.searchKey.contains(it) } }
            .sortedWith(compareBy<FDroidIndexApp> { matchRank(it, query) }.thenByDescending { it.lastUpdated })

        val from = page * SearchPageSize
        val items = matches.drop(from).take(SearchPageSize).map { toMarketAppInfo(it, mirror) }
        return SearchPage(items = items, hasMore = matches.size > from + SearchPageSize)
    }

    /**
     * 获取应用详情（名称、图标、描述、更新日志、截图、最新版本）。
     */
    suspend fun appDetail(packageName: String, mirror: FDroidMirror = FDroidMirror.Default): AppDetail {
        val pkg = packageName.trim()
        val app = index.load(mirror).find(pkg) ?: throw MarketException("F-Droid 未找到该应用：$pkg")
        val summary = app.summary
        val whatsNew = app.whatsNew
        val description = cleanHtml(app.description)

        // 截图优先中文版；中文没有截图时回落到英文
        val shotsText = app.zh?.takeIf { it.screenshots.isNotEmpty() } ?: app.en
        val screenshots = shotsText?.screenshots.orEmpty().map { file ->
            AppScreenshot(
                url = "${mirror.repoBaseUrl}/$pkg/${shotsText?.locale}/phoneScreenshots/$file",
                orientation = ScreenshotOrientation.PORTRAIT,
            )
        }

        return AppDetail(
            app = toMarketAppInfo(app, mirror).copy(changeLog = whatsNew.ifBlank { summary }),
            brief = summary,
            introduction = description.ifBlank { summary },
            changeLog = whatsNew,
            category = "Open Source",
            ageClassification = "",
            downloadCount = 0L,
            registrationNum = "",
            privacyUrl = "",
            screenshots = screenshots,
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    /**
     * 获取 APK 下载直链（指向用户选择的国内镜像源）。
     */
    suspend fun downloadMeta(app: MarketAppInfo, mirror: FDroidMirror = FDroidMirror.Default): DownloadMeta {
        val entry = index.load(mirror).find(app.packageName)
            ?.takeIf { it.apkName.isNotBlank() }
            ?: throw MarketException("F-Droid 未找到该应用的有效版本下载")

        return fdroidDownloadMeta(
            appId = app.appId,
            packageName = entry.packageName,
            displayName = app.displayName.ifBlank { entry.displayName },
            versionName = entry.versionName.ifBlank { app.versionName },
            versionCode = entry.versionCode.takeIf { it > 0 } ?: app.versionCode.takeIf { it > 0 } ?: 1L,
            icon = iconUrl(entry, mirror),
            downloadUrl = "${mirror.repoBaseUrl}/${entry.apkName}",
            fallbackSize = entry.apkSize.takeIf { it > 0 } ?: app.apkSize,
            hash = entry.apkHash,
        )
    }

    /**
     * 本地比对索引做更新检查。F-Droid 会给 APK 重新签名，所以只推荐签名与本机已装版本一致的更新，
     * 避免用户从其它商店装的应用被覆盖安装时签名冲突；任一侧签名缺失时保守跳过。
     */
    suspend fun checkUpdates(
        installed: List<InstalledPackage>,
        mirror: FDroidMirror = FDroidMirror.Default,
    ): List<MarketAppInfo> {
        if (installed.isEmpty()) return emptyList()
        val index = index.load(mirror)
        return installed.mapNotNull { local ->
            val remote = index.find(local.packageName) ?: return@mapNotNull null
            if (remote.versionCode <= local.versionCode) return@mapNotNull null
            if (remote.signer.isBlank() || !remote.signer.equals(local.signerSha256, ignoreCase = true)) {
                return@mapNotNull null
            }
            toMarketAppInfo(remote, mirror)
        }
    }

    /** 名称完全匹配 > 名称前缀 > 名称包含 > 包名包含 > 仅简介命中。 */
    private fun matchRank(app: FDroidIndexApp, query: String): Int {
        val names = listOfNotNull(app.zh?.name, app.en?.name).map { it.lowercase() }
        return when {
            names.any { it == query } -> 0
            names.any { it.startsWith(query) } -> 1
            names.any { it.contains(query) } -> 2
            app.packageName.lowercase().contains(query) -> 3
            else -> 4
        }
    }

    private fun toMarketAppInfo(app: FDroidIndexApp, mirror: FDroidMirror): MarketAppInfo =
        MarketAppInfo(
            appId = app.packageName.hashCode().toLong(),
            packageName = app.packageName,
            displayName = app.displayName,
            publisherName = "",
            versionName = app.versionName,
            versionCode = app.versionCode,
            icon = iconUrl(app, mirror),
            apkSize = app.apkSize,
            ratingScore = 0.0,
            changeLog = app.summary,
            openLink = "https://f-droid.org/en/packages/${app.packageName}/",
            source = AppSource.FDROID,
        )

    private fun iconUrl(app: FDroidIndexApp, mirror: FDroidMirror): String {
        if (app.icon.isNotBlank()) return "${mirror.repoBaseUrl}/icons-640/${app.icon}"
        val text = listOfNotNull(app.zh, app.en).firstOrNull { it.icon.isNotBlank() } ?: return ""
        return "${mirror.repoBaseUrl}/${app.packageName}/${text.locale}/${text.icon}"
    }

    /** 索引里的描述是简化 HTML（`<b>`、`<ul>` 等），详情页按纯文本展示。 */
    private fun cleanHtml(raw: String): String =
        raw.replace("</li>", "\n").replace("<li>", "• ")
            .let { HtmlTag.replace(it, "") }
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .trim()
}

internal fun fdroidDownloadMeta(
    appId: Long,
    packageName: String,
    displayName: String,
    versionName: String,
    versionCode: Long,
    icon: String,
    downloadUrl: String,
    fallbackSize: Long,
    hash: String = "",
): DownloadMeta {
    return DownloadMeta(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        versionName = versionName,
        versionCode = versionCode,
        url = downloadUrl,
        size = fallbackSize,
        parts = listOf(
            DownloadPart(
                name = "",
                type = "base",
                url = downloadUrl,
                size = fallbackSize,
                hash = hash,
            )
        ),
        icon = icon,
    )
}
