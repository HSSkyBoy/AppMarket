package top.app.market.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
actual fun isPredictiveBackSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
actual fun isBlurSettingSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

@Composable
actual fun ApplyPredictiveBackPreference(enabled: Boolean) {
    if (!isPredictiveBackSupported()) return
    val context = LocalContext.current
    val activity = context.findActivity()
    val appliedEnabled = remember { mutableStateOf(enabled) }
    LaunchedEffect(enabled) {
        if (enabled == appliedEnabled.value) return@LaunchedEffect
        setEnableOnBackInvokedCallback(context.applicationContext.applicationInfo, enabled)
        appliedEnabled.value = enabled
        activity?.recreate()
    }
}

private fun setEnableOnBackInvokedCallback(applicationInfo: ApplicationInfo, enabled: Boolean) {
    runCatching {
        val method = ApplicationInfo::class.java.getDeclaredMethod(
            "setEnableOnBackInvokedCallback",
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(applicationInfo, enabled)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
