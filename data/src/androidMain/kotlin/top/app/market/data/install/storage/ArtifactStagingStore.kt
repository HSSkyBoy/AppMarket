package top.app.market.data.install.storage

import android.content.Context
import top.app.market.data.install.InstallPipelineException
import top.app.market.domain.model.install.InstallFailureCode
import java.io.File

/** 断点续传暂存区：按任务 id 存放下载中的分片，文件长度即已下载字节数。 */
internal class ArtifactStagingStore(private val context: Context) {
    private val root: File get() = File(context.filesDir, STAGING_DIRECTORY)

    /** 空间不足要在下载前失败；峰值需暂存与安装会话各一份，故按两倍申请再留余量。 */
    fun ensureSpaceFor(taskId: String, requiredBytes: Long) {
        if (requiredBytes <= 0L) return
        val alreadyStaged = File(root, sanitize(taskId)).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val needed = (requiredBytes - alreadyStaged).coerceAtLeast(0L) * 2 + SPACE_HEADROOM_BYTES
        val usable = runCatching { context.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (usable < needed) {
            throw InstallPipelineException(
                InstallFailureCode.STORAGE,
                "存储空间不足：还需 ${needed / 1024 / 1024} MB，可用 ${usable / 1024 / 1024} MB",
            )
        }
    }

    fun artifactFile(taskId: String, index: Int, name: String): File =
        File(directory(taskId), "${index}_${sanitize(name)}")

    fun patchFile(taskId: String, index: Int): File =
        File(directory(taskId), "${index}_patch.bin")

    fun clear(taskId: String) {
        File(root, sanitize(taskId)).deleteRecursively()
    }

    fun retainOnly(taskIds: Collection<String>) {
        val keep = taskIds.mapTo(mutableSetOf(), ::sanitize)
        root.listFiles()?.forEach { entry ->
            if (entry.name !in keep) entry.deleteRecursively()
        }
    }

    private fun directory(taskId: String): File =
        File(root, sanitize(taskId)).apply { mkdirs() }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "task" }

    private companion object {
        const val STAGING_DIRECTORY = "download_staging"
        const val SPACE_HEADROOM_BYTES = 64L * 1024 * 1024
    }
}
