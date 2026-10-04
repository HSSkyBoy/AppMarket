package top.app.market.data.store

import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.local.preferences.HistoryPreferenceKeys
import top.app.market.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull


internal class SearchHistoryRepositoryImpl(private val preferences: PreferencesDataSource, private val json: Json) :
    SearchHistoryRepository {
    private val MAX_SIZE = 20
    private val mutex = Mutex()

    override suspend fun load(): List<String> = withContext(Dispatchers.Default) { readHistory() }

    override suspend fun add(keyword: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            val trimmed = keyword.trim()
            if (trimmed.isNotEmpty()) {
                val current = readHistory().filterNot { it.equals(trimmed, ignoreCase = true) }
                writeHistory((listOf(trimmed) + current).take(MAX_SIZE))
            }
        }
    }

    override suspend fun remove(keyword: String) = withContext(Dispatchers.Default) {
        mutex.withLock { writeHistory(readHistory().filterNot { it == keyword }) }
    }

    override suspend fun clear() = withContext(Dispatchers.Default) {
        mutex.withLock { preferences.remove(HistoryPreferenceKeys.Search) }
    }

    private suspend fun readHistory(): List<String> {
        val raw = preferences.read(HistoryPreferenceKeys.Search)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }

    private suspend fun writeHistory(items: List<String>) {
        val arr = buildJsonArray { items.forEach { add(it) } }
        preferences.put(HistoryPreferenceKeys.Search, arr.toString())
    }
}
