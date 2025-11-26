package com.iot.roomfreshener.data.repository

import android.util.Log
import com.iot.roomfreshener.data.model.HomeSnapshot
import com.iot.roomfreshener.data.model.Task
import com.iot.roomfreshener.data.model.TaskWriteRequest
import com.iot.roomfreshener.data.model.WifiConfigPayload
import com.iot.roomfreshener.data.remote.DeviceChannel
import com.iot.roomfreshener.data.remote.DeviceConnectionState
import com.iot.roomfreshener.data.remote.TaskRemoteDataSource
import com.iot.roomfreshener.data.remote.TaskRemoteEvent
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

/**
 * Repository gom tất cả luồng dữ liệu nhiệm vụ phun.
 * Nhiệm vụ chính: khởi động kênh kết nối, phát snapshot về UI,
 * relay sự kiện thành công/thất bại và cung cấp API thao tác.
 */
class TaskRepository(
    private val remote: TaskRemoteDataSource,
    private val deviceChannel: DeviceChannel,
    private val scope: CoroutineScope
) {

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _homeSnapshot = MutableStateFlow<HomeSnapshot?>(null)
    val homeSnapshot: StateFlow<HomeSnapshot?> = _homeSnapshot.asStateFlow()

    private val _deviceTimeSeconds = MutableStateFlow<Long?>(null)
    val deviceTimeSeconds: StateFlow<Long?> = _deviceTimeSeconds.asStateFlow()

    private val _commandEvents = MutableSharedFlow<TaskRemoteEvent.CommandResult>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commandEvents: SharedFlow<TaskRemoteEvent.CommandResult> = _commandEvents.asSharedFlow()

    val connectionState: StateFlow<DeviceConnectionState> = deviceChannel.state

    init {
        // Bắt đầu mở kết nối ngay khi repository được tạo
        Log.d(TAG, "init: start device channel")
        deviceChannel.start()
        scope.launch {
            remote.events.collectLatest { event ->
                Log.v(TAG, "remote event=$event")
                when (event) {
                    is TaskRemoteEvent.Snapshot -> _tasks.value = event.tasks
                    is TaskRemoteEvent.Home -> _homeSnapshot.value = event.snapshot
                    is TaskRemoteEvent.DeviceTime -> _deviceTimeSeconds.value = event.timestampSeconds
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
            // Mỗi lần kết nối thành công thì chủ động yêu cầu lại danh sách task
            connectionState.collectLatest { state ->
                Log.d(TAG, "connection state=$state")
                if (state is DeviceConnectionState.Connected) {
                    refreshTasks()
                    refreshHome()
                }
            }
        }
    }

    suspend fun refreshTasks() {
        Log.d(TAG, "refreshTasks()")
        remote.requestAllTasks()
    }

    suspend fun createTask(request: TaskWriteRequest) {
        Log.d(TAG, "createTask id=${request.id}")
        remote.createTask(request.copy(id = null))
    }

    suspend fun updateTask(request: TaskWriteRequest) {
        Log.d(TAG, "updateTask id=${request.id}")
        remote.updateTask(request)
    }

    suspend fun deleteTask(taskId: Int) {
        Log.d(TAG, "deleteTask id=$taskId")
        remote.deleteTask(taskId)
    }

    suspend fun setTaskEnabled(taskId: Int, enabled: Boolean) {
        Log.d(TAG, "setTaskEnabled id=$taskId enabled=$enabled")
        remote.setTaskEnabled(taskId, enabled)
    }

    suspend fun sprayNow(durationMs: Long) {
        Log.d(TAG, "sprayNow duration=$durationMs")
        remote.sprayNow(durationMs)
    }

    suspend fun refreshHome() {
        Log.d(TAG, "refreshHome()")
        remote.requestHomeData()
    }

    suspend fun requestDeviceTime() {
        Log.d(TAG, "requestDeviceTime()")
        remote.requestDeviceTime()
    }

    suspend fun syncDeviceTime(timestampSeconds: Long) {
        Log.d(TAG, "syncDeviceTime() timestamp=$timestampSeconds")
        remote.setDeviceTime(timestampSeconds)
    }

    suspend fun updateWifiConfig(payload: WifiConfigPayload) {
        Log.d(TAG, "updateWifiConfig() mode=${payload.mode}")
        remote.updateWifiConfig(payload)
    }

    fun reconnect() {
        // Cho phép UI yêu cầu kết nối lại (sẽ khởi động lại Hybrid channel)
        Log.d(TAG, "reconnect() restarting channel")
        deviceChannel.restart()
    }

    companion object {
        private const val TAG = "TaskRepository"
    }
}
