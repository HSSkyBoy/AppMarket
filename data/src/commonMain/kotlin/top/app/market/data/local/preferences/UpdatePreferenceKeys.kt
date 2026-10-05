package top.app.market.data.local.preferences

import top.app.market.data.local.BooleanPreferenceKey
import top.app.market.data.local.StringPreferenceKey

object UpdatePreferenceKeys {
    private const val NS = "update_preferences"
    val ShowSystemUpdates = BooleanPreferenceKey(NS, "show_system_updates", true)
    val RemoveSearchAds = BooleanPreferenceKey(NS, "remove_search_ads")
    val FilterQuickGames = BooleanPreferenceKey(NS, "filter_quick_games")
    val FilterReservationApps = BooleanPreferenceKey(NS, "filter_reservation_apps")
    val ShowAppComments = BooleanPreferenceKey(NS, "show_app_comments")
    val StripAppNameSubtitle = BooleanPreferenceKey(NS, "strip_app_name_subtitle")
    val ShowSameDeveloper = BooleanPreferenceKey(NS, "show_same_developer")
    val ShowPromotions = BooleanPreferenceKey(NS, "show_promotions")
    val HomePage = StringPreferenceKey(NS, "home_page")
    val SearchSources = StringPreferenceKey(NS, "search_sources")
    val TodaySource = StringPreferenceKey(NS, "today_source")
    val CategorySource = StringPreferenceKey(NS, "category_source")
    val UpdateSource = StringPreferenceKey(NS, "update_source")
    val PermanentIgnores = StringPreferenceKey(NS, "permanent_ignores")
    val OnceIgnores = StringPreferenceKey(NS, "once_ignores")
    val CachedUpdates = StringPreferenceKey(NS, "cached_updates")
}
