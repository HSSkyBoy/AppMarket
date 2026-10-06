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

    /** 读取剪贴板文本；剪贴板自上次读取后没有变化时返回 null，避免重复读取（Android 每次读取会提示用户）。 */
    suspend fun readNewClipboardText(): String? = null
}
