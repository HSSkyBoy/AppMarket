package top.app.market.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

const val GET_INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

class AndroidPermissionCoordinator(private val context: Context) {
    private var installedAppsLauncher: ActivityResultLauncher<String>? = null
    private var installedAppsCallback: ((Boolean) -> Unit)? = null
    private var pendingInstalledAppsResumeCheck = false
    private var notificationsLauncher: ActivityResultLauncher<String>? = null
    private var notificationsCallback: (() -> Unit)? = null

    fun registerInstalledApps(launcher: ActivityResultLauncher<String>) {
        installedAppsLauncher = launcher
    }

    fun unregisterInstalledApps(launcher: ActivityResultLauncher<String>) {
        if (installedAppsLauncher === launcher) {
            installedAppsLauncher = null
            installedAppsCallback = null
            pendingInstalledAppsResumeCheck = false
        }
    }

    fun requestInstalledApps(onResult: (Boolean) -> Unit): Boolean {
        if (hasPermission(GET_INSTALLED_APPS_PERMISSION)) {
            onResult(true)
            return true
        }
        val launcher = installedAppsLauncher ?: return false
        installedAppsCallback = onResult
        pendingInstalledAppsResumeCheck = true
        return runCatching { launcher.launch(GET_INSTALLED_APPS_PERMISSION) }
            .onFailure {
                installedAppsCallback = null
                pendingInstalledAppsResumeCheck = false
            }.isSuccess
    }

    fun dispatchInstalledAppsResult(granted: Boolean) {
        pendingInstalledAppsResumeCheck = false
        val callback = installedAppsCallback
        installedAppsCallback = null
        callback?.invoke(granted)
    }

    fun checkInstalledAppsOnResume() {
        if (pendingInstalledAppsResumeCheck && hasPermission(GET_INSTALLED_APPS_PERMISSION)) {
            dispatchInstalledAppsResult(true)
        }
    }

    fun registerNotifications(launcher: ActivityResultLauncher<String>) {
        notificationsLauncher = launcher
    }

    fun unregisterNotifications(launcher: ActivityResultLauncher<String>) {
        if (notificationsLauncher === launcher) {
            notificationsLauncher = null
            notificationsCallback = null
        }
    }

    fun requestNotifications(onResult: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            onResult()
            return true
        }
        val launcher = notificationsLauncher ?: return false
        notificationsCallback = onResult
        return runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure { notificationsCallback = null }
            .isSuccess
    }

    fun dispatchNotificationsResult() {
        val callback = notificationsCallback ?: return
        notificationsCallback = null
        callback()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
