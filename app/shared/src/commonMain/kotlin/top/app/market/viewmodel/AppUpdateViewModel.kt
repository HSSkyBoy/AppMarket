package top.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.app.market.domain.model.update.AppReleaseInfo
import top.app.market.domain.repository.AppUpdateRepository

sealed interface AppUpdateEvent {
    data object AlreadyLatest : AppUpdateEvent
    data class CheckFailed(val message: String?) : AppUpdateEvent
}

class AppUpdateViewModel(
    private val appUpdateRepository: AppUpdateRepository,
) : ViewModel() {
    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _newRelease = MutableStateFlow<AppReleaseInfo?>(null)
    val newRelease: StateFlow<AppReleaseInfo?> = _newRelease.asStateFlow()

    private val _events = MutableSharedFlow<AppUpdateEvent>()
    val events: SharedFlow<AppUpdateEvent> = _events.asSharedFlow()

    fun checkForUpdate(currentVersionName: String, manual: Boolean) {
        if (_isChecking.value) return
        viewModelScope.launch {
            _isChecking.value = true
            try {
                val release = appUpdateRepository.checkForUpdate(currentVersionName)
                if (release != null) {
                    _newRelease.value = release
                } else if (manual) {
                    _events.emit(AppUpdateEvent.AlreadyLatest)
                }
            } catch (e: Exception) {
                if (manual) {
                    _events.emit(AppUpdateEvent.CheckFailed(e.message))
                }
            } finally {
                _isChecking.value = false
            }
        }
    }

    fun dismissUpdate() {
        _newRelease.value = null
    }
}
