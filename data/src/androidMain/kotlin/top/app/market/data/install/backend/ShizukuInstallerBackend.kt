package top.app.market.data.install.backend

import android.content.pm.PackageManager
import top.app.market.data.install.InstallPipelineException
import top.app.market.domain.model.install.InstallFailureCode
import top.app.market.domain.model.installer.InstallerMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

internal class ShizukuInstallerBackend(
    private val factory: PrivilegedPackageInstallerFactory,
) : InstallerBackend {
    override val mode: InstallerMode = InstallerMode.SHIZUKU

    override suspend fun open(callerPackageName: String): PackageInstallerAccess {
        requirePermission()
        return factory.create(
            wrapBinder = { binder -> ShizukuBinderWrapper(binder) },
            callerPackageName = callerPackageName,
        )
    }

    private suspend fun requirePermission() {
        if (!Shizuku.pingBinder()) {
            throw InstallPipelineException(
                InstallFailureCode.SHIZUKU_UNAVAILABLE,
                "Shizuku service is not running",
            )
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return

        val requestCode = Random.nextInt()
        val result = CompletableDeferred<Int>()
        val listener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
            if (code == requestCode) result.complete(grantResult)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(requestCode)
            val grantResult = try {
                withTimeout(60_000L.milliseconds) { result.await() }
            } catch (error: TimeoutCancellationException) {
                throw InstallPipelineException(
                    InstallFailureCode.SHIZUKU_PERMISSION_DENIED,
                    "Shizuku permission request timed out",
                    error,
                )
            }
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                throw InstallPipelineException(
                    InstallFailureCode.SHIZUKU_PERMISSION_DENIED,
                    "Shizuku permission was denied",
                )
            }
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }
}
