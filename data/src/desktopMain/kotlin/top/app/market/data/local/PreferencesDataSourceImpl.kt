package top.app.market.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

internal class PreferencesDataSourceImpl : PreferencesDataSource {
    private val file = File(System.getProperty("user.home"), ".app-market/config.properties")

    @Volatile
    private var values = Properties().also { properties ->
        // 配置文件损坏时按空配置启动，避免 DI 图构建直接失败
        if (file.exists()) runCatching { file.inputStream().use(properties::load) }
    }
    private val mutex = Mutex()
    private val stateLock = Any()
    private fun key(namespace: String, key: String) = "$namespace.$key"

    private val stringStates = ConcurrentHashMap<StringPreferenceKey, MutableStateFlow<String?>>()
    private val booleanStates = ConcurrentHashMap<BooleanPreferenceKey, MutableStateFlow<Boolean>>()
    override fun observe(key: StringPreferenceKey): Flow<String?> =
        synchronized(stateLock) {
            stringStates.getOrPut(key) { MutableStateFlow(values.getProperty(key(key.namespace, key.name))) }
        }

    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> = synchronized(stateLock) {
        booleanStates.getOrPut(key) {
            MutableStateFlow(values.getProperty(key(key.namespace, key.name))?.toBooleanStrictOrNull() ?: key.default)
        }
    }

    override suspend fun update(namespace: String, changes: PreferenceChanges) = withContext(Dispatchers.IO) {
        changes.requireNamespace(namespace)
        if (changes.isEmpty) return@withContext
        mutex.withLock {
            val next = Properties().also { it.putAll(values) }
            changes.strings.forEach { (preferenceKey, value) ->
                val propertyKey = key(namespace, preferenceKey.name)
                if (value == null) next.remove(propertyKey) else next.setProperty(propertyKey, value)
            }
            changes.booleans.forEach { (preferenceKey, value) ->
                val propertyKey = key(namespace, preferenceKey.name)
                if (value == null) next.remove(propertyKey) else next.setProperty(propertyKey, value.toString())
            }
            persist(next)
            synchronized(stateLock) {
                values = next
                changes.strings.forEach { (preferenceKey, value) -> stringStates[preferenceKey]?.value = value }
                changes.booleans.forEach { (preferenceKey, value) ->
                    booleanStates[preferenceKey]?.value = value ?: preferenceKey.default
                }
            }
        }
    }

    private fun persist(next: Properties) {
        val parent = requireNotNull(file.parentFile)
        parent.mkdirs()
        val temporary = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            temporary.outputStream().use { next.store(it, "AppMarket") }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
