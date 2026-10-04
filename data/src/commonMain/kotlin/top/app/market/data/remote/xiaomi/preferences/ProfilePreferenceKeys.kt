package top.app.market.data.remote.xiaomi.preferences

import top.app.market.data.local.StringPreferenceKey
import top.app.market.domain.model.market.AppSource

object ProfilePreferenceKeys {
    private const val NS = "market_profile"

    fun source(appSource: AppSource) = StringPreferenceKey(NS, "profile_source_${appSource.token}")
    fun overrides(appSource: AppSource) = StringPreferenceKey(NS, "profile_overrides_${appSource.token}")
    fun currentTemplate(appSource: AppSource) =
        StringPreferenceKey(NS, "profile_current_template_${appSource.token}")

    fun field(appSource: AppSource, name: String) =
        StringPreferenceKey(NS, "profile_field_${appSource.token}_$name")

    val Templates = StringPreferenceKey(NS, "profile_templates")
    val SyncedWebResource = StringPreferenceKey(NS, "synced_web_res_version")
    val SyncedPageConfig = StringPreferenceKey(NS, "synced_page_config_version")
    val LastServerSync = StringPreferenceKey(NS, "last_server_sync_time")
    val OppoUserRegion = StringPreferenceKey(NS, "oppo_china_user_region")
    val OppoSystemLocale = StringPreferenceKey(NS, "oppo_china_system_locale")
    val OppoSupportedLocales = StringPreferenceKey(NS, "oppo_china_supported_locales")
    val OppoLocale = StringPreferenceKey(NS, "oppo_china_locale")
    val SamsungCountryCode = StringPreferenceKey(NS, "samsung_china_country_code")
    val SamsungLanguage = StringPreferenceKey(NS, "samsung_china_language")
    val SamsungMcc = StringPreferenceKey(NS, "samsung_china_mcc")
    val SamsungMnc = StringPreferenceKey(NS, "samsung_china_mnc")
    val SamsungCsc = StringPreferenceKey(NS, "samsung_china_csc")
}
