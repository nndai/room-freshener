package com.iot.roomfreshener.data.remote

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface DeviceChannel {
    val incoming: SharedFlow<String>
    val state: StateFlow<DeviceConnectionState>

    suspend fun send(raw: String): Boolean

    fun start()
    fun stop()

    fun restart() {
        stop()
        start()
    }
}
