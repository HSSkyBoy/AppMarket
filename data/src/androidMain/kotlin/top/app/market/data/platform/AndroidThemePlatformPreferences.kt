package top.app.market.data.platform

import android.content.Context

internal class AndroidThemePlatformPreferences(
    private val context: Context,
) : ThemePlatformPreferences {
    override fun setPredictiveBackEnabled(enabled: Boolean) {
        context.getSharedPreferences(ThemePlatformPreferenceContract.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ThemePlatformPreferenceContract.PREDICTIVE_BACK_KEY, enabled)
            .apply()
    }
}
