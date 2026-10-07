package top.app.market.data.local.preferences

import top.app.market.data.local.BooleanPreferenceKey
import top.app.market.data.local.StringPreferenceKey

private const val Namespace = "installer_prefs"

internal object InstallerPreferenceKeys {
    val Mode = StringPreferenceKey(Namespace, "mode")
    val ThirdPartyInstallerPackage = StringPreferenceKey(Namespace, "custom_pkg")
    val SaveToDownloads = BooleanPreferenceKey(Namespace, "save_to_downloads", default = false)
    val AutoLaunchConfirmUi = BooleanPreferenceKey(Namespace, "auto_launch_confirm_ui", default = true)
    val SaveToDownloadsMigrated = BooleanPreferenceKey(Namespace, "save_to_downloads_migrated")
    val LegacyDeleteAfterInstall = BooleanPreferenceKey(Namespace, "delete_after_install")
    val UserActionNotRequired = BooleanPreferenceKey(Namespace, "user_action_not_required", default = false)
    val DeltaUpdate = BooleanPreferenceKey(Namespace, "delta_update", default = true)
    val DeltaFallbackNotice = BooleanPreferenceKey(Namespace, "delta_fallback_notice", default = true)
    val XiaomiIslandOptimization = BooleanPreferenceKey(Namespace, "xiaomi_island_optimization")
    val AttributionMode = StringPreferenceKey(Namespace, "attribution_mode")
    val AttributionCustomPackage = StringPreferenceKey(Namespace, "attribution_custom_pkg")
}
