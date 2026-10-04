package top.app.market.domain.repository

/** Recent search keywords. */
interface SearchHistoryRepository {
    suspend fun load(): List<String>
    suspend fun add(keyword: String)
    suspend fun remove(keyword: String)
    suspend fun clear()
}
