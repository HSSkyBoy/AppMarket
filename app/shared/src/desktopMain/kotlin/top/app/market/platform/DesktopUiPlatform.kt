package top.app.market.platform

class DesktopUiPlatform : UiPlatform {
    override val packageInstallationSupported: Boolean = false

    override fun showToast(message: String) {
        println("[toast] $message")
    }

    override fun openAppSettings(): Boolean = false

    override fun openUnknownSourcesSettings(): Boolean = false

    override fun requestInstalledAppsPermission(onResult: (Boolean) -> Unit): Boolean = false

    override fun requestPostNotificationsPermission(onResult: () -> Unit): Boolean {
        onResult()
        return true
    }

    override suspend fun saveImageToPictures(url: String, fileName: String): ImageSaveResult =
        ImageSaveResult.Unsupported
}
