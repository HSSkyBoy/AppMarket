package top.app.market.data.install.backend

import top.app.market.data.install.backend.root.RootBinderBridge
import top.app.market.domain.model.installer.InstallerMode

internal class RootInstallerBackend(
    private val factory: PrivilegedPackageInstallerFactory,
    private val rootBridge: RootBinderBridge,
) : InstallerBackend {
    override val mode: InstallerMode = InstallerMode.ROOT

    override suspend fun open(callerPackageName: String): PackageInstallerAccess {
        val bridge = rootBridge.open()
        return try {
            factory.create(
                wrapBinder = bridge::wrap,
                callerPackageName = callerPackageName,
                closeAction = bridge::close,
            )
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
    }
}
