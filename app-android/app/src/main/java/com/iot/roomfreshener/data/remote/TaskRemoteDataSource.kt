package com.iot.roomfreshener.data.remote

import android.util.Base64
import com.iot.roomfreshener.data.model.HomeSnapshot
import com.iot.roomfreshener.data.model.SprayMoment
import com.iot.roomfreshener.data.model.Task
import com.iot.roomfreshener.data.model.TaskWriteRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tầng gửi/nhận JSON tới ESP. Không quan tâm kênh vật lý,
 * chỉ cần một DeviceChannel để đẩy/gom chuỗi JSON.
 */
class TaskRemoteDataSource(
    private val channel: DeviceChannel,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _events = MutableSharedFlow<TaskRemoteEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TaskRemoteEvent> = _events.asSharedFlow()

    init {
        scope.launch(dispatcher) {
            channel.incoming.collect { raw ->
                handleIncoming(raw)
            }
        }
    }

    suspend fun requestAllTasks() {
        // Lệnh đọc toàn bộ lịch; ESP sẽ trả snapshot kèm base64
        sendJson(
            JSONObject().apply { put("command", "getAllTaskSpray") }
        )
    }

    suspend fun requestHomeData() {
        // Đọc dữ liệu tổng quan cho màn hình Home
        sendJson(
            JSONObject().apply { put("command", "getHomeData") }
        )
    }

    suspend fun createTask(request: TaskWriteRequest) {
        // Thêm task mới: truyền đầy đủ thông tin giờ/phút/weekday/duration
        sendJson(
            JSONObject().apply {
                put("command", "addTaskSpray")
                put("hour", request.hour)
                put("minute", request.minute)
                put("weekday", request.weekdayMask)
                put("duration", request.durationMs)
                put("enabled", request.enabled)
            }
        )
    }

    suspend fun updateTask(request: TaskWriteRequest) {
        val id = request.id ?: return
        // Cập nhật task hiện tại dựa trên ID đã có
        sendJson(
            JSONObject().apply {
                put("command", "editTaskSpray")
                put("taskId", id)
                put("hour", request.hour)
                put("minute", request.minute)
                put("weekday", request.weekdayMask)
                put("duration", request.durationMs)
                put("enabled", request.enabled)
            }
        )
    }

    suspend fun deleteTask(taskId: Int) {
        sendJson(
            JSONObject().apply {
                put("command", "removeTaskSpray")
                put("taskId", taskId)
            }
        )
    }

    suspend fun setTaskEnabled(taskId: Int, enabled: Boolean) {
        // Lưu ý firmware dùng khóa "task_id" khác với các lệnh khác
        sendJson(
            JSONObject().apply {
                put("command", "setTaskEnabled")
                put("task_id", taskId)
                put("enabled", enabled)
            }
        )
    }

    suspend fun sprayNow(durationMs: Long) {
        sendJson(
            JSONObject().apply {
                put("command", "sprayNow")
                put("duration", durationMs)
            }
        )
    }

    private suspend fun sendJson(json: JSONObject) {
        val payload = json.toString()
        val sent = withContext(dispatcher) {
            channel.send(payload)
        }
        if (!sent) {
            // Nếu kênh hiện tại chưa kết nối thành công -> báo lỗi UI biết
            _events.tryEmit(
                TaskRemoteEvent.Failure(
                    "Không thể gửi lệnh ${json.optString("command", "").ifBlank { "n/a" }}"
                )
            )
        }
    }

    private fun handleIncoming(raw: String) {
        try {
            val json = JSONObject(raw)
            val command = json.optString("command")
            when (command) {
                "getAllTasksSprayResponse" -> emitSnapshot(json)
                "getHomeDataResponse" -> emitHome(json)
                "addTaskSprayResponse",
                "removeTaskSprayResponse",
                "editTaskSprayResponse",
                "setTaskEnabledResponse" -> emitCommandResult(command, json)
                else -> Unit
            }
        } catch (ex: JSONException) {
            _events.tryEmit(TaskRemoteEvent.Failure("Json error: ${ex.message}"))
        }
    }

    private fun emitSnapshot(json: JSONObject) {
        val tasks = parseTasks(json)
        _events.tryEmit(TaskRemoteEvent.Snapshot(tasks))
    }

    private fun emitHome(json: JSONObject) {
        val snapshot = parseHome(json)
        _events.tryEmit(TaskRemoteEvent.Home(snapshot))
    }

    private fun emitCommandResult(command: String, json: JSONObject) {
        _events.tryEmit(
            TaskRemoteEvent.CommandResult(
                command = command,
                success = json.optBoolean("status", false),
                message = json.optString("message")
            )
        )
    }

    private fun parseTasks(json: JSONObject): List<Task> {
        val count = if (json.has("countask")) {
            json.optInt("countask", 0)
        } else {
            json.optInt("countTask", 0)
        }
        val sizePerTask = json.optInt("sizeTask", 0)
        val data = json.optString("dataTask", "")
        if (count <= 0 || sizePerTask <= 0 || data.isBlank()) {
            return emptyList()
        }
        val decoded = Base64.decode(data, Base64.DEFAULT)
        val buffer = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
        val tasks = mutableListOf<Task>()
        val safeCount = minOf(count, decoded.size / sizePerTask)
        repeat(safeCount) {
            val record = ByteArray(sizePerTask)
            buffer.get(record)
            // Map từng byte đúng layout firmware gửi về
            val id = record.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
            val hour = record.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
            val minute = record.getOrNull(2)?.toInt()?.and(0xFF) ?: 0
            val weekday = record.getOrNull(3)?.toInt()?.and(0xFF) ?: 0
            val enabled = (record.getOrNull(4)?.toInt() ?: 0) != 0
            val durationBytes = record.copyOfRange(maxOf(sizePerTask - 4, 0), sizePerTask)
            val duration = ByteBuffer.wrap(durationBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .toLong()
                .and(0xFFFF_FFFFL)
            tasks += Task(
                id = id,
                hour = hour,
                minute = minute,
                weekdayMask = weekday,
                enabled = enabled,
                durationMs = duration
            )
        }
        return tasks
    }

    private fun parseHome(json: JSONObject): HomeSnapshot {
        val temp = json.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() }
        val humidity = json.optDouble("humidity", Double.NaN).takeIf { !it.isNaN() }

        val lastHour = optIntOrNull(json, "lastSprayHourTime")
        val lastMinute = optIntOrNull(json, "lastSprayMinuteTime")
        val lastDuration = json.optLong("lastSprayDurationMs", 0L)
        val last = if (lastHour != null || lastMinute != null || lastDuration > 0) {
            SprayMoment(
                hour = lastHour,
                minute = lastMinute,
                durationMs = lastDuration,
                reason = optIntOrNull(json, "lastSprayReason")
            )
        } else {
            null
        }

        val nextHour = optIntOrNull(json, "nextSprayHourTime")
        val nextMinute = optIntOrNull(json, "nextSprayMinuteTime")
        val nextDuration = json.optLong("nextSprayDurationMs", 0L)
        val next = if (nextHour != null || nextMinute != null || nextDuration > 0) {
            SprayMoment(
                hour = nextHour,
                minute = nextMinute,
                durationMs = nextDuration,
                reason = null
            )
        } else {
            null
        }

        return HomeSnapshot(
            temperature = temp,
            humidity = humidity,
            lastSpray = last,
            nextSpray = next,
            totalSprayCount = json.optLong("totalSprayCount", 0L),
            totalSprayDuration = json.optLong("totalSprayDuration", 0L)
        )
    }

    private fun optIntOrNull(json: JSONObject, key: String): Int? {
        return if (json.has(key) && !json.isNull(key)) json.optInt(key) else null
    }
}
