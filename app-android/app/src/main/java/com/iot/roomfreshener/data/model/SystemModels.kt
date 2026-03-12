package com.iot.roomfreshener.data.model

data class SystemInfoSnapshot(
    val chipId: Long = 0,
    val coreVersion: String = "",
    val sdkVersion: String = "",
    val cpuFreqMHz: Int = 0,
    val flashChipId: String = "",
    val flashChipSizeKb: Long = 0,
    val flashChipRealSizeKb: Long = 0,
    val flashChipSpeedMHz: Int = 0,
    val flashChipMode: Int = 0,
    val freeHeap: Long = 0,
    val heapFragmentation: Int = 0,
    val maxFreeBlockSize: Long = 0,
    val sketchSizeKb: Long = 0,
    val freeSketchSpaceKb: Long = 0,
    val sketchMD5: String = "",
    val resetReason: String = "",
    val bootMode: Int = 0,
    val vccMv: Int = 0,
    val uptime: String = "",
    val appVersion: String = "",
    val loopMqttRunning: Boolean = false,
    val loopWebsocketRunning: Boolean = false,
    val wifiSsid: String = "",
    val wifiRssi: Int = 0,
    val wifiMode: Int = 0,
    val wifiStatus: Int = 0,
    val wifiIp: String = "",
    val wifiGateway: String = "",
    val wifiSubnet: String = "",
    val wifiMac: String = "",
    val wifiChannel: Int = 0,
    val wifiAutoReconnect: Boolean = false,
    val wifiSleepMode: Boolean = false
) {
    fun toFormattedString(): String {
        return """
            =================
            RUNTIME STATUS
            =================
            App Version: $appVersion
            Uptime: $uptime
            MQTT Loop Running: $loopMqttRunning
            Websocket Loop Running: $loopWebsocketRunning
            
            =================
            HARDWARE INFO
            =================
            Chip ID: $chipId
            Core Version: $coreVersion
            SDK Version: $sdkVersion
            CPU Frequency: $cpuFreqMHz MHz
            Flash Chip ID: 0x$flashChipId
            Flash Size: $flashChipSizeKb KB (Real: $flashChipRealSizeKb KB)
            Flash Speed: $flashChipSpeedMHz MHz
            Flash Mode: $flashChipMode
            Free Heap: $freeHeap bytes
            Heap Frag: $heapFragmentation%
            Max Free Block: $maxFreeBlockSize bytes
            Sketch Size: $sketchSizeKb KB
            Free Sketch: $freeSketchSpaceKb KB
            Sketch MD5: $sketchMD5
            Reset Reason: $resetReason
            Boot Mode: $bootMode
            Vcc: $vccMv mV
            
            =================
            WIFI
            =================
            SSID: $wifiSsid
            RSSI: $wifiRssi dBm
            Mode: $wifiMode
            Status: $wifiStatus
            IP Address: $wifiIp
            Gateway: $wifiGateway
            Subnet: $wifiSubnet
            MAC: $wifiMac
            Channel: $wifiChannel
            Auto Reconnect: $wifiAutoReconnect
            Sleep Mode: $wifiSleepMode
        """.trimIndent()
    }
}
