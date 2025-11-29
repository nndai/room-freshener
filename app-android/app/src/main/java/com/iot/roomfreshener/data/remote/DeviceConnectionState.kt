package com.iot.roomfreshener.data.remote

/**
 * Trạng thái kết nối tổng quát cho mọi kênh truyền thông đến ESP07.
 */
sealed interface DeviceConnectionState {
    data object Idle : DeviceConnectionState
    data class Connecting(val channel: ChannelKind? = null) : DeviceConnectionState
    data class Connected(val channel: ChannelKind) : DeviceConnectionState
    data class Disconnected(val channel: ChannelKind?, val reason: String?) : DeviceConnectionState
    data class Failed(val channel: ChannelKind?, val throwable: Throwable) : DeviceConnectionState
}

enum class ChannelKind {
    WEBSOCKET,
    MQTT
}
