package top.app.market.data.install.backend

import android.content.pm.PackageInstaller
import top.app.market.domain.model.installer.InstallerMode
import java.io.Closeable

internal interface InstallerBackend {
    val mode: InstallerMode
    suspend fun open(): PackageInstallerAccess
}

internal class PackageInstallerAccess(
    val packageInstaller: PackageInstaller,
    val installerPackageName: String,
    private val sessionWrapper: (PackageInstaller.Session) -> Unit = {},
    private val closeAction: () -> Unit = {},
) : Closeable {
    fun prepare(session: PackageInstaller.Session) = sessionWrapper(session)
    override fun close() = closeAction()
}

internal class InstallerBackendSelector(
    standard: StandardInstallerBackend,
    root: RootInstallerBackend,
    shizuku: ShizukuInstallerBackend,
) {
    private val backends = listOf(standard, root, shizuku).associateBy { it.mode }

    fun get(mode: InstallerMode): InstallerBackend =
        requireNotNull(backends[mode]) { "No installer backend registered for $mode" }
}

internal const val XIAOMI_MARKET_PACKAGE_NAME = "com.xiaomi.market"
