package com.iot.roomfreshener.data.remote

sealed interface WebSocketState {
    data object Idle : WebSocketState
    data object Connecting : WebSocketState
    data class Connected(val url: String) : WebSocketState
    data class Disconnected(val reason: String?) : WebSocketState
    data class Failed(val throwable: Throwable) : WebSocketState
}
