package com.iot.roomfreshener.data.di

import com.iot.roomfreshener.BuildConfig
import com.iot.roomfreshener.data.remote.ChannelKind
import com.iot.roomfreshener.data.remote.DeviceCommandDataSource
import com.iot.roomfreshener.data.remote.EspWebSocketClient
import com.iot.roomfreshener.data.remote.HybridDeviceChannel
import com.iot.roomfreshener.data.remote.MqttDeviceChannel
import com.iot.roomfreshener.data.repository.DeviceControlRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object DeviceRepositoryProvider {

    @Volatile
    private var repository: DeviceControlRepository? = null

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun provide(): DeviceControlRepository {
        return repository ?: synchronized(this) {
            repository ?: buildRepository().also { repository = it }
        }
    }

    private fun buildRepository(): DeviceControlRepository {
        val websocketChannel = EspWebSocketClient(
            baseUrl = BuildConfig.WEBSOCKET_URL,
        )
        val mqttChannel = MqttDeviceChannel(
            host = BuildConfig.MQTT_HOST,
            port = BuildConfig.MQTT_PORT,
            username = BuildConfig.MQTT_USERNAME,
            password = BuildConfig.MQTT_PASSWORD,
            topicSend = BuildConfig.MQTT_TOPIC_SEND,
            topicReceive = BuildConfig.MQTT_TOPIC_RECEIVE,
            scope = appScope
        )
        val hybrid = HybridDeviceChannel(
            listOf(
                HybridDeviceChannel.ChannelEntry(ChannelKind.WEBSOCKET, websocketChannel),
                HybridDeviceChannel.ChannelEntry(ChannelKind.MQTT, mqttChannel)
            ),
            appScope
        )
        val remote = DeviceCommandDataSource(hybrid, appScope)
        return DeviceControlRepository(remote, hybrid, appScope)
    }
}
