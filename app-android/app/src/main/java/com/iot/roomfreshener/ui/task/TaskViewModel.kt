package com.iot.roomfreshener.ui.task

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iot.roomfreshener.R
import com.iot.roomfreshener.adapter.TaskItem
import com.iot.roomfreshener.data.di.TaskRepositoryProvider
import com.iot.roomfreshener.data.model.Task
import com.iot.roomfreshener.data.model.TaskDayMapper
import com.iot.roomfreshener.data.model.TaskWriteRequest
import com.iot.roomfreshener.data.remote.DeviceConnectionState
import com.iot.roomfreshener.data.remote.TaskRemoteEvent
import com.iot.roomfreshener.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TaskViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TaskRepository = TaskRepositoryProvider.provide()
    private val dayLabels = application.resources.getStringArray(R.array.task_day_letters).toList()

    private val _uiState = MutableStateFlow(
        TaskUiState(
            items = emptyList(),
            isLoading = true,
            connectionState = DeviceConnectionState.Idle
        )
    )
    val uiState: StateFlow<TaskUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TaskUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TaskUiEvent> = _events.asSharedFlow()

    init {
        observeData()
        observeCommandResults()
        refresh()
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(repository.tasks, repository.connectionState) { tasks, connection ->
                TaskUiState(
                    items = tasks
                        .map { task -> task.toTaskItem() }
                        .sortedBy { it.hour * 60 + it.minute },
                    isLoading = false,
                    connectionState = connection
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    private fun observeCommandResults() {
        viewModelScope.launch {
            repository.commandEvents.collect { event ->
                handleCommandResult(event)
            }
        }
    }

    private fun handleCommandResult(event: TaskRemoteEvent.CommandResult) {
        if (event.success) {
            if (event.command in successCommands) {
                refresh()
            }
        } else {
            _events.tryEmit(
                TaskUiEvent.ShowMessage(event.message ?: "Không thể thực hiện thao tác.")
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.refreshTasks()
        }
    }

    fun saveTask(item: TaskItem) {
        val mask = TaskDayMapper.labelsToMask(item.repeatDays)
        val request = TaskWriteRequest(
            id = item.id.toInt().takeIf { it > 0 },
            hour = item.hour,
            minute = item.minute,
            weekdayMask = mask,
            durationSeconds = item.durationSeconds,
            enabled = item.enabled
        )
        viewModelScope.launch {
            if (request.id == null) {
                repository.createTask(request)
            } else {
                repository.updateTask(request)
            }
        }
    }

    fun deleteTask(item: TaskItem) {
        val id = item.id.toInt()
        if (id <= 0) {
            _events.tryEmit(TaskUiEvent.ShowMessage("Không tìm thấy ID lịch phun."))
            return
        }
        viewModelScope.launch {
            repository.deleteTask(id)
        }
    }

    fun toggleTask(item: TaskItem, enabled: Boolean) {
        val id = item.id.toInt()
        if (id <= 0) {
            _events.tryEmit(TaskUiEvent.ShowMessage("Không tìm thấy ID lịch phun."))
            return
        }
        viewModelScope.launch {
            repository.setTaskEnabled(id, enabled)
        }
    }

    fun sprayNow(durationMs: Long) {
        viewModelScope.launch {
            repository.sprayNow(durationMs)
        }
    }

    fun reconnect() {
        repository.reconnect()
    }

    private fun Task.toTaskItem(): TaskItem {
        val labels = TaskDayMapper.maskToLabels(weekdayMask, dayLabels)
        return TaskItem(
            id = id.toLong(),
            hour = hour,
            minute = minute,
            durationSeconds = (durationMs / 1000L).toInt().coerceAtLeast(1),
            repeatDays = labels,
            enabled = enabled
        )
    }

    companion object {
        private val successCommands = setOf(
            "addTaskSprayResponse",
            "editTaskSprayResponse",
            "removeTaskSprayResponse",
            "setTaskEnabledResponse"
        )
    }
}

data class TaskUiState(
    val items: List<TaskItem>,
    val isLoading: Boolean,
    val connectionState: DeviceConnectionState
)

sealed interface TaskUiEvent {
    data class ShowMessage(val message: String) : TaskUiEvent
}
