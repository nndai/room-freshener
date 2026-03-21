package com.iot.roomfreshener.data.remote

import com.iot.roomfreshener.data.model.HomeSnapshot
import com.iot.roomfreshener.data.model.SystemInfoSnapshot
import com.iot.roomfreshener.data.model.Task
import com.iot.roomfreshener.data.model.LogFileInfo
import com.iot.roomfreshener.data.model.LogChunk

sealed interface DeviceCommandEvent {
    data class Snapshot(val tasks: List<Task>) : DeviceCommandEvent
    data class Home(val snapshot: HomeSnapshot) : DeviceCommandEvent
    data class SystemInfo(val info: SystemInfoSnapshot) : DeviceCommandEvent
    data class DeviceTime(val timestampSeconds: Long) : DeviceCommandEvent
    data class CommandResult(
        val command: String,
        val success: Boolean,
        val message: String? = null
    ) : DeviceCommandEvent
    data class Failure(val message: String) : DeviceCommandEvent
    data class LogFilesList(val files: List<LogFileInfo>) : DeviceCommandEvent
    data class LogFileChunkEvent(val chunk: LogChunk) : DeviceCommandEvent
}
