package top.app.market.domain.repository

import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage

/**
 * Today tab (金米奖) content operations against the Xiaomi App Store APIs.
 *
 * Implementations live in the platform layer and resolve the device profile + account cookie
 * internally, mirroring [MarketRepository].
 */
interface TodayRepository {
    /** Loads one page of Golden Mi Award apps from Xiaomi Market's `zone/goldMiV2` endpoint. */
    suspend fun goldMiFeed(page: Int = 0, pageSize: Int = 9): TodayFeedPage

    /** Loads the article for a feed entry: header image + rich-text body + embedded app. */
    suspend fun todayArticle(rId: String): TodayArticle
}
