package top.app.market.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

internal interface PreferencesDataSource {
    fun observe(key: StringPreferenceKey): Flow<String?>
    fun observe(key: BooleanPreferenceKey): Flow<Boolean>

    suspend fun read(key: StringPreferenceKey): String? = observe(key).first()
    suspend fun read(key: BooleanPreferenceKey): Boolean = observe(key).first()
    suspend fun update(namespace: String, changes: PreferenceChanges)

    suspend fun put(key: StringPreferenceKey, value: String) =
        update(key.namespace, PreferenceChanges(strings = mapOf(key to value)))

    suspend fun put(key: BooleanPreferenceKey, value: Boolean) =
        update(key.namespace, PreferenceChanges(booleans = mapOf(key to value)))

    suspend fun remove(key: PreferenceKey<*>) {
        when (key) {
            is StringPreferenceKey -> update(key.namespace, PreferenceChanges(strings = mapOf(key to null)))
            is BooleanPreferenceKey -> update(key.namespace, PreferenceChanges(booleans = mapOf(key to null)))
        }
    }
}

/** One atomic update within a single preference namespace. Null removes the key. */
data class PreferenceChanges(
    val strings: Map<StringPreferenceKey, String?> = emptyMap(),
    val booleans: Map<BooleanPreferenceKey, Boolean?> = emptyMap(),
) {
    val isEmpty: Boolean get() = strings.isEmpty() && booleans.isEmpty()

    fun requireNamespace(namespace: String) {
        val foreignKey = (strings.keys + booleans.keys).firstOrNull { it.namespace != namespace }
        require(foreignKey == null) {
            "Preference key ${foreignKey?.name} belongs to ${foreignKey?.namespace}, not $namespace"
        }
    }
}

sealed class PreferenceKey<T>(val namespace: String, val name: String)
data class StringPreferenceKey(private val keyNamespace: String, private val keyName: String) :
    PreferenceKey<String>(keyNamespace, keyName)

data class BooleanPreferenceKey(
    private val keyNamespace: String,
    private val keyName: String,
    val default: Boolean = false,
) :
    PreferenceKey<Boolean>(keyNamespace, keyName)
