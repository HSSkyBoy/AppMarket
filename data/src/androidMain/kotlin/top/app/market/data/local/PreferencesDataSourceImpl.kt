package top.app.market.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import top.app.market.data.platform.debugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class PreferencesDataSourceImpl(private val context: android.content.Context) : PreferencesDataSource {
    private companion object {
        val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
    }

    private fun store(namespace: String): DataStore<Preferences> =
        stores.computeIfAbsent(namespace) {
            val appContext = context.applicationContext
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { error ->
                    debugLog("Preferences") { "Replacing corrupted store $namespace: $error" }
                    emptyPreferences()
                },
                migrations = listOf(SharedPreferencesMigration(appContext, namespace)),
                produceFile = { appContext.preferencesDataStoreFile(namespace) },
            )
        }

    private fun data(namespace: String): Flow<Preferences> =
        store(namespace).data.catch { error ->
            if (error !is IOException) throw error
            debugLog("Preferences") { "Read failure in $namespace: $error" }
            emit(emptyPreferences())
        }

    override fun observe(key: StringPreferenceKey): Flow<String?> =
        data(key.namespace).map { it[stringPreferencesKey(key.name)] }

    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> =
        data(key.namespace).map { it[booleanPreferencesKey(key.name)] ?: key.default }

    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.requireNamespace(namespace)
        if (changes.isEmpty) return
        store(namespace).edit { preferences ->
            changes.strings.forEach { (key, value) ->
                val dataStoreKey = stringPreferencesKey(key.name)
                if (value == null) preferences.remove(dataStoreKey) else preferences[dataStoreKey] = value
            }
            changes.booleans.forEach { (key, value) ->
                val dataStoreKey = booleanPreferencesKey(key.name)
                if (value == null) preferences.remove(dataStoreKey) else preferences[dataStoreKey] = value
            }
        }
    }
}
