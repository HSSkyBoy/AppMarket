package top.app.market.data.store

import top.app.market.data.local.PreferenceChanges
import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.local.StringPreferenceKey
import top.app.market.data.local.preferences.HistoryPreferenceKeys
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.update.UpdateHistoryEntry
import top.app.market.domain.repository.UpdateHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


internal class UpdateHistoryRepositoryImpl(
    private val preferences: PreferencesDataSource,
    private val scope: CoroutineScope,
    private val json: Json
) : UpdateHistoryRepository {
    private val MAX_SIZE = 500

    private val serialDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val mutex = Mutex()

    private var loaded = false

    private val _entries = MutableStateFlow<List<UpdateHistoryEntry>>(emptyList())
    override val entries: StateFlow<List<UpdateHistoryEntry>> = _entries.asStateFlow()

    init {
        scope.launch { serialized { ensureLoaded() } }
    }

    override suspend fun markPending(entry: UpdateHistoryEntry) = serialized {
        val pending = read(HistoryPreferenceKeys.PendingUpdates).filterNot { it.packageName == entry.packageName }
        write(HistoryPreferenceKeys.PendingUpdates, listOf(entry) + pending)
    }

    override suspend fun commitPending(packageName: String) = serialized {
        ensureLoaded()
        val pending = read(HistoryPreferenceKeys.PendingUpdates)
        val entry = pending.firstOrNull { it.packageName == packageName } ?: return@serialized
        val updated = (listOf(entry.copy(installedAt = epochMillis())) + _entries.value).take(MAX_SIZE)
        preferences.update(
            HistoryPreferenceKeys.Updates.namespace,
            PreferenceChanges(
                strings = mapOf(
                    HistoryPreferenceKeys.PendingUpdates to encode(pending.filterNot { it.packageName == packageName }),
                    HistoryPreferenceKeys.Updates to encode(updated),
                )
            ),
        )
        _entries.value = updated
    }

    override suspend fun removePending(packageName: String) = serialized {
        val pending = read(HistoryPreferenceKeys.PendingUpdates)
        val remaining = pending.filterNot { it.packageName == packageName }
        if (remaining.size != pending.size) write(HistoryPreferenceKeys.PendingUpdates, remaining)
    }

    override suspend fun pendingEntries(): List<UpdateHistoryEntry> =
        serialized { read(HistoryPreferenceKeys.PendingUpdates) }

    override suspend fun clear() = serialized {
        preferences.remove(HistoryPreferenceKeys.Updates)
        loaded = true
        _entries.value = emptyList()
    }

    private suspend inline fun <T> serialized(crossinline block: suspend () -> T): T =
        withContext(serialDispatcher) { mutex.withLock { block() } }

    private suspend fun ensureLoaded() {
        if (loaded) return
        _entries.value = read(HistoryPreferenceKeys.Updates)
        loaded = true
    }

    private suspend fun read(key: StringPreferenceKey): List<UpdateHistoryEntry> {
        val raw = preferences.read(key)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val pkg = o.str("packageName")
            if (pkg.isBlank()) return@mapNotNull null
            UpdateHistoryEntry(
                appId = o.long("appId"),
                packageName = pkg,
                displayName = o.str("displayName", pkg),
                versionName = o.str("versionName"),
                versionCode = o.long("versionCode"),
                previousVersionName = o.str("previousVersionName"),
                icon = o.str("icon"),
                changeLog = o.str("changeLog"),
                installedAt = o.long("installedAt"),
                source = AppSource.fromToken(o.str("source")),
            )
        }
    }

    private suspend fun write(key: StringPreferenceKey, items: List<UpdateHistoryEntry>) {
        preferences.put(key, encode(items))
    }

    private fun encode(items: List<UpdateHistoryEntry>): String {
        val arr = buildJsonArray {
            items.forEach { e ->
                add(
                    buildJsonObject {
                        put("appId", e.appId)
                        put("packageName", e.packageName)
                        put("displayName", e.displayName)
                        put("versionName", e.versionName)
                        put("versionCode", e.versionCode)
                        put("previousVersionName", e.previousVersionName)
                        put("icon", e.icon)
                        put("changeLog", e.changeLog)
                        put("installedAt", e.installedAt)
                        e.source?.let { put("source", it.token) }
                    }
                )
            }
        }
        return arr.toString()
    }
}
