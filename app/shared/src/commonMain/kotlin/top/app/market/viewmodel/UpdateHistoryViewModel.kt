package top.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.update.UpdateHistoryEntry
import top.app.market.domain.repository.UpdateHistoryRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class UpdateHistoryViewModel(
    private val store: UpdateHistoryRepository,
) : ViewModel() {
    val entries: StateFlow<List<UpdateHistoryEntry>> = store.entries
    fun clear() {
        viewModelScope.launch { store.clear() }
    }
}
