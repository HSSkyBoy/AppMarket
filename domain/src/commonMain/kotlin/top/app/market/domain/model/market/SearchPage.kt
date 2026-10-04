package top.app.market.domain.model.market

/** One page of search results plus whether the server reports more pages to load. */
data class SearchPage(
    val items: List<MarketAppInfo>,
    val hasMore: Boolean,
)
