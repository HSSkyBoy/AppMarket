package top.app.market.domain.model.today

import top.app.market.domain.model.market.MarketAppInfo

/**
 * A single Today / 金米奖 article loaded via `topic/detail`.
 *
 * The server delivers the body as an ordered list of topic blocks; the data layer flattens it into a
 * [headerImage] (the leading banner) plus [richTextHtml] (the concatenated text blocks) and lifts the
 * embedded app card out into [app].
 */
data class TodayArticle(
    val rId: String,
    val title: String,
    /** Source-provided award name. Empty keeps the localized Xiaomi default in the UI. */
    val awardName: String = "",
    /** Resolved leading banner/header image URL (empty when absent). */
    val headerImage: String,
    /** 头图低清预览地址，高清头图加载期间作占位。 */
    val headerImagePreview: String = headerImage,
    /** Article body: rich-text/HTML delivered by the server, blocks joined in order. */
    val richTextHtml: String,
    /** The app the article is about, if the topic embeds an app card. */
    val app: MarketAppInfo?,
    /** All app cards embedded in the topic. Group articles can contain several apps. */
    val apps: List<MarketAppInfo> = app?.let(::listOf).orEmpty(),
    /** Server content in `topicItemList` order. Legacy aggregate fields above remain available. */
    val blocks: List<TodayArticleBlock> = buildList {
        if (headerImage.isNotBlank()) add(TodayArticleBlock.Banner(headerImage))
        if (richTextHtml.isNotBlank()) add(TodayArticleBlock.RichText(richTextHtml))
        apps.forEach { add(TodayArticleBlock.App(it)) }
    },
    /** Whether the standard award/title label is drawn above the article reason. */
    val showTitleLabel: Boolean = true,
    /** Whether source text and its gradient are drawn over the header image. */
    val showCoverText: Boolean = true,
)
