package top.app.market.data.remote.xiaomi.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import top.app.market.data.platform.debugLog
import top.app.market.domain.exception.InstalledPackagesUnavailableException
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.repository.InstalledApkHashRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Enumerates installed packages via [PackageManager] for update checking. The scan is expensive
 * (hundreds of packages with heavy match flags), so its result is cached in memory and invalidated
 * by package add/remove/replace broadcasts — mirroring the official Market's `LocalAppManager`.
 */
class AndroidInstalledPackagesRepositoryImpl(
    private val context: Context,
) : InstalledPackagesRepository {
    @Volatile
    private var cache: List<InstalledPackage>? = null
    private val generation = AtomicLong(0L)
    private val receiverRegistered = AtomicBoolean(false)
    private val scanMutex = Mutex()

    override suspend fun installed(): List<InstalledPackage> = withContext(Dispatchers.IO) {
        ensureReceiver()
        cache?.let { return@withContext it }
        // Serialize the cold scan so concurrent first callers (seed reconciliation + live check) share
        // one enumeration instead of each running confirmedScan().
        scanMutex.withLock {
            cache?.let { return@withContext it }
            val genBefore = generation.get()
            val scanned = confirmedScan()
            // Publish then re-check the generation: drop the cache if a package change raced the scan,
            // so the broadcast's invalidation wins over this now-stale scan.
            cache = scanned
            if (generation.get() != genBefore) cache = null
            scanned
        }
    }

    override suspend fun installedPackage(packageName: String): InstalledPackage? = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext null
        ensureReceiver()
        // Targeted lookups feed the download request and must reflect the package currently on
        // disk. System-app updates do not reliably deliver package broadcasts on every MIUI build,
        // so the full-scan cache can be stale here even though it remains useful for bulk checks.
        val pm = context.packageManager
        val flags = updatePackageQueryFlags()
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags)
            }
        }.getOrNull() ?: return@withContext null

        info.toInstalledPackage(pm)
    }

    /** Registers a one-shot receiver that drops the cache whenever the installed set changes. */
    private fun ensureReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                generation.incrementAndGet()
                cache = null
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private suspend fun confirmedScan(): List<InstalledPackage> {
        val first = scan()
        if (!isReliableScan(first)) {
            debugLog("InstalledPackages") { "first scan unreliable: ${scanSummary(first)}" }
            throw InstalledPackagesUnavailableException()
        }

        // MIUI can return a full list before the app-list denial takes effect. Confirm the cold
        // scan once before publishing it into the process cache, otherwise that first race wins
        // until the app is killed.
        delay(SCAN_CONFIRMATION_DELAY_MS.milliseconds)
        val second = scan()
        if (!isReliableScan(second)) {
            debugLog("InstalledPackages") {
                "confirmed scan became unreliable first=${scanSummary(first)} second=${scanSummary(second)}"
            }
            throw InstalledPackagesUnavailableException()
        }
        return second
    }

    private fun isReliableScan(packages: List<InstalledPackage>): Boolean {
        if (packages.size < MIN_RELIABLE_PACKAGE_COUNT) return false
        val selfPackageName = context.packageName
        val thirdPartyCount = packages.count { !it.isSystemApp && it.packageName != selfPackageName }
        return thirdPartyCount >= MIN_RELIABLE_THIRD_PARTY_COUNT
    }

    private fun scanSummary(packages: List<InstalledPackage>): String {
        val selfPackageName = context.packageName
        val thirdPartyCount = packages.count { !it.isSystemApp && it.packageName != selfPackageName }
        return "total=${packages.size} thirdParty=$thirdPartyCount"
    }

    private fun scan(): List<InstalledPackage> {
        val pm = context.packageManager
        val flags = updatePackageQueryFlags()
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
        return packages.asSequence().filter { info ->
            val privateFlags = info.applicationInfo?.flags ?: 0
            privateFlags and FLAG_INSTALLED_FOR_USER != 0 && isUpdateInfoEligible(info)
        }.map { info -> info.toInstalledPackage(pm) }.toList()
    }

    private fun PackageInfo.toInstalledPackage(pm: PackageManager): InstalledPackage {
        val flags = applicationInfo?.flags ?: 0
        val systemUpdateCandidate = isSystemUpdateCandidate(this, flags)
        val certificateBytes = signingCertificateBytes()
        return InstalledPackage(
            packageName = packageName,
            versionCode = versionCode(this),
            versionName = versionName.orEmpty(),
            isSystemApp = systemUpdateCandidate,
            installedBy = installedBy(pm, packageName),
            splits = installedModules(this),
            baseApkPath = applicationInfo?.sourceDir.orEmpty(),
            targetSdkVersion = applicationInfo?.targetSdkVersion ?: 0,
            signature = legacySignatureBytes()?.let(::legacySignatureDigest).orEmpty(),
            signatureList = certificateBytes.map(::certificateDigest).distinct(),
            installOrigin = installOrigin(pm, packageName, systemUpdateCandidate),
            displayName = runCatching { applicationInfo?.loadLabel(pm)?.toString().orEmpty() }.getOrDefault(""),
            signerSha256 = certificateBytes.firstOrNull()?.let(::certificateSha256).orEmpty(),
            signerSha256List = certificateBytes.map(::certificateSha256).distinct(),
        )
    }

    private fun updatePackageQueryFlags(): Int =
        PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
                PackageManager.GET_PERMISSIONS or
                signingCertificateQueryFlag() or
                matchApexFlag()

    @Suppress("DEPRECATION")
    private fun signingCertificateQueryFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    private fun matchApexFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PackageManager.MATCH_APEX else 0

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun isSystemUpdateCandidate(info: PackageInfo, flags: Int): Boolean {
        if (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true
        val sourceDir = info.applicationInfo?.sourceDir.orEmpty()
        return SYSTEM_APP_PATH_PREFIXES.any { sourceDir.startsWith(it) }
    }

    private fun isUpdateInfoEligible(info: PackageInfo): Boolean {
        val packageName = info.packageName
        if (packageName in APEX_PACKAGES) return false
        if (packageName.startsWith("com.android.theme.font.")) return false
        if (packageName.startsWith("com.android.theme.lockscreen_clock_font.")) return false

        val sourceDir = info.applicationInfo?.sourceDir.orEmpty()
        if (sourceDir.startsWith("/apex/")) return false
        if (sourceDir.contains("/overlay/")) return false
        return true
    }

    @Suppress("DEPRECATION")
    private fun installedBy(pm: PackageManager, packageName: String): String =
        if (runCatching { pm.getInstallerPackageName(packageName) }.getOrNull() == "com.xiaomi.market") {
            "1"
        } else {
            "0"
        }

    private fun installedModules(info: PackageInfo): String {
        val modules = info.splitNames.orEmpty()
            .filter { it.isNotBlank() }
            .sorted()
        if (modules.isEmpty()) return "0"
        return modules.joinToString(separator = "#", prefix = "1:[", postfix = "]")
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.legacySignatureBytes(): ByteArray? =
        signatures?.firstOrNull()?.toByteArray()
            ?: signingCertificateBytes().firstOrNull()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signingCertificateBytes(): List<ByteArray> = runCatching {
        val selected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.let { info ->
                if (info.hasPastSigningCertificates()) {
                    info.signingCertificateHistory
                } else {
                    info.apkContentsSigners
                }
            }
        } else {
            signatures
        }
        (selected ?: signatures).orEmpty().map { it.toByteArray() }
    }.getOrDefault(emptyList())

    private fun legacySignatureDigest(certificate: ByteArray): String {
        // Official OPPO clients hash the uppercase hex representation of the X.509 DER bytes,
        // then send the result as uppercase hex (UpgradeReqV2.sign).
        val certificateHex = certificate.toHex(UPPER_HEX)
        return MessageDigest.getInstance("MD5")
            .digest(certificateHex.encodeToByteArray())
            .toHex(UPPER_HEX)
    }

    private fun certificateDigest(certificate: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(certificate).toHex(LOWER_HEX)

    private fun certificateSha256(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).toHex(LOWER_HEX)

    private fun ByteArray.toHex(alphabet: CharArray): String = CharArray(size * 2).also { output ->
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = alphabet[value ushr 4]
            output[index * 2 + 1] = alphabet[value and 0x0f]
        }
    }.concatToString()

    @Suppress("DEPRECATION")
    private fun installOrigin(pm: PackageManager, packageName: String, isSystemApp: Boolean): String {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                pm.getInstallerPackageName(packageName)
            }
        }.getOrNull()
        return installer ?: if (isSystemApp) OPPO_PRELOAD_ORIGIN else ""
    }

    private companion object {
        // ApplicationInfo.FLAG_INSTALLED is @hide on some SDK stubs. It marks packages installed
        // for the current user; MATCH_UNINSTALLED_PACKAGES also returns uninstall remnants/other
        // user packages, which the official Market does not include in updateinfo/v2.
        private const val FLAG_INSTALLED_FOR_USER = 1 shl 23
        private const val OPPO_PRELOAD_ORIGIN = "PRELOAD"
        private val UPPER_HEX = "0123456789ABCDEF".toCharArray()
        private val LOWER_HEX = "0123456789abcdef".toCharArray()

        private val APEX_PACKAGES = setOf(
            "com.android.adbd",
            "com.android.adservices",
            "com.android.apex.cts.shim",
            "com.android.appsearch",
            "com.android.art",
            "com.android.bt",
            "com.android.configinfrastructure",
            "com.android.conscrypt",
            "com.android.crashrecovery",
            "com.android.devicelock",
            "com.android.extservices",
            "com.android.hardware.drm.clearkey",
            "com.android.healthfitness",
            "com.android.i18n",
            "com.android.ipsec",
            "com.android.media",
            "com.android.media.swcodec",
            "com.android.mediaprovider",
            "com.android.neuralnetworks",
            "com.android.nfcservices",
            "com.android.ondevicepersonalization",
            "com.android.os.statsd",
            "com.android.permission",
            "com.android.profiling",
            "com.android.resolv",
            "com.android.rkpd",
            "com.android.runtime",
            "com.android.scheduling",
            "com.android.sdkext",
            "com.android.telephonycore",
            "com.android.tethering",
            "com.android.tzdata",
            "com.android.uprobestats",
            "com.android.uwb",
            "com.android.virt",
            "com.android.wifi",
        )

        private val SYSTEM_APP_PATH_PREFIXES = listOf(
            "/system/",
            "/system_ext/",
            "/product/",
            "/vendor/",
            "/odm/",
            "/oem/",
        )

        private const val MIN_RELIABLE_PACKAGE_COUNT = 30
        private const val MIN_RELIABLE_THIRD_PARTY_COUNT = 1
        private const val SCAN_CONFIRMATION_DELAY_MS = 900L
    }
}

