package top.app.market.install

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import top.app.market.data.install.platform.ExternalInstallerLauncher
import java.io.File

internal class ExternalInstallerLauncherImpl(
    private val context: Context,
) : ExternalInstallerLauncher {
    override fun launch(packageName: String, artifactUris: List<String>) {
        require(packageName.isNotBlank()) { "Package installer is not selected" }
        require(artifactUris.isNotEmpty()) { "No APK is available to install" }
        val uris = artifactUris.map(::grantableUri)
        uris.forEach { uri ->
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (uris.size == 1) {
            launchSingle(packageName, uris.single())
        } else {
            launchSplit(packageName, uris)
        }
    }

    private fun launchSingle(packageName: String, uri: Uri) {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME),
            Intent(ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK_MIME),
        )
        var lastError: Throwable? = null
        intents.forEach { intent ->
            intent.prepare(packageName, listOf(uri))
            runCatching {
                context.startActivity(intent)
                return
            }.onFailure { lastError = it }
        }
        throw IllegalStateException(lastError?.message ?: "Selected package installer cannot open this APK")
    }

    private fun launchSplit(packageName: String, uris: List<Uri>) {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = APK_MIME
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(APK_MIME))
            prepare(packageName, uris)
        }
        context.startActivity(intent)
    }

    private fun Intent.prepare(packageName: String, uris: List<Uri>) {
        setPackage(packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, "APK", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
    }

    private fun grantableUri(value: String): Uri {
        val uri = value.toUri()
        if (uri.scheme == "content") return uri
        val file = File(requireNotNull(uri.path) { "Invalid APK URI: $value" })
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val ACTION_INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE"
    }
}
