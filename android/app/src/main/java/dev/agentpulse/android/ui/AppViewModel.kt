package dev.agentpulse.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.agentpulse.android.data.PulseRepository
import dev.agentpulse.android.data.PulseUiState
import dev.agentpulse.android.data.TransportSettings
import dev.agentpulse.android.data.model.PulseTask
import dev.agentpulse.android.data.model.TerminalOutput
import dev.agentpulse.android.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The four main screens of design report 4.3 / 4.1. */
enum class PulseTab(val label: String) {
    Overview("总览"),
    Tasks("任务"),
    Live("动态"),
    Alerts("告警"),
}

/**
 * App-scoped state: navigation selection and user actions. Data flows straight from
 * [PulseRepository.uiState] — the single source of truth — so every screen shares the same
 * connection state, alert badge and timeline cursor.
 */
class AppViewModel(
    private val repository: PulseRepository,
) : ViewModel() {

    val uiState: StateFlow<PulseUiState> = repository.uiState

    private val _selectedTab = MutableStateFlow(PulseTab.Overview)
    val selectedTab: StateFlow<PulseTab> = _selectedTab.asStateFlow()

    /** Task highlighted in the left pane (landscape) or opened full-screen (phone). */
    private val _selectedTaskId = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

    /** Task opened as a detail surface. */
    private val _detailTaskId = MutableStateFlow<String?>(null)
    val detailTaskId: StateFlow<String?> = _detailTaskId.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _alertSheetId = MutableStateFlow<String?>(null)
    val alertSheetId: StateFlow<String?> = _alertSheetId.asStateFlow()

    // ------------------------------------------------------------ actions

    fun selectTab(tab: PulseTab) {
        _selectedTab.value = tab
        _showSettings.value = false
        _detailTaskId.value = null
    }

    fun openTaskDetail(taskId: String) {
        _selectedTaskId.value = taskId
        _detailTaskId.value = taskId
    }

    fun selectTask(taskId: String) {
        _selectedTaskId.value = taskId
    }

    fun closeDetail() {
        _detailTaskId.value = null
    }

    fun openSettings() {
        _showSettings.value = true
    }

    fun closeSettings() {
        _showSettings.value = false
    }

    fun openAlertSheet(alertId: String) {
        _alertSheetId.value = alertId
    }

    fun closeAlertSheet() {
        _alertSheetId.value = null
    }

    fun refresh() = viewModelScope.launch { repository.refresh() }

    fun acknowledgeAlert(id: String) = repository.acknowledgeAlert(id)

    fun clearReadAlerts() = repository.acknowledgeAllAlerts()

    fun updateTransport(transform: (TransportSettings) -> TransportSettings) =
        repository.updateTransport(transform)

    fun resetTransport() = repository.resetTransport()

    suspend fun loadTerminal(taskId: String, lines: Int = 50, ansi: Boolean = true): Result<TerminalOutput> =
        repository.loadTerminal(taskId, lines, ansi)

    fun taskById(id: String?): PulseTask? =
        id?.let { taskId -> uiState.value.tasks.firstOrNull { it.id == taskId } }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(container.repository) as T
            }
    }
}
