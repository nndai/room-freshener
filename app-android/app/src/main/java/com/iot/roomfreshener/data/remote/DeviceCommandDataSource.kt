package com.iot.roomfreshener.data.remote

import android.util.Base64
import android.util.Log
import com.iot.roomfreshener.data.model.HomeSnapshot
import com.iot.roomfreshener.data.model.SystemInfoSnapshot
import com.iot.roomfreshener.data.model.SprayMoment
import com.iot.roomfreshener.data.model.Task
import com.iot.roomfreshener.data.model.TaskWriteRequest
import com.iot.roomfreshener.data.model.WifiConfigPayload
import com.iot.roomfreshener.data.model.LogFileInfo
import com.iot.roomfreshener.data.model.LogChunk
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
class DeviceCommandDataSource(
    private val channel: DeviceChannel,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _events = MutableSharedFlow<DeviceCommandEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<DeviceCommandEvent> = _events.asSharedFlow()

    init {
        scope.launch(dispatcher) {
            channel.incoming.collect { raw ->
                Log.v(TAG, "incoming payload=${raw.take(128)}")
                handleIncoming(raw)
            }
        }
    }

    suspend fun requestAllTasks() {
        Log.d(TAG, "requestAllTasks()")
        // Lệnh đọc toàn bộ lịch; ESP sẽ trả snapshot kèm base64
        sendJson(
            JSONObject().apply { put("command", "getAllTaskSpray") }
        )
    }

    suspend fun requestSystemInfo() {
        Log.d(TAG, "requestSystemInfo()")
        sendJson(
            JSONObject().apply { put("command", "getEspInfo") }
        )
    }

    suspend fun requestHomeData() {
        Log.d(TAG, "requestHomeData()")
        // Đọc dữ liệu tổng quan cho màn hình Home
        sendJson(
            JSONObject().apply { put("command", "getHomeData") }
        )
    }

    suspend fun requestDeviceTime() {
        Log.d(TAG, "requestDeviceTime()")
        sendJson(
            JSONObject().apply { put("command", "getTime") }
        )
    }

    suspend fun setDeviceTime(timestampSeconds: Long) {
        Log.d(TAG, "setDeviceTime() timestamp=$timestampSeconds")
        sendJson(
            JSONObject().apply {
                put("command", "setTime")
                put("timestamp", timestampSeconds)
            }
        )
    }

    suspend fun updateWifiConfig(payload: WifiConfigPayload) {
        Log.d(TAG, "updateWifiConfig() mode=${payload.mode} ssidAp=${payload.ssidAp}")
        sendJson(
            JSONObject().apply {
                put("command", "setWiFiConfig")
                put("modeConnect", payload.mode.value)
                put("ssidAp", payload.ssidAp)
                put("passwordAp", payload.passwordAp)
                put("ssid", payload.ssid)
                put("password", payload.password)
            }
        )
    }

    suspend fun createTask(request: TaskWriteRequest) {
        Log.d(TAG, "createTask payload=$request")
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
        Log.d(TAG, "updateTask id=$id payload=$request")
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
        Log.d(TAG, "deleteTask id=$taskId")
        sendJson(
            JSONObject().apply {
                put("command", "removeTaskSpray")
                put("taskId", taskId)
            }
        )
    }

    suspend fun setTaskEnabled(taskId: Int, enabled: Boolean) {
        // Lưu ý firmware dùng khóa "task_id" khác với các lệnh khác
        Log.d(TAG, "setTaskEnabled id=$taskId enabled=$enabled")
        sendJson(
            JSONObject().apply {
                put("command", "setTaskEnabled")
                put("task_id", taskId)
                put("enabled", enabled)
            }
        )
    }

    suspend fun sprayNow(durationMs: Long) {
        Log.d(TAG, "sprayNow duration=$durationMs")
        sendJson(
            JSONObject().apply {
                put("command", "sprayNow")
                put("duration", durationMs)
            }
        )
    }

    suspend fun stopSpray() {
        Log.d(TAG, "stopSpray()")
        sendJson(
            JSONObject().apply {
                put("command", "stopSpray")
            }
        )
    }

    suspend fun setSystemSettings(hwButtonDurationMs: Long) {
        Log.d(TAG, "setSystemSettings duration=$hwButtonDurationMs")
        sendJson(
            JSONObject().apply {
                put("command", "setSystemSettings")
                put("hwButtonDurationMs", hwButtonDurationMs)
            }
        )
    }

    suspend fun requestLogFiles() {
        Log.d(TAG, "requestLogFiles()")
        sendJson(JSONObject().apply { put("command", "getLogFiles") })
    }

    suspend fun readLogFile(name: String, offset: Long) {
        Log.d(TAG, "readLogFile() name=$name offset=$offset")
        sendJson(JSONObject().apply {
            put("command", "readLogFile")
            put("name", name)
            put("offset", offset)
        })
    }

    private suspend fun sendJson(json: JSONObject) {
        val payload = json.toString()
        Log.v(TAG, "sendJson payload=${payload.take(128)}")
        val sent = withContext(dispatcher) {
            channel.send(payload)
        }
        if (!sent) {
            // Nếu kênh hiện tại chưa kết nối thành công -> báo lỗi UI biết
            _events.tryEmit(
                DeviceCommandEvent.Failure(
                    "Không thể gửi lệnh ${json.optString("command", "").ifBlank { "n/a" }}"
                )
            )
        }
    }

    private fun handleIncoming(raw: String) {
        try {
            val json = JSONObject(raw)
            val command = json.optString("command")
            Log.v(TAG, "handleIncoming command=$command")
            when (command) {
                "getAllTasksSprayResponse" -> emitSnapshot(json)
                "getHomeDataResponse" -> emitHome(json)
                "getEspInfoResponse" -> emitSystemInfo(json)
                "getTimeResponse" -> emitDeviceTime(json)
                "getLogFilesResponse" -> emitLogFiles(json)
                "readLogFileResponse" -> emitLogChunk(json)
                "addTaskSprayResponse",
                "removeTaskSprayResponse",
                "editTaskSprayResponse",
                "setTaskEnabledResponse",
                "setTimeResponse",
                "sprayNowResponse",
                "stopSprayResponse",
                "setWiFiConfigResponse",
                "setSystemSettingsResponse" -> emitCommandResult(command, json)
                else -> Unit
            }
        } catch (ex: JSONException) {
            Log.e(TAG, "handleIncoming() json error", ex)
            _events.tryEmit(DeviceCommandEvent.Failure("Json error: ${ex.message}"))
        }
    }

    private fun emitSnapshot(json: JSONObject) {
        val tasks = parseTasks(json)
        _events.tryEmit(DeviceCommandEvent.Snapshot(tasks))
        _events.tryEmit(DeviceCommandEvent.CommandResult("getAllTasksSprayResponse", true, ""))
    }

    private fun emitHome(json: JSONObject) {
        val snapshot = parseHome(json)
        Log.d(TAG, "emitHome temperature=${snapshot.temperature} total=${snapshot.totalSprayCount}")
        _events.tryEmit(DeviceCommandEvent.Home(snapshot))
        _events.tryEmit(DeviceCommandEvent.CommandResult("getHomeDataResponse", true, ""))
    }

    private fun emitDeviceTime(json: JSONObject) {
        if (!json.has("timestamp")) return
        val timestamp = json.optLong("timestamp", 0L)
        if (timestamp > 0) {
            _events.tryEmit(DeviceCommandEvent.DeviceTime(timestamp))
        }
    }

    private fun emitSystemInfo(json: JSONObject) {
        val info = SystemInfoSnapshot(
            chipId = json.optLong("chipId", 0),
            coreVersion = json.optString("coreVersion", ""),
            sdkVersion = json.optString("sdkVersion", ""),
            cpuFreqMHz = json.optInt("cpuFreqMHz", 0),
            flashChipId = json.optString("flashChipId", ""),
            flashChipSizeKb = json.optLong("flashChipSizeKb", 0),
            flashChipRealSizeKb = json.optLong("flashChipRealSizeKb", 0),
            flashChipSpeedMHz = json.optInt("flashChipSpeedMHz", 0),
            flashChipMode = json.optInt("flashChipMode", 0),
            freeHeap = json.optLong("freeHeap", 0),
            heapFragmentation = json.optInt("heapFragmentation", 0),
            maxFreeBlockSize = json.optLong("maxFreeBlockSize", 0),
            sketchSizeKb = json.optLong("sketchSizeKb", 0),
            freeSketchSpaceKb = json.optLong("freeSketchSpaceKb", 0),
            sketchMD5 = json.optString("sketchMD5", ""),
            resetReason = json.optString("resetReason", ""),
            bootMode = json.optInt("bootMode", 0),
            vccMv = json.optInt("vccMv", 0),
            uptime = json.optString("uptime", ""),
            appVersion = json.optString("appVersion", ""),
            loopMqttRunning = json.optBoolean("loopMqttRunning", false),
            loopWebsocketRunning = json.optBoolean("loopWebsocketRunning", false),
            wifiSsid = json.optString("wifiSsid", ""),
            wifiRssi = json.optInt("wifiRssi", 0),
            wifiMode = json.optInt("wifiMode", 0),
            wifiStatus = json.optInt("wifiStatus", 0),
            wifiIp = json.optString("wifiIp", ""),
            wifiGateway = json.optString("wifiGateway", ""),
            wifiSubnet = json.optString("wifiSubnet", ""),
            wifiMac = json.optString("wifiMac", ""),
            wifiChannel = json.optInt("wifiChannel", 0),
            wifiAutoReconnect = json.optBoolean("wifiAutoReconnect", false),
            wifiSleepMode = json.optBoolean("wifiSleepMode", false),
            fsTotalBytes = json.optLong("fsTotalBytes", 0),
            fsUsedBytes = json.optLong("fsUsedBytes", 0),
            hwButtonDurationMs = json.optLong("hwButtonDurationMs", 3000),
            configMode = json.optInt("configMode", 0),
            configSsidAp = json.optString("configSsidAp", ""),
            configSsid = json.optString("configSsid", "")
        )
        _events.tryEmit(DeviceCommandEvent.SystemInfo(info))
    }

    private fun emitCommandResult(command: String, json: JSONObject) {
        _events.tryEmit(
            DeviceCommandEvent.CommandResult(
                command = command,
                success = json.optBoolean("status", false),
                message = json.optString("message")
            )
        )
    }

    private fun emitLogFiles(json: JSONObject) {
        val filesArray = json.optJSONArray("files") ?: return
        val list = mutableListOf<LogFileInfo>()
        for (i in 0 until filesArray.length()) {
            val obj = filesArray.optJSONObject(i) ?: continue
            list.add(LogFileInfo(
                name = obj.optString("name", ""),
                size = obj.optLong("size", 0L)
            ))
        }
        _events.tryEmit(DeviceCommandEvent.LogFilesList(list))
    }

    private fun emitLogChunk(json: JSONObject) {
        val chunk = LogChunk(
            name = json.optString("name", ""),
            offset = json.optLong("offset", 0L),
            data = json.optString("data", ""),
            isEOF = json.optBoolean("isEOF", false)
        )
        _events.tryEmit(DeviceCommandEvent.LogFileChunkEvent(chunk))
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
            totalSprayDuration = json.optLong("totalSprayDuration", 0L),
            sprayActive = json.optBoolean("sprayActive", false)
        )
    }

    private fun optIntOrNull(json: JSONObject, key: String): Int? {
        return if (json.has(key) && !json.isNull(key)) json.optInt(key) else null
    }

    companion object {
        private const val TAG = "DeviceCommandDataSource"
    }
}
