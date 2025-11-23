package com.iot.roomfreshener.data.repository

import com.iot.roomfreshener.data.model.Task
import com.iot.roomfreshener.data.model.TaskWriteRequest
import com.iot.roomfreshener.data.remote.EspWebSocketClient
import com.iot.roomfreshener.data.remote.TaskRemoteDataSource
import com.iot.roomfreshener.data.remote.TaskRemoteEvent
import com.iot.roomfreshener.data.remote.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TaskRepository(
    private val remote: TaskRemoteDataSource,
    private val socketClient: EspWebSocketClient,
    private val scope: CoroutineScope
) {

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _commandEvents = MutableSharedFlow<TaskRemoteEvent.CommandResult>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commandEvents: SharedFlow<TaskRemoteEvent.CommandResult> = _commandEvents.asSharedFlow()

    val connectionState: StateFlow<WebSocketState> = socketClient.state

    init {
        socketClient.connect()
        scope.launch {
            remote.events.collectLatest { event ->
                when (event) {
                    is TaskRemoteEvent.Snapshot -> _tasks.value = event.tasks
                    is TaskRemoteEvent.CommandResult -> _commandEvents.emit(event)
                    is TaskRemoteEvent.Failure -> _commandEvents.emit(
                        TaskRemoteEvent.CommandResult(
                            command = "error",
                            success = false,
                            message = event.message
                        )
                    )
                }
            }
        }

        scope.launch {
            connectionState.collectLatest { state ->
                if (state is WebSocketState.Connected) {
                    refreshTasks()
                }
            }
        }
    }

    suspend fun refreshTasks() {
        remote.requestAllTasks()
    }

    suspend fun createTask(request: TaskWriteRequest) {
        remote.createTask(request.copy(id = null))
    }

    suspend fun updateTask(request: TaskWriteRequest) {
        remote.updateTask(request)
    }

    suspend fun deleteTask(taskId: Int) {
        remote.deleteTask(taskId)
    }

    suspend fun setTaskEnabled(taskId: Int, enabled: Boolean) {
        remote.setTaskEnabled(taskId, enabled)
    }

    suspend fun sprayNow(durationMs: Long) {
        remote.sprayNow(durationMs)
    }

    fun reconnect() {
        socketClient.reconnect()
    }
}
