package com.iot.roomfreshener.data.remote

import android.util.Base64
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

class TaskRemoteDataSource(
    private val client: EspWebSocketClient,
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
            client.messages.collect { raw ->
                handleIncoming(raw)
            }
        }
    }

    suspend fun requestAllTasks() {
        sendJson(
            JSONObject().apply { put("command", "getAllTaskSpray") }
        )
    }

    suspend fun createTask(request: TaskWriteRequest) {
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
            client.send(payload)
        }
        if (!sent) {
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
}
