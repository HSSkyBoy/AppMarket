package top.app.market.domain.repository

import top.app.market.domain.model.update.UpdateHistoryEntry
import kotlinx.coroutines.flow.StateFlow

/** Local installation/update history. */
interface UpdateHistoryRepository {
    val entries: StateFlow<List<UpdateHistoryEntry>>
    suspend fun markPending(entry: UpdateHistoryEntry)
    suspend fun commitPending(packageName: String)
    suspend fun removePending(packageName: String)
    suspend fun pendingEntries(): List<UpdateHistoryEntry>
    suspend fun clear()
}
