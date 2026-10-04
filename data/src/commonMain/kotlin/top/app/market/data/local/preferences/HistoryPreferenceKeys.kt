package top.app.market.data.local.preferences

import top.app.market.data.local.StringPreferenceKey

object HistoryPreferenceKeys {
    val Search = StringPreferenceKey("market_search_history", "history")
    val Updates = StringPreferenceKey("market_update_history", "history")
    val PendingUpdates = StringPreferenceKey("market_update_history", "pending")
}
