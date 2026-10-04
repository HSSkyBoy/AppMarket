package top.app.market.platform

interface UiPlatform {
    val packageInstallationSupported: Boolean
    fun showToast(message: String)
    fun openAppSettings(): Boolean
    fun openUnknownSourcesSettings(): Boolean
    fun openInOtherAppStore(packageName: String): Boolean = false
    fun requestInstalledAppsPermission(onResult: (Boolean) -> Unit): Boolean
    fun requestPostNotificationsPermission(onResult: () -> Unit): Boolean
    suspend fun saveImageToPictures(url: String, fileName: String): ImageSaveResult
}
