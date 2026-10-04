package top.app.market.data.install.platform

import android.content.IntentSender

interface InstallTaskLauncher {
    fun start(taskId: String)
}

interface SavedPackageInstallLauncher {
    suspend fun installSavedPackage(savedPackageId: String)
}

interface InstallStatusIntentFactory {
    fun create(taskId: String, sessionId: Int, packageName: String, displayName: String): IntentSender
}

interface ExternalInstallerLauncher {
    fun launch(packageName: String, artifactUris: List<String>)
}

interface InstallTaskProcessor {
    suspend fun process(taskId: String)
    suspend fun onUnhandledFailure(taskId: String, error: Throwable)
    fun cancelTask(taskId: String)
}

interface InstallResultHandler {
    suspend fun onPendingUserAction(taskId: String)
    suspend fun onResult(taskId: String, status: Int, message: String?)
    suspend fun onPackageChanged(packageName: String)
}
