package top.app.market.data.install.backend

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process

internal class PrivilegedPackageInstallerFactory(
    private val context: Context,
) {
    fun create(
        wrapBinder: (IBinder) -> IBinder,
        callerPackageName: String,
        closeAction: () -> Unit = {},
    ): PackageInstallerAccess {
        val packageManagerBinder = serviceManagerGetService("package")
        val packageManager = asInterface("android.content.pm.IPackageManager", wrapBinder(packageManagerBinder))
        val packageInstallerInterface = packageManager.javaClass.methods
            .firstOrNull { it.name == "getPackageInstaller" && it.parameterCount == 0 }
            ?.invoke(packageManager) as? IInterface
            ?: error("Unable to resolve IPackageInstaller")
        val packageInstaller = asInterface(
            "android.content.pm.IPackageInstaller",
            wrapBinder(packageInstallerInterface.asBinder()),
        )
        val installer = constructPackageInstaller(packageInstaller, callerPackageName)
        return PackageInstallerAccess(
            packageInstaller = installer,
            installerPackageName = callerPackageName,
            sessionWrapper = { session -> wrapSession(session, wrapBinder) },
            closeAction = closeAction,
        )
    }

    @SuppressLint("PrivateApi")
    private fun constructPackageInstaller(installer: IInterface, callerPackageName: String): PackageInstaller {
        val installerClass = Class.forName("android.content.pm.IPackageInstaller")
        val userId = Process.myUid() / 100000
        val constructor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PackageInstaller::class.java.getDeclaredConstructor(
                installerClass,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        } else {
            PackageInstaller::class.java.getDeclaredConstructor(
                installerClass,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        }
        constructor.isAccessible = true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            constructor.newInstance(installer, callerPackageName, null, userId) as PackageInstaller
        } else {
            constructor.newInstance(installer, callerPackageName, userId) as PackageInstaller
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun wrapSession(session: PackageInstaller.Session, wrapBinder: (IBinder) -> IBinder) {
        val field = PackageInstaller.Session::class.java.getDeclaredField("mSession").apply { isAccessible = true }
        val current = field.get(session) as IInterface
        val wrapped = asInterface("android.content.pm.IPackageInstallerSession", wrapBinder(current.asBinder()))
        field.set(session, wrapped)
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun serviceManagerGetService(name: String): IBinder {
        val serviceManager = Class.forName("android.os.ServiceManager")
        return serviceManager.getDeclaredMethod("getService", String::class.java)
            .invoke(null, name) as IBinder
    }

    private fun asInterface(interfaceName: String, binder: IBinder): IInterface {
        val stub = Class.forName("$interfaceName\$Stub")
        return stub.getDeclaredMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) as IInterface
    }
}
