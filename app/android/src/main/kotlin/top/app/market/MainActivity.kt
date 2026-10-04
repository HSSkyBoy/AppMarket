package top.app.market

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import top.app.market.domain.repository.ThemePreferencesRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import top.app.market.platform.AndroidPermissionCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MainActivity : ComponentActivity(), KoinComponent {

    private val externalDetailRequest = MutableStateFlow<ExternalDetailRequest?>(null)
    private val externalSearchKeyword = MutableStateFlow<String?>(null)
    private val externalDownloadsRequest = MutableStateFlow(false)
    private val permissions: AndroidPermissionCoordinator by inject()
    private val updatePreferences: UpdatePreferencesRepository by inject()
    private val themePreferences: ThemePreferencesRepository by inject()
    private var contentReady = false

    private val installedAppsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissions.dispatchInstalledAppsResult(granted)
        }
    private val postNotificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissions.dispatchNotificationsResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !contentReady }
        consumeExternalIntent(intent)
        permissions.registerInstalledApps(installedAppsPermissionLauncher)
        permissions.registerNotifications(postNotificationsPermissionLauncher)
        setContent {
            val darkMode = isSystemInDarkTheme()
            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }

            val detailRequest by externalDetailRequest.collectAsState()
            val searchKeyword by externalSearchKeyword.collectAsState()
            val downloadsRequest by externalDownloadsRequest.collectAsState()
            val preferencesReady by updatePreferences.initialized.collectAsState()
            val themePreferencesReady by themePreferences.initialized.collectAsState()
            if (preferencesReady && themePreferencesReady) {
                App(
                    externalDetailPackageName = detailRequest?.packageName,
                    externalDetailQuery = detailRequest?.encodedQuery,
                    onExternalDetailConsumed = { consumed ->
                        if (externalDetailRequest.value?.packageName == consumed) {
                            externalDetailRequest.value = null
                        }
                    },
                    externalSearchKeyword = searchKeyword,
                    onExternalSearchConsumed = { consumed ->
                        if (externalSearchKeyword.value == consumed) {
                            externalSearchKeyword.value = null
                        }
                    },
                    externalOpenDownloads = downloadsRequest,
                    onExternalDownloadsConsumed = { externalDownloadsRequest.value = false },
                )
                SideEffect { contentReady = true }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeExternalIntent(intent)
    }

    private fun consumeExternalIntent(intent: Intent?) {
        if (intent.isDownloadsRequest()) {
            externalDownloadsRequest.value = true
            return
        }
        val keyword = intent.searchKeyword()
        if (keyword != null) {
            externalSearchKeyword.value = keyword
            return
        }
        externalDetailRequest.value = intent.detailRequest()
    }

    // 全局搜索的「去应用商店搜索」：market://search?q=xxx；命中但无关键词时返回空串，仅切到搜索页
    private fun Intent?.searchKeyword(): String? {
        if (this?.action != Intent.ACTION_VIEW) return null
        val uri = data ?: return null
        if (uri.scheme.orEmpty().lowercase() !in setOf("market", "mimarket")) return null
        if (uri.host.orEmpty().lowercase() !in setOf("search", "searchresult")) return null
        val keys = listOf("q", "keyword", "query", "key", "kw")
        return keys.firstNotNullOfOrNull { uri.getQueryParameter(it)?.asKeyword() }
            ?: keys.firstNotNullOfOrNull { getStringExtra(it)?.asKeyword() }
            ?: getStringExtra(Intent.EXTRA_TEXT)?.asKeyword()
            ?: ""
    }

    private fun String.asKeyword(): String? = trim().take(100).takeIf(String::isNotEmpty)

    // 多任务下载通知的落点：market://downloads 打开「下载中的应用」页
    private fun Intent?.isDownloadsRequest(): Boolean {
        if (this?.action != Intent.ACTION_VIEW) return false
        val uri = data ?: return false
        return uri.scheme.orEmpty().lowercase() in setOf("market", "mimarket") &&
                uri.host.orEmpty().lowercase() == "downloads"
    }

    override fun onResume() {
        super.onResume()
        permissions.checkInstalledAppsOnResume()
    }

    override fun onDestroy() {
        permissions.unregisterInstalledApps(installedAppsPermissionLauncher)
        permissions.unregisterNotifications(postNotificationsPermissionLauncher)
        super.onDestroy()
    }

    private fun Intent?.detailRequest(): ExternalDetailRequest? {
        if (this?.action != Intent.ACTION_VIEW) return null
        val uri = data
        val uriPackageName = uri?.detailPackageName()
        val packageName = uriPackageName ?: stringExtraPackageName() ?: return null
        val encodedQuery = uriPackageName
            ?.let { uri.encodedQuery }
            ?.takeIf(String::isNotBlank)
            ?: "id=${Uri.encode(packageName)}"
        return ExternalDetailRequest(packageName, encodedQuery)
    }

    private fun Intent.stringExtraPackageName(): String? =
        listOf("id", "packageName", "pName", "pkg", "package", "android.intent.extra.PACKAGE_NAME")
            .firstNotNullOfOrNull { key -> getStringExtra(key)?.asPackageName() }

    private fun Uri.detailPackageName(): String? {
        val scheme = scheme.orEmpty().lowercase()
        val host = host.orEmpty().lowercase()
        val path = path.orEmpty()
        val supportsMarketLink = scheme in setOf("market", "mimarket") &&
                host in setOf("details", "detail", "launchordetail", "app.xiaomi.com")
        val supportsWebLink = scheme in setOf("http", "https") &&
                host in setOf("m.app.mi.com", "app.mi.com", "app.xiaomi.com", "market.android.com", "play.google.com")
        if (!supportsMarketLink && !supportsWebLink) return null

        listOf("id", "packageName", "pName", "pkg", "package").forEach { key ->
            getQueryParameter(key)?.asPackageName()?.let { return it }
        }
        if (host == "app.xiaomi.com" && path.isNotBlank()) {
            path.trim('/').split('/').firstOrNull()?.asPackageName()?.let { return it }
        }
        return encodedSchemeSpecificPart
            ?.substringAfter("?", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.let { "market://details?$it".toUri() }
            ?.let { parsed ->
                listOf("id", "packageName", "pName", "pkg", "package").firstNotNullOfOrNull {
                    parsed.getQueryParameter(it)?.asPackageName()
                }
            }
    }

    private fun String.asPackageName(): String? =
        trim().takeIf { value ->
            value.length in 3..255 &&
                    value.any { it == '.' } &&
                    value.all { it.isLetterOrDigit() || it == '_' || it == '.' }
        }
}

private data class ExternalDetailRequest(
    val packageName: String,
    val encodedQuery: String,
)
