package top.app.market.domain.model.today

/** One page of Golden Mi Award feed entries plus whether the server reports more pages to load. */
data class TodayFeedPage(
    val items: List<TodayFeaturedItem>,
    val hasMore: Boolean,
)
