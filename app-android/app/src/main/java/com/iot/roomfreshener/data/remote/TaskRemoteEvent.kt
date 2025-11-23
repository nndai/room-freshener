package com.iot.roomfreshener.data.remote

import com.iot.roomfreshener.data.model.Task

sealed interface TaskRemoteEvent {
    data class Snapshot(val tasks: List<Task>) : TaskRemoteEvent
    data class CommandResult(
        val command: String,
        val success: Boolean,
        val message: String? = null
    ) : TaskRemoteEvent
    data class Failure(val message: String) : TaskRemoteEvent
}
