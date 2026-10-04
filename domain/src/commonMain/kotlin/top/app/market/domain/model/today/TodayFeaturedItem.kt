package top.app.market.domain.model.today

import top.app.market.domain.model.market.MarketAppInfo

/**
 * One entry in the Today tab's Golden Mi Award (金米奖) feed.
 *
 * Each entry renders a cover image ([coverImage]) and the awarded [app].
 * Tapping it opens the [TodayArticle] identified by [rId] (loaded via `topic/detail`).
 */
data class TodayFeaturedItem(
    /** Topic id used to load the article; may be empty when the entry only carries a raw [articleLink]. */
    val rId: String,
    val title: String,
    val summary: String,
    /** Resolved list cover image URL (empty when the server omitted it). */
    val coverImage: String,
    /** Featured app for app-type entries; null for pure editorial/webview entries. */
    val app: MarketAppInfo?,
    /** Raw click/webview URL as delivered by the server (fallback navigation target). */
    val articleLink: String = "",
    /** Apps resolved from the linked topic detail; group topics can contain several entries. */
    val apps: List<MarketAppInfo> = app?.let(::listOf).orEmpty(),
    /** Source-provided award name. Empty keeps the localized Xiaomi default in the UI. */
    val awardName: String = "",
    /** Whether the standard award/title label is drawn over the cover. */
    val showTitleLabel: Boolean = true,
    /** Whether source text is drawn over the cover image. */
    val showCoverText: Boolean = true,
)
