package top.app.market.data.remote.xiaomi.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.platform.md5
import top.app.market.data.remote.xiaomi.preferences.XiaomiIdentityPreferenceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

internal class AndroidXiaomiDeviceIdentityDataSource(
    private val preferences: PreferencesDataSource,
    private val context: Context,
) : XiaomiDeviceIdentityDataSource {

    private val identityStore = XiaomiIdentityStore(preferences)

    @Volatile
    private var oaIdCache: String? = null
    private val firstInstallTime: Long by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(System.currentTimeMillis())
    }
    private val cachedXmsfVersion: String by lazy { packageVersionCode(context, "com.xiaomi.xmsf") }

    private class CachedIdentity(val value: XiaomiDeviceIdentity, val expiresAt: Long)

    private val identityMutex = Mutex()

    @Volatile
    private var cachedIdentity: CachedIdentity? = null

    /** 重建身份要绑 binder 服务并等签名回调（可达数十秒），故内存缓存 + 单飞。 */
    override suspend fun identity(): XiaomiDeviceIdentity {
        fresh()?.let { return it.withCurrentServerDctx() }
        return identityMutex.withLock {
            fresh() ?: buildIdentity().also {
                cachedIdentity = CachedIdentity(it, System.currentTimeMillis() + IDENTITY_CACHE_TTL_MS)
            }
        }.withCurrentServerDctx()
    }

    private suspend fun XiaomiDeviceIdentity.withCurrentServerDctx(): XiaomiDeviceIdentity =
        copy(dctx = identityStore.cachedServerDctx())

    private fun fresh(): XiaomiDeviceIdentity? =
        cachedIdentity?.takeIf { it.expiresAt > System.currentTimeMillis() }?.value

    private suspend fun buildIdentity(): XiaomiDeviceIdentity = withContext(Dispatchers.IO) {
        val ageMs = (System.currentTimeMillis() - firstInstallTime).coerceAtLeast(0L)
        val ageDays = TimeUnit.MILLISECONDS.toDays(ageMs).coerceAtLeast(1L).toString()
        val debug = mutableListOf<String>()
        val deviceId = identityStore.cachedSecurityDeviceId().ifBlank {
            securityDeviceId(context, debug).also { identityStore.saveSystemDctx(it) }
        }
        val passportToken = if (deviceId.isNotBlank()) passportSignServiceToken(context, debug) else null
        val tzToken = trustZoneToken(context, deviceId, debug)
        XiaomiDeviceIdentity(
            oaId = oaId(),
            activeTimeInterval = ageMs.coerceAtLeast(1L).toString(),
            installDay = ageDays,
            launchDay = ageDays,
            dctx = identityStore.cachedServerDctx(),
            xmsfVersion = cachedXmsfVersion,
            tzNonce = tzToken?.nonce ?: passportToken?.nonce.orEmpty(),
            tzSign = tzToken?.sign ?: passportToken?.sign.orEmpty(),
            debug = (debug + "fid=${deviceId.isNotBlank()}" + "tz=${tzToken != null || passportToken != null}").joinToString("|").take(480),
        )
    }

    private suspend fun oaId(): String {
        oaIdCache?.let { return it }
        return realOaId(context).ifBlank { stableOaId(fallbackOaIdSeed()) }.also { oaIdCache = it }
    }

    // 小米内置 com.android.id.impl.IdProviderImpl，反射直取真实 OAID；非小米设备无此类，走随机种子兜底。
    private fun realOaId(context: Context): String =
        runCatching {
            val clazz = Class.forName("com.android.id.impl.IdProviderImpl")
            val impl = clazz.getDeclaredConstructor().newInstance()
            clazz.getMethod("getOAID", Context::class.java).invoke(impl, context) as? String
        }.getOrNull().orEmpty()

    // Persisted random seed instead of ANDROID_ID, to avoid reading hardware identifiers.
    private suspend fun fallbackOaIdSeed(): String {
        preferences.read(XiaomiIdentityPreferenceKeys.FallbackOaIdSeed)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return java.util.UUID.randomUUID().toString()
            .also { preferences.put(XiaomiIdentityPreferenceKeys.FallbackOaIdSeed, it) }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun stableOaId(seed: String): String {
        val source = seed.takeIf { it.isNotBlank() } ?: context.packageName
        return md5("oaid:$source".encodeToByteArray()).toHexString().take(16)
    }

    private fun securityDeviceId(context: Context, debug: MutableList<String>? = null): String {
        val client = SecurityDeviceCredentialClient.connect(context, debug) ?: return ""
        return try {
            if (!client.isThisDeviceSupported()) {
                debug?.add("binder:supported=false")
                ""
            } else {
                client.getSecurityDeviceId()
            }
        } catch (e: Throwable) {
            debug?.add("binder.get:" + e.javaClass.simpleName)
            ""
        } finally {
            client.close()
        }
    }

    private data class TrustZoneToken(
        val nonce: String,
        val sign: String,
    )

    private data class PassportSignToken(
        val nonce: String,
        val sign: String,
        val deviceId: String,
    )

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun trustZoneToken(context: Context, deviceId: String, debug: MutableList<String>? = null): TrustZoneToken? {
        if (deviceId.isBlank()) return null
        cachedTrustZoneToken()?.let { return it }
        val credentialToken = if (SecurityDeviceCredentialClient.hasBindableService(context)) {
            credentialManagerToken(context, deviceId, debug)
        } else {
            debug?.add("sdcService:false")
            null
        }
        return credentialToken ?: passportTrustZoneToken(context, deviceId, debug)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun credentialManagerToken(context: Context, deviceId: String, debug: MutableList<String>? = null): TrustZoneToken? {
        val token = credentialManagerTokenFromBinder(context, deviceId, debug) ?: return null
        cacheTrustZoneToken(token)
        return token
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun credentialManagerTokenFromBinder(context: Context, deviceId: String, debug: MutableList<String>? = null): TrustZoneToken? {
        val client = SecurityDeviceCredentialClient.connect(context, debug) ?: return null
        return try {
            if (!client.isThisDeviceSupported()) {
                debug?.add("binderSign:supported=false")
                null
            } else {
                val nonce = System.currentTimeMillis().toString()
                val payload = "$deviceId,$nonce".encodeToByteArray()
                val sign = client.sign(1, payload, true)?.toHexString().orEmpty()
                TrustZoneToken(nonce = nonce, sign = sign).takeIf { it.sign.isNotBlank() }
            }
        } catch (e: Throwable) {
            debug?.add("binder.sign:" + e.javaClass.simpleName)
            null
        } finally {
            client.close()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun passportTrustZoneToken(context: Context, deviceId: String, debug: MutableList<String>? = null): TrustZoneToken? {
        val result = runCatching {
            val nonce = System.currentTimeMillis().toString()
            val params = "$deviceId,$nonce"
            val md5Params = md5(params.encodeToByteArray()).toHexString()
            val clz = Class.forName("com.xiaomi.passport.SecurityDeviceSignManager")
            val future = clz.getMethod(
                "sign",
                Context::class.java,
                String::class.java,
                Bundle::class.java,
            ).invoke(null, context, md5Params, null) as? java.util.concurrent.Future<*>
            val bundle = future?.get(5, TimeUnit.SECONDS) as? Bundle ?: return@runCatching null
            val sign = if (bundle.getBoolean("booleanResult", false)) {
                bundle.getString("userData").orEmpty()
            } else {
                val code = bundle.getInt("errorCode")
                debug?.add("passportSign:$code")
                ""
            }
            TrustZoneToken(nonce = nonce, sign = sign).takeIf { it.sign.isNotBlank() }
        }
        val token = result.getOrNull()
        if (token != null) {
            cacheTrustZoneToken(token)
        } else {
            debug?.add("passportSign:" + (result.exceptionOrNull()?.javaClass?.simpleName ?: "blank"))
        }
        return token
    }

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun passportSignServiceToken(context: Context, debug: MutableList<String>? = null): PassportSignToken? {
        cachedPassportSignServiceToken()?.let { return it }
        val nonce = System.currentTimeMillis().toString()
        val params = md5("app-market,$nonce".encodeToByteArray()).toHexString()
        val result = PassportSignServiceClient.sign(
            context = context,
            data = params,
            packageName = context.packageName,
            options = Bundle(),
            debug = debug,
        )
        if (result == null) return null
        val ok = result.getBoolean("booleanResult", false)
        val sign = result.getString("userData").orEmpty()
        val deviceId = result.getString("deviceId").orEmpty()
        debug?.add(
            "passportSvc:ok=$ok,code=${result.getInt("errorCode", -1)}," +
                    "sign=${sign.isNotBlank()},device=${deviceId.isNotBlank()}"
        )
        if (deviceId.isNotBlank()) {
            identityStore.saveSystemDctx(deviceId)
        }
        if (!ok || sign.isBlank()) return null
        return PassportSignToken(nonce = nonce, sign = sign, deviceId = deviceId).also {
            cacheTrustZoneToken(TrustZoneToken(it.nonce, it.sign))
        }
    }

    private suspend fun cachedPassportSignServiceToken(): PassportSignToken? {
        val token = cachedTrustZoneToken() ?: return null
        val deviceId = identityStore.cachedSecurityDeviceId()
        return PassportSignToken(token.nonce, token.sign, deviceId)
    }

    private suspend fun cachedTrustZoneToken(): TrustZoneToken? {
        val sign = preferences.read(XiaomiIdentityPreferenceKeys.TrustZoneSign).orEmpty()
        val createTime = preferences.read(XiaomiIdentityPreferenceKeys.TrustZoneSignCreateTime)?.toLongOrNull() ?: 0L
        if (sign.isBlank() || createTime <= 0L) return null
        if (System.currentTimeMillis() - createTime >= 1.hours.inWholeMilliseconds) return null
        return TrustZoneToken(nonce = createTime.toString(), sign = sign)
    }

    private suspend fun cacheTrustZoneToken(token: TrustZoneToken) {
        preferences.put(XiaomiIdentityPreferenceKeys.TrustZoneSign, token.sign)
        preferences.put(XiaomiIdentityPreferenceKeys.TrustZoneSignCreateTime, token.nonce)
    }

    private class SecurityDeviceCredentialClient private constructor(
        private val context: Context,
        private val binder: IBinder,
        private val connection: android.content.ServiceConnection,
    ) : AutoCloseable {
        fun isThisDeviceSupported(): Boolean =
            transact(1) { reply -> reply.readInt() != 0 }

        fun getSecurityDeviceId(): String =
            transact(2) { reply -> reply.readString().orEmpty() }

        fun sign(keyType: Int, data: ByteArray, reloadIfNecessary: Boolean): ByteArray? =
            transact(3, write = { dataParcel ->
                dataParcel.writeInt(keyType)
                dataParcel.writeByteArray(data)
                dataParcel.writeInt(if (reloadIfNecessary) 1 else 0)
            }) { reply ->
                reply.createByteArray()
            }

        private fun <T> transact(
            code: Int,
            write: (Parcel) -> Unit = {},
            read: (Parcel) -> T,
        ): T {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                write(data)
                binder.transact(code, data, reply, 0)
                reply.readException()
                return read(reply)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun close() {
            runCatching { context.unbindService(connection) }
        }

        companion object {
            private const val DESCRIPTOR = "com.xiaomi.security.devicecredential.ISecurityDeviceCredentialManager"
            private val intents = listOf(
                android.content.Intent("com.xiaomi.account.action.BIND_SECURITY_DEVICE_CREDENTIAL")
                    .setPackage("com.xiaomi.account"),
                android.content.Intent("com.xiaomi.finddevice.action.BIND_SECURITY_DEVICE_CREDENTIAL")
                    .setPackage("com.xiaomi.finddevice"),
            )

            fun connect(context: Context, debug: MutableList<String>? = null): SecurityDeviceCredentialClient? {
                val appContext = context.applicationContext
                for (intent in intents) {
                    if (!canResolveService(appContext, intent)) {
                        debug?.add("bind:${intent.`package`}:notFound")
                        continue
                    }
                    val bound = bindServiceBlocking(appContext, intent, 5, TimeUnit.SECONDS)
                    if (bound == null) {
                        debug?.add("bind:${intent.`package`}:failed")
                        continue
                    }
                    debug?.add("bind:${intent.`package`}:ok")
                    return SecurityDeviceCredentialClient(appContext, bound.binder, bound.connection)
                }
                return null
            }

            fun hasBindableService(context: Context): Boolean {
                val context = context.applicationContext
                return intents.any { canResolveService(context, it) }
            }
        }
    }

    private object PassportSignServiceClient {
        private const val SIGN_TIMEOUT_SECONDS = 8L
        private const val DESCRIPTOR = "com.xiaomi.passport.ISecurityDeviceSignService"
        private const val RESPONSE_DESCRIPTOR = "com.xiaomi.passport.ISecurityDeviceSignResponse"
        private val intent = android.content.Intent("com.xiaomi.account.action.SECURITY_DEVICE_SIGN")
            .setPackage("com.xiaomi.account")

        fun sign(
            context: Context,
            data: String,
            packageName: String,
            options: Bundle,
            debug: MutableList<String>? = null,
        ): Bundle? {
            val context = context.applicationContext
            if (!canResolveService(context, intent)) {
                debug?.add("passportSvc.bind:notFound")
                return null
            }
            val resultLatch = CountDownLatch(1)
            var resultBundle: Bundle? = null
            val callback = object : Binder(), IInterface {
                init {
                    attachInterface(this, RESPONSE_DESCRIPTOR)
                }

                override fun asBinder(): IBinder = this

                override fun onTransact(code: Int, dataParcel: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) {
                        reply?.writeString(RESPONSE_DESCRIPTOR)
                        return true
                    }
                    if (code != 1) return super.onTransact(code, dataParcel, reply, flags)
                    dataParcel.enforceInterface(RESPONSE_DESCRIPTOR)
                    resultBundle = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        dataParcel.readTypedObject(Bundle.CREATOR)
                    } else {
                        @Suppress("DEPRECATION")
                        dataParcel.readParcelable(Bundle::class.java.classLoader)
                    }
                    resultLatch.countDown()
                    return true
                }
            }
            val bound = bindServiceBlocking(context, intent, 5, TimeUnit.SECONDS)
            if (bound == null) {
                debug?.add("passportSvc.bind:failed")
                return null
            }
            try {
                val service = bound.binder
                val parcel = Parcel.obtain()
                try {
                    parcel.writeInterfaceToken(DESCRIPTOR)
                    parcel.writeStrongInterface(callback)
                    parcel.writeString(packageName)
                    parcel.writeString(data)
                    parcel.writeTypedObject(options, 0)
                    if (!service.transact(1, parcel, null, IBinder.FLAG_ONEWAY)) {
                        debug?.add("passportSvc.transact:false")
                        return null
                    }
                } finally {
                    parcel.recycle()
                }
                // tz 字段允许为空：等太久不如尽快让请求走，宁缺毋滞
                if (!resultLatch.await(SIGN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    debug?.add("passportSvc.result:timeout")
                    return null
                }
                return resultBundle
            } catch (e: Throwable) {
                debug?.add("passportSvc:" + e.javaClass.simpleName)
                return null
            } finally {
                runCatching { context.unbindService(bound.connection) }
            }
        }
    }

    private companion object {
        const val IDENTITY_CACHE_TTL_MS = 10 * 60 * 1000L
    }
}

private fun canResolveService(context: Context, intent: android.content.Intent): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= 33) {
        context.packageManager.resolveService(
            intent,
            PackageManager.ResolveInfoFlags.of(0),
        ) != null
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.resolveService(intent, 0) != null
    }
}

private object ContextExecutor : Executor {
    override fun execute(command: Runnable) {
        Thread(command, "xiaomi-sdc-bind").start()
    }
}

private class BoundService(
    val binder: IBinder,
    val connection: android.content.ServiceConnection,
)

// Binds and blocks until connected; returns the binder plus connection (caller must unbind), or null.
private fun bindServiceBlocking(
    context: Context,
    intent: android.content.Intent,
    timeout: Long,
    unit: TimeUnit,
): BoundService? {
    val latch = CountDownLatch(1)
    var boundBinder: IBinder? = null
    val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            boundBinder = service
            latch.countDown()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            boundBinder = null
        }

        override fun onBindingDied(name: android.content.ComponentName?) {
            boundBinder = null
            latch.countDown()
        }

        override fun onNullBinding(name: android.content.ComponentName?) {
            boundBinder = null
            latch.countDown()
        }
    }
    val bound = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            context.bindService(intent, Context.BIND_AUTO_CREATE, ContextExecutor, connection)
        } else {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }.getOrDefault(false)
    if (!bound) {
        runCatching { context.unbindService(connection) }
        return null
    }
    val service = boundBinder.takeIf { latch.await(timeout, unit) }
    if (service != null) return BoundService(service, connection)
    runCatching { context.unbindService(connection) }
    return null
}

@Suppress("DEPRECATION")
private fun packageVersionCode(context: Context, pkg: String): String {
    val pm = context.packageManager
    val info = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(pkg, 0)
        }
    }.getOrNull() ?: return ""
    return if (android.os.Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode.toString()
    } else {
        info.versionCode.toString()
    }
}
