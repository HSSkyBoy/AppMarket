package top.app.market.data.install

import top.app.market.data.install.network.ArtifactSourceReader
import top.app.market.data.install.platform.ExternalInstallerLauncher
import top.app.market.data.install.storage.MediaStorePackageStore
import top.app.market.domain.model.install.InstallEvent
import top.app.market.domain.model.install.InstallFailureCode
import top.app.market.domain.model.install.InstallRequest
import top.app.market.domain.model.installer.InstallerMode
import top.app.market.domain.repository.InstallerPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class ThirdPartyInstallEngine(
    private val sourceReader: ArtifactSourceReader,
    private val stagingDownloader: PackageStagingDownloader,
    private val packageStore: MediaStorePackageStore,
    private val launcher: ExternalInstallerLauncher,
    private val preferences: InstallerPreferencesRepository,
) {
    fun install(request: InstallRequest, installerPackageName: String): Flow<InstallEvent> = channelFlow {
        var pendingPackage: MediaStorePackageStore.PendingSavedPackage? = null
        var published = false
        try {
            send(InstallEvent.Preparing)
            val existingPackage = request.sourceSavedPackageId?.let { packageStore.get(it) }
            if (request.sourceSavedPackageId != null && existingPackage == null) {
                throw InstallPipelineException(
                    InstallFailureCode.STORAGE,
                    "Saved package is unavailable",
                )
            }
            val savedPackage = existingPackage ?: run {
                val staged = stagingDownloader.stage(request) { completed, total ->
                    send(InstallEvent.Progress(completed, total))
                }
                val pending = packageStore.createPending(request)
                pendingPackage = pending
                request.artifacts.forEachIndexed { index, artifact ->
                    pending.openOutput(index).use { output ->
                        sourceReader.copyTo(artifact, staged[index], listOf(output))
                        output.flush()
                    }
                }
                pending.publish().also {
                    published = true
                    stagingDownloader.clear(request.id)
                }
            }
            send(InstallEvent.Downloaded(savedPackage.id))
            send(
                InstallEvent.ExternalInstallerPrepared(
                    installerPackageName = installerPackageName,
                    savedPackageId = savedPackage.id,
                )
            )
            try {
                launcher.launch(
                    packageName = installerPackageName,
                    artifactUris = savedPackage.artifacts.map { it.uri },
                )
            } catch (error: Throwable) {
                preferences.setMode(InstallerMode.STANDARD)
                throw InstallPipelineException(
                    InstallFailureCode.THIRD_PARTY_UNAVAILABLE,
                    error.message ?: "Selected package installer is unavailable",
                    error,
                )
            }
            send(
                InstallEvent.ExternalInstallerLaunched(
                    installerPackageName = installerPackageName,
                    savedPackageId = savedPackage.id,
                )
            )
        } finally {
            if (!published) {
                withContext(NonCancellable) { runCatching { pendingPackage?.rollback() } }
            }
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        val failure = error.toInstallFailure()
        emit(InstallEvent.Failed(failure.code, failure.message ?: "Installation failed"))
    }.flowOn(Dispatchers.IO)
}
