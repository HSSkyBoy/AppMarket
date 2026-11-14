package top.app.market.data.remote.fdroid

import top.app.market.domain.model.preference.FDroidMirror

/** 单个语言下的展示文案与素材文件名（相对 `<repo>/<包名>/<locale>/`）。 */
internal data class FDroidLocalizedText(
    val locale: String,
    val name: String,
    val summary: String,
    val description: String,
    val whatsNew: String,
    val icon: String,
    val screenshots: List<String>,
)

/** 本地精简索引中的一条应用记录；只保留搜索、详情、下载所需字段。 */
internal data class FDroidIndexApp(
    val packageName: String,
    val icon: String,
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val apkSize: Long,
    val lastUpdated: Long,
    val zh: FDroidLocalizedText?,
    val en: FDroidLocalizedText?,
    val apkHash: String = "",
    val signer: String = "",
) {
    /** 逐字段取文案：中文优先，该字段为空时回落英文（索引里中文常只翻译了名称和简介）。 */
    private fun text(pick: (FDroidLocalizedText) -> String): String =
        zh?.let(pick)?.takeIf { it.isNotBlank() } ?: en?.let(pick).orEmpty()

    val displayName: String get() = text { it.name }.ifBlank { packageName }
    val summary: String get() = text { it.summary }
    val description: String get() = text { it.description }
    val whatsNew: String get() = text { it.whatsNew }

    /** 搜索用的小写文本，构造时算一次，避免每次搜索重复转换。 */
    val searchKey: String = listOfNotNull(packageName, zh?.name, zh?.summary, en?.name, en?.summary)
        .joinToString("\n")
        .lowercase()
}

internal class FDroidIndex(val apps: List<FDroidIndexApp>) {
    private val byPackage: Map<String, FDroidIndexApp> = apps.associateBy { it.packageName }

    fun find(packageName: String): FDroidIndexApp? = byPackage[packageName]
}

/** 提供 F-Droid 索引；平台实现负责下载、解析与落盘缓存。 */
internal interface FDroidIndexSource {
    suspend fun load(mirror: FDroidMirror): FDroidIndex
}
