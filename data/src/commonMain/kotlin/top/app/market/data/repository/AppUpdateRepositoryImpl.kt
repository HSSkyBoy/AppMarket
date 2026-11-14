package top.app.market.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.app.market.domain.model.update.AppReleaseInfo
import top.app.market.domain.repository.AppUpdateRepository

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAssetDto>? = null,
)

@Serializable
private data class GitHubAssetDto(
    val name: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
)

class AppUpdateRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
) : AppUpdateRepository {

    override suspend fun checkForUpdate(currentVersionName: String): AppReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val response = client.get(GITHUB_API_URL) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "AppMarket-App")
            }
            if (!response.status.isSuccess()) {
                return@withContext null
            }
            val bodyText = response.bodyAsText()
            val releaseDto = json.decodeFromString<GitHubReleaseDto>(bodyText)

            val tagName = releaseDto.tagName.orEmpty()
            val cleanRemoteVersion = tagName.trimStart('v', 'V')
            val cleanLocalVersion = currentVersionName.trimStart('v', 'V')

            if (cleanRemoteVersion.isBlank() || !isNewerVersion(cleanRemoteVersion, cleanLocalVersion)) {
                return@withContext null
            }

            val htmlUrl = releaseDto.htmlUrl.orEmpty()
            var apkUrl = htmlUrl
            val assets = releaseDto.assets
            if (assets != null) {
                for (asset in assets) {
                    val name = asset.name.orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        val downloadUrl = asset.browserDownloadUrl
                        if (!downloadUrl.isNullOrBlank()) {
                            apkUrl = downloadUrl
                            break
                        }
                    }
                }
            }

            val mirrorDownloadUrl = if (apkUrl.startsWith("http://") || apkUrl.startsWith("https://")) {
                "$MIRROR_PREFIX$apkUrl"
            } else {
                apkUrl
            }

            AppReleaseInfo(
                versionName = cleanRemoteVersion,
                releaseNotes = releaseDto.body.orEmpty(),
                downloadUrl = apkUrl,
                mirrorDownloadUrl = mirrorDownloadUrl,
                htmlUrl = htmlUrl,
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/HSSkyBoy/AppMarket/releases/latest"
        private const val MIRROR_PREFIX = "https://gh-proxy.com/"

        internal fun isNewerVersion(remote: String, local: String): Boolean {
            val remoteParts = remote.trimStart('v', 'V').split(".").mapNotNull { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull()
            }
            val localParts = local.trimStart('v', 'V').split(".").mapNotNull { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull()
            }
            val maxLength = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLength) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            return false
        }
    }
}
