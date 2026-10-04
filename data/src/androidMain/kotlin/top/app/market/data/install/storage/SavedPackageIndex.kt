package top.app.market.data.install.storage

import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.local.StringPreferenceKey
import top.app.market.domain.model.installer.SavedPackage
import top.app.market.domain.model.installer.SavedPackageArtifact
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class SavedPackageIndex(
    private val preferences: PreferencesDataSource,
) {
    private val mutex = Mutex()

    suspend fun all(): List<SavedPackage> = mutex.withLock { readLocked() }

    suspend fun get(id: String): SavedPackage? = mutex.withLock {
        readLocked().firstOrNull { it.id == id }
    }

    suspend fun put(savedPackage: SavedPackage) = mutex.withLock {
        writeLocked(readLocked().filterNot { it.id == savedPackage.id } + savedPackage)
    }

    suspend fun remove(id: String) = mutex.withLock {
        writeLocked(readLocked().filterNot { it.id == id })
    }

    private suspend fun readLocked(): List<SavedPackage> {
        val raw = preferences.read(IndexKey)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toSavedPackage()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun writeLocked(packages: List<SavedPackage>) {
        val array = JSONArray()
        packages.forEach { array.put(it.toJson()) }
        preferences.put(IndexKey, array.toString())
    }

    private fun SavedPackage.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("packageName", packageName)
        put("displayName", displayName)
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("size", size)
        put("modifiedAt", modifiedAt)
        put("icon", icon)
        put("artifacts", JSONArray().also { array ->
            artifacts.forEach { artifact ->
                array.put(JSONObject().apply {
                    put("uri", artifact.uri)
                    put("fileName", artifact.fileName)
                    put("size", artifact.size)
                })
            }
        })
    }

    private fun JSONObject.toSavedPackage(): SavedPackage? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val packageName = optString("packageName").takeIf { it.isNotBlank() } ?: return null
        val array = optJSONArray("artifacts") ?: return null
        val artifacts = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                add(SavedPackageArtifact(uri, item.optString("fileName"), item.optLong("size")))
            }
        }
        if (artifacts.isEmpty()) return null
        return SavedPackage(
            id = id,
            fileName = optString("fileName", artifacts.first().fileName),
            packageName = packageName,
            displayName = optString("displayName", packageName),
            versionName = optString("versionName"),
            versionCode = optLong("versionCode"),
            size = optLong("size", artifacts.sumOf { it.size }),
            modifiedAt = optLong("modifiedAt"),
            artifacts = artifacts,
            icon = optString("icon"),
        )
    }

    private companion object {
        val IndexKey = StringPreferenceKey("saved_packages", "index")
    }
}
