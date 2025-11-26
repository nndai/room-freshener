package com.iot.roomfreshener.data.model

/**
 * Mô tả cấu hình WiFi mà app gửi xuống ESP.
 */
data class WifiConfigPayload(
    val mode: ModeConnect = ModeConnect.WEBSOCKET,
    val ssidAp: String = "",
    val passwordAp: String = "",
    val ssid: String = "",
    val password: String = ""
)

/**
 * Trùng với enum ModeConnect trong firmware.
 */
enum class ModeConnect(val value: Int) {
    WEBSOCKET(0),
    BLYNK(1),
    MQTT(2);

    companion object {
        fun fromValue(value: Int): ModeConnect {
            return values().firstOrNull { it.value == value } ?: WEBSOCKET
        }
    }
}
