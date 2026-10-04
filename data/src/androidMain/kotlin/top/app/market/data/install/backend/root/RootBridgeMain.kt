package top.app.market.data.install.backend.root

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import org.lsposed.hiddenapibypass.HiddenApiBypass

@Suppress("DEPRECATION")
internal object RootBridgeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        startParentWatchdog()

        val packageName = args[0]
        val token = args[1]
        val extras = Bundle().apply {
            putString(EXTRA_TOKEN, token)
            putBinder(EXTRA_BINDER, RootTransactionServer())
        }
        systemContext().sendBroadcast(
            Intent(ACTION_ROOT_BRIDGE_READY)
                .setPackage(packageName)
                .putExtras(extras)
        )
        Looper.loop()
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getDeclaredMethod("systemMain").invoke(null)
        return activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
    }

    private fun startParentWatchdog() {
        Thread({
            runCatching {
                val buffer = ByteArray(128)
                while (System.`in`.read(buffer) >= 0) {
                    // The pipe stays open while the parent app process is alive.
                }
            }
            Process.killProcess(Process.myPid())
        }, "RootBridgeWatchdog").apply {
            isDaemon = true
            start()
        }
    }
}

private class RootTransactionServer : Binder() {
    override fun getInterfaceDescriptor(): String = ROOT_BRIDGE_DESCRIPTOR

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == TRANSACTION_EXIT) {
            data.enforceInterface(ROOT_BRIDGE_DESCRIPTOR)
            Process.killProcess(Process.myPid())
            return true
        }
        if (code != TRANSACTION_PROXY) return super.onTransact(code, data, reply, flags)

        data.enforceInterface(ROOT_BRIDGE_DESCRIPTOR)
        val target = requireNotNull(data.readStrongBinder())
        val targetCode = data.readInt()
        val targetFlags = data.readInt()
        val targetData = Parcel.obtain()
        val identity = Binder.clearCallingIdentity()
        return try {
            targetData.appendFrom(data, data.dataPosition(), data.dataAvail())
            target.transact(targetCode, targetData, reply, targetFlags)
        } finally {
            restoreCallingIdentity(identity)
            targetData.recycle()
        }
    }
}
