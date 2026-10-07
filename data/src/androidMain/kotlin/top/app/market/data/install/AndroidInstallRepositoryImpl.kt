package top.app.market.data.install

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import top.app.market.data.install.backend.InstallerBackendSelector
import top.app.market.data.install.backend.PackageInstallerAccess
import top.app.market.data.install.network.ArtifactSourceReader
import top.app.market.data.install.platform.InstallStatusIntentFactory
import top.app.market.data.install.storage.MediaStorePackageStore
import top.app.market.data.platform.debugLog
import top.app.market.domain.model.install.InstallEvent
import top.app.market.domain.model.install.InstallRequest
import top.app.market.domain.model.install.InstallSource
import top.app.market.domain.model.installer.InstallerAttributionResolver
import top.app.market.domain.model.installer.InstallerMode
import top.app.market.domain.repository.InstallRepository
import top.app.market.domain.repository.InstallerDiscoveryRepository
import top.app.market.domain.repository.InstallerPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal class AndroidInstallRepositoryImpl(
    private val context: Context,
    private val preferences: InstallerPreferencesRepository,
    private val backends: InstallerBackendSelector,
    private val sourceReader: ArtifactSourceReader,
    private val stagingDownloader: PackageStagingDownloader,
    private val packageStore: MediaStorePackageStore,
    private val statusIntentFactory: InstallStatusIntentFactory,
    private val discovery: InstallerDiscoveryRepository,
    private val thirdPartyInstaller: ThirdPartyInstallEngine,
) : InstallRepository {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    @SuppressLint("RequestInstallPackagesPolicy")
    override fun install(request: InstallRequest): Flow<InstallEvent> = channelFlow {
        require(request.artifacts.isNotEmpty()) { "Install request has no artifacts" }
        val job = currentCoroutineContext().job
        check(activeJobs.putIfAbsent(request.id, job) == null) { "Install task ${request.id} is already active" }

        var pendingPackage: MediaStorePackageStore.PendingSavedPackage? = null
        var published = false
        var session: PackageInstaller.Session? = null
        // openSession / prepare 可能在 session 句柄拿到之前抛错，回收必须以 id 为准
        var sessionId: Int? = null
        var committed = false
        var access: PackageInstallerAccess? = null
        try {
            var mode = preferences.mode()
            if (mode == InstallerMode.THIRD_PARTY) {
                val packageName = preferences.thirdPartyInstallerPackage()
                val isAvailable = packageName.isNotBlank() && runCatching {
                    discovery.listCandidates().any { it.packageName == packageName }
                }.getOrDefault(false)
                if (isAvailable) {
                    thirdPartyInstaller.install(request, packageName).collect { send(it) }
                    return@channelFlow
                }
                preferences.setMode(InstallerMode.STANDARD)
                mode = InstallerMode.STANDARD
            }
            send(InstallEvent.Preparing)
            // 第一阶段：远端 artifact 断点续传暂存；取消保留数据，恢复后继续传
            val staged = stagingDownloader.stage(request) { completed, total ->
                send(InstallEvent.Progress(completed, total))
            }
            // 第二阶段：本地数据写入安装会话；仅本地源（保存的安装包）在此阶段报进度
            val resolvedCallerPackage = resolveCallerPackageName(request)
            access = backends.get(mode).open(resolvedCallerPackage)
            pendingPackage = request.takeIf {
                it.saveToDownloads && it.sourceSavedPackageId == null
            }?.let { packageStore.createPending(it) }

            val params = createSessionParams(
                request = request,
                userActionNotRequired = shouldRequestUserActionNotRequired(mode),
                installerPackageName = access.installerPackageName,
            )
            val createdSessionId = access.packageInstaller.createSession(params)
            sessionId = createdSessionId
            session = access.packageInstaller.openSession(createdSessionId).also(access::prepare)

            // 写入会话 + 保存副本在大包上可达数十秒，不报进度用户会以为卡死
            val total = request.artifacts.map { it.transferSize }
                .takeIf { sizes -> sizes.all { it > 0L } }
                ?.sum()
                ?: -1L
            var completed = 0L
            var lastProgressMarker = -1L
            request.artifacts.forEachIndexed { index, artifact ->
                session.openWrite(artifact.name, 0L, artifact.size.takeIf { it > 0L } ?: -1L).use { sessionOutput ->
                    val savedOutput = pendingPackage?.openOutput(index)
                    savedOutput.use { savedOutput ->
                        val outputs = buildList {
                            add(sessionOutput)
                            savedOutput?.let(::add)
                        }
                        sourceReader.copyTo(artifact, staged[index], outputs) { count ->
                            completed += count
                            val marker = progressMarker(completed, total)
                            if (marker != lastProgressMarker) {
                                lastProgressMarker = marker
                                send(InstallEvent.Progress(completed, total))
                            }
                        }
                        savedOutput?.flush()
                        session.fsync(sessionOutput)
                    }
                }
            }

            val savedPackage = pendingPackage?.publish()
            published = savedPackage != null
            send(InstallEvent.SessionReady(createdSessionId, savedPackage?.id))
            session.commit(
                statusIntentFactory.create(request.id, createdSessionId, request.packageName, request.displayName)
            )
            committed = true
            stagingDownloader.clear(request.id)
            send(InstallEvent.Committed(createdSessionId, savedPackage?.id))
        } finally {
            withContext(NonCancellable) {
                runCatching { session?.close() }
                if (!committed) {
                    sessionId?.let { id -> runCatching { access?.packageInstaller?.abandonSession(id) } }
                }
                if (!published) runCatching { pendingPackage?.rollback() }
                runCatching { access?.close() }
                activeJobs.remove(request.id, job)
            }
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        val failure = error.toInstallFailure()
        emit(InstallEvent.Failed(failure.code, failure.message ?: "Installation failed"))
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(id: String) {
        activeJobs[id]?.cancel()
    }

    override suspend fun reclaimSessions(retainedSessionIds: Set<Int>) = withContext(Dispatchers.IO) {
        val sessions = runCatching { context.packageManager.packageInstaller.mySessions }.getOrNull() ?: return@withContext
        sessions.asSequence()
            .map { it.sessionId }
            .filter { it !in retainedSessionIds }
            .forEach { id ->
                runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                    .onSuccess { debugLog("Installer") { "Abandoned orphaned session $id" } }
            }
    }

    private fun progressMarker(completed: Long, total: Long): Long =
        if (total > 0L) (completed.coerceAtMost(total) * 100L) / total
        else completed / PROGRESS_STEP_BYTES

    private suspend fun resolveCallerPackageName(request: InstallRequest): String {
        val attributionMode = preferences.attributionMode()
        val customPackage = preferences.attributionCustomPackage()
        return InstallerAttributionResolver.resolve(
            mode = attributionMode,
            marketSource = request.marketSource,
            customPackage = customPackage,
            fallbackPackageName = context.packageName,
        )
    }

    private suspend fun shouldRequestUserActionNotRequired(mode: InstallerMode): Boolean {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            mode != InstallerMode.STANDARD ||
            !preferences.userActionNotRequiredConfigurable()
        ) {
            return false
        }
        return preferences.userActionNotRequiredEnabled()
    }

    private fun createSessionParams(
        request: InstallRequest,
        userActionNotRequired: Boolean,
        installerPackageName: String,
    ): PackageInstaller.SessionParams =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(request.packageName)
            request.artifacts.map { it.size }
                .takeIf { sizes -> sizes.isNotEmpty() && sizes.all { it > 0L } }
                ?.sum()
                ?.let(::setSize)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            request.artifacts.firstNotNullOfOrNull { artifact ->
                when (val source = artifact.source) {
                    is InstallSource.Remote -> source.url
                    is InstallSource.Delta -> source.patch.url
                    is InstallSource.Local -> null
                }
            }?.let { setOriginatingUri(Uri.parse(it)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (userActionNotRequired) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                setInstallScenario(PackageManager.INSTALL_SCENARIO_FAST)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setInstallerPackageName(installerPackageName)
            }
            // MODE_FULL_INSTALL 本身已是替换语义，隐藏字段不可访问时不应连累整次安装
            runCatching { enableReplaceExisting() }
        }

    private companion object {
        const val PROGRESS_STEP_BYTES = 1024L * 1024L
    }
}

@SuppressLint("DiscouragedPrivateApi")
private fun PackageInstaller.SessionParams.enableReplaceExisting() {
    val installFlagsField = PackageInstaller.SessionParams::class.java
        .getDeclaredField("installFlags")
        .apply { isAccessible = true }
    installFlagsField.setInt(
        this,
        installFlagsField.getInt(this) or INSTALL_REPLACE_EXISTING,
    )
}

// PackageManager.INSTALL_REPLACE_EXISTING is hidden from the public SDK.
private const val INSTALL_REPLACE_EXISTING = 0x00000002
