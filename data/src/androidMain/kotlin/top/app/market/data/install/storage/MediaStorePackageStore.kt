package top.app.market.data.install.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import top.app.market.domain.model.install.InstallRequest
import top.app.market.domain.model.installer.SavedPackage
import top.app.market.domain.model.installer.SavedPackageArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal class MediaStorePackageStore(
    private val context: Context,
    private val index: SavedPackageIndex,
) {
    suspend fun createPending(request: InstallRequest): PendingSavedPackage = withContext(Dispatchers.IO) {
        val targets = mutableListOf<PendingArtifactTarget>()
        try {
            request.artifacts.forEachIndexed { artifactIndex, artifact ->
                targets += createTarget(request, artifactIndex, artifact.name)
            }
            PendingSavedPackage(request, targets, context, index)
        } catch (error: Throwable) {
            targets.forEach { runCatching { deleteUri(it.uri) } }
            throw error
        }
    }

    suspend fun list(): List<SavedPackage> = withContext(Dispatchers.IO) {
        val valid = mutableListOf<SavedPackage>()
        index.all().forEach { saved ->
            if (saved.artifacts.all { canRead(Uri.parse(it.uri)) }) {
                valid += saved
            } else {
                index.remove(saved.id)
            }
        }
        valid.sortedByDescending { it.modifiedAt }
    }

    suspend fun get(id: String): SavedPackage? = withContext(Dispatchers.IO) {
        index.get(id)?.takeIf { saved -> saved.artifacts.all { canRead(Uri.parse(it.uri)) } }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val saved = index.get(id) ?: return@withContext
        saved.artifacts.forEach { artifact -> deleteUri(Uri.parse(artifact.uri)) }
        index.remove(id)
    }

    /**
     * 清理下载中途被杀留下的 pending 条目。只以 pending 为依据——索引读失败会静默返回空，
     * 若按"不在索引里"删，一次索引丢失就会清空用户下载目录里的全部安装包。
     */
    suspend fun sweepOrphans() = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { sweepPendingEntries() }
        } else {
            runCatching { sweepLegacyOrphans() }
        }
        Unit
    }

    private fun sweepPendingEntries() {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 1",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/AppMarket%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { deleteUri(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
            }
        }
    }

    /** Q 以下没有 pending 概念，只能靠索引比对，因此索引为空时一律不动。 */
    private suspend fun sweepLegacyOrphans() {
        val known = index.all().flatMapTo(mutableSetOf()) { saved -> saved.artifacts.map { it.uri } }
        if (known.isEmpty()) return
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        File(base, "AppMarket").walkBottomUp().forEach { entry ->
            when {
                entry.isFile && Uri.fromFile(entry).toString() !in known -> entry.delete()
                entry.isDirectory && entry.listFiles()?.isEmpty() == true -> entry.delete()
            }
        }
    }

    fun openInput(uri: String): InputStream {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            "content" -> context.contentResolver.openInputStream(parsed)
                ?: error("Unable to open saved package: $uri")

            "file", null -> File(requireNotNull(parsed.path)).inputStream()
            else -> error("Unsupported saved package URI: $uri")
        }
    }

    private fun createTarget(request: InstallRequest, index: Int, sessionName: String): PendingArtifactTarget {
        val fileName = displayFileName(request, index, sessionName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = buildRelativePath(request)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create download entry")
            PendingArtifactTarget(uri, fileName)
        } else {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("External downloads directory is unavailable")
            val directory = File(base, buildLegacySubdirectory(request)).apply { mkdirs() }
            val file = uniqueFile(directory, fileName)
            PendingArtifactTarget(Uri.fromFile(file), file.name)
        }
    }

    private fun displayFileName(request: InstallRequest, index: Int, sessionName: String): String {
        if (request.artifacts.size > 1) return sanitizeFileName(sessionName).ensureApkSuffix()
        val version = request.versionName.ifBlank { request.versionCode.toString() }
        return sanitizeFileName("${request.displayName}-$version").ensureApkSuffix()
    }

    private fun buildRelativePath(request: InstallRequest): String {
        val root = "${Environment.DIRECTORY_DOWNLOADS}/AppMarket"
        if (request.artifacts.size == 1) return root
        return "$root/${sanitizeFileName("${request.packageName}-${request.versionCode}")}"
    }

    private fun buildLegacySubdirectory(request: InstallRequest): String =
        if (request.artifacts.size == 1) "AppMarket"
        else "AppMarket/${sanitizeFileName("${request.packageName}-${request.versionCode}")}"

    private fun uniqueFile(directory: File, requestedName: String): File {
        val base = requestedName.removeSuffix(".apk")
        var candidate = File(directory, requestedName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base-$suffix.apk")
            suffix += 1
        }
        return candidate
    }

    private fun canRead(uri: Uri): Boolean = runCatching {
        when (uri.scheme) {
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            "file", null -> File(requireNotNull(uri.path)).isFile
            else -> false
        }
    }.getOrDefault(false)

    private fun deleteUri(uri: Uri) {
        when (uri.scheme) {
            "content" -> context.contentResolver.delete(uri, null, null)
            "file", null -> File(requireNotNull(uri.path)).delete()
        }
    }

    internal class PendingSavedPackage(
        private val request: InstallRequest,
        private val targets: List<PendingArtifactTarget>,
        private val context: Context,
        private val index: SavedPackageIndex,
    ) {
        fun openOutput(artifactIndex: Int): OutputStream {
            val uri = targets[artifactIndex].uri
            val raw = when (uri.scheme) {
                "content" -> context.contentResolver.openOutputStream(uri, "w")
                    ?: error("Unable to write download entry")

                "file", null -> File(requireNotNull(uri.path)).outputStream()
                else -> error("Unsupported download URI: $uri")
            }
            return raw.buffered(OUTPUT_BUFFER_SIZE)
        }

        suspend fun publish(): SavedPackage = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                targets.forEach { context.contentResolver.update(it.uri, values, null, null) }
            }
            val artifacts = targets.map { target ->
                val (name, size) = resolveMetadata(target)
                SavedPackageArtifact(target.uri.toString(), name, size)
            }
            val saved = SavedPackage(
                id = request.id,
                fileName = artifacts.first().fileName,
                packageName = request.packageName,
                displayName = request.displayName,
                versionName = request.versionName,
                versionCode = request.versionCode,
                size = artifacts.sumOf { it.size },
                modifiedAt = System.currentTimeMillis(),
                artifacts = artifacts,
                icon = request.icon,
            )
            index.put(saved)
            saved
        }

        suspend fun rollback() = withContext(Dispatchers.IO) {
            targets.forEach { target ->
                when (target.uri.scheme) {
                    "content" -> context.contentResolver.delete(target.uri, null, null)
                    "file", null -> File(requireNotNull(target.uri.path)).delete()
                }
            }
        }

        private fun resolveMetadata(target: PendingArtifactTarget): Pair<String, Long> {
            if (target.uri.scheme != "content") {
                val file = File(requireNotNull(target.uri.path))
                return file.name to file.length()
            }
            context.contentResolver.query(
                target.uri,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0) to cursor.getLong(1)
                }
            }
            return target.fileName to 0L
        }

        private companion object {
            const val OUTPUT_BUFFER_SIZE = 128 * 1024
        }
    }

    internal data class PendingArtifactTarget(val uri: Uri, val fileName: String)

    private fun sanitizeFileName(value: String): String =
        value.ifBlank { "package" }
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
            .ifBlank { "package" }

    private fun String.ensureApkSuffix(): String =
        if (endsWith(".apk", ignoreCase = true)) this else "$this.apk"

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
