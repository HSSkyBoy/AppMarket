package top.app.market.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import top.app.market.domain.model.installer.InstallerCandidate
import top.app.market.domain.repository.InstallerDiscoveryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidInstallerDiscoveryRepositoryImpl(
    private val context: Context,
) : InstallerDiscoveryRepository {
    override suspend fun listCandidates(): List<InstallerCandidate> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val probeUris = listOf(
            Uri.parse("content://${context.packageName}.fileprovider/installer_probe.apk"),
            Uri.parse("file:///sdcard/Download/installer_probe.apk"),
        )
        val intents = buildList {
            add(Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                type = APK_MIME
            })
            probeUris.forEach { uri ->
                add(Intent(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    setDataAndType(uri, APK_MIME)
                })
                add(Intent(ACTION_INSTALL_PACKAGE).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    setDataAndType(uri, APK_MIME)
                })
            }
        }

        intents.flatMap { intent -> packageManager.queryAll(intent) }
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                InstallerCandidate(
                    packageName = packageName,
                    label = resolveInfo.loadLabel(packageManager).toString().ifBlank { packageName },
                )
            }
            .distinctBy(InstallerCandidate::packageName)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun PackageManager.queryAll(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val ACTION_INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE"
    }
}
