package top.app.market.platform

import androidx.compose.runtime.Composable

expect fun isPredictiveBackSupported(): Boolean
expect fun isBlurSettingSupported(): Boolean

@Composable
expect fun ApplyPredictiveBackPreference(enabled: Boolean)