class AndroidInstalledApkHashRepositoryImpl : InstalledApkHashRepository {
    override suspend fun tapTap(path: String): String = top.app.market.data.platform.tapTapApkHash(path)

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<CacheKey, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    override suspend fun md5(path: String): String = digestFile(path, "MD5", "0")

    override suspend fun sha256(path: String): String = digestFile(path, "SHA-256", "")

    private suspend fun digestFile(path: String, algorithm: String, fallback: String): String =
        withContext(Dispatchers.IO) {
            if (path.isBlank()) return@withContext fallback
            currentCoroutineContext().ensureActive()
            try {
                val file = File(path)
                if (!file.isFile || !file.canRead()) return@withContext fallback
                val key = CacheKey(file.absolutePath, file.length(), file.lastModified(), algorithm)
                synchronized(cacheLock) { cache[key] }?.let { return@withContext it }
                val digest = MessageDigest.getInstance(algorithm)
                val buffer = ByteArray(HASH_BUFFER_SIZE)
                FileInputStream(file).use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                currentCoroutineContext().ensureActive()
                val value = digest.digest().toLowerHex()
                synchronized(cacheLock) {
                    // Drop an obsolete entry for a replaced APK at the same path and algorithm.
                    cache.keys.removeAll { it.path == key.path && it.algorithm == key.algorithm && it != key }
                    cache[key] = value
                }
                value
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                fallback
            }
        }

    private fun ByteArray.toLowerHex(): String = CharArray(size * 2).also { output ->
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = LOWER_HEX[value ushr 4]
            output[index * 2 + 1] = LOWER_HEX[value and 0x0f]
        }
    }.concatToString()

    private data class CacheKey(
        val path: String,
        val length: Long,
        val lastModified: Long,
        val algorithm: String,
    )

    private companion object {
        const val MAX_CACHE_ENTRIES = 128
        const val HASH_BUFFER_SIZE = 64 * 1024
        val LOWER_HEX = "0123456789abcdef".toCharArray()
    }
}
