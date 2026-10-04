package top.app.market.data.install.backend.root

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import top.app.market.data.install.InstallPipelineException
import top.app.market.domain.model.install.InstallFailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

internal class RootBinderBridge(
    private val context: Context,
) {
    suspend fun open(): RootBinderHandle = withContext(Dispatchers.IO) {
        val token = UUID.randomUUID().toString()
        val result = CompletableDeferred<IBinder>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_ROOT_BRIDGE_READY) return
                if (intent.getStringExtra(EXTRA_TOKEN) != token) return
                intent.extras?.getBinder(EXTRA_BINDER)?.let { result.complete(it) }
            }
        }
        val filter = IntentFilter(ACTION_ROOT_BRIDGE_READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        var process: Process? = null
        try {
            process = startRootProcess(token)
            val manager = withTimeout(15_000L.milliseconds) { result.await() }
            RootBinderHandle(manager, process)
        } catch (error: TimeoutCancellationException) {
            process?.destroy()
            throw InstallPipelineException(
                InstallFailureCode.ROOT_UNAVAILABLE,
                "Root service did not respond",
                error,
            )
        } catch (error: CancellationException) {
            process?.destroy()
            throw error
        } catch (error: Throwable) {
            process?.destroy()
            throw InstallPipelineException(
                InstallFailureCode.ROOT_UNAVAILABLE,
                error.message ?: "Unable to start root service",
                error,
            )
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun startRootProcess(token: String): Process {
        val command = listOf(
            "/system/bin/app_process",
            "-Djava.class.path=${context.packageCodePath}",
            "/system/bin",
            "--nice-name=${context.packageName}:root_installer",
            RootBridgeMain::class.java.name,
            context.packageName,
            token,
        ).joinToString(" ") { shellQuote(it) }
        return ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
            .also { process ->
                Thread({
                    try {
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { } }
                    } catch (_: IOException) {
                        // Closing the root process interrupts this blocking read during normal cleanup.
                    }
                }, "RootBridgeOutput").apply {
                    isDaemon = true
                    start()
                }
            }
    }

    private fun shellQuote(value: String): String =
        if (value.all { it.isLetterOrDigit() || it in "-._/:=" }) value
        else "'${value.replace("'", "'\\''")}'"
}

internal class RootBinderHandle(
    private val manager: IBinder,
    private val process: Process,
) : Closeable {
    fun wrap(target: IBinder): IBinder = RootBinderProxy(manager, target)

    override fun close() {
        runCatching {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(ROOT_BRIDGE_DESCRIPTOR)
                manager.transact(TRANSACTION_EXIT, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        }
        runCatching { process.outputStream.close() }
        runCatching { process.destroy() }
    }
}

private class RootBinderProxy(
    private val manager: IBinder,
    private val target: IBinder,
) : Binder() {
    override fun getInterfaceDescriptor(): String? = runCatching { target.interfaceDescriptor }.getOrNull()
    override fun pingBinder(): Boolean = target.pingBinder()
    override fun isBinderAlive(): Boolean = target.isBinderAlive
    override fun queryLocalInterface(descriptor: String): IInterface? = null

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val request = Parcel.obtain()
        return try {
            request.writeInterfaceToken(ROOT_BRIDGE_DESCRIPTOR)
            request.writeStrongBinder(target)
            request.writeInt(code)
            request.writeInt(flags)
            request.appendFrom(data, 0, data.dataSize())
            manager.transact(TRANSACTION_PROXY, request, reply, 0)
        } finally {
            request.recycle()
        }
    }

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = target.linkToDeath(recipient, flags)
    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
        target.unlinkToDeath(recipient, flags)
}

internal const val ROOT_BRIDGE_DESCRIPTOR = "top.app.market.install.IRootBinderBridge"
internal const val TRANSACTION_PROXY = IBinder.FIRST_CALL_TRANSACTION
internal const val TRANSACTION_EXIT = IBinder.FIRST_CALL_TRANSACTION + 1
internal const val ACTION_ROOT_BRIDGE_READY = "top.app.market.action.ROOT_BRIDGE_READY"
internal const val EXTRA_TOKEN = "root_bridge_token"
internal const val EXTRA_BINDER = "root_bridge_binder"
