package com.iot.roomfreshener.data.di

import com.iot.roomfreshener.BuildConfig
import com.iot.roomfreshener.data.remote.BlynkHttpChannel
import com.iot.roomfreshener.data.remote.ChannelKind
import com.iot.roomfreshener.data.remote.EspWebSocketClient
import com.iot.roomfreshener.data.remote.HybridDeviceChannel
import com.iot.roomfreshener.data.remote.TaskRemoteDataSource
import com.iot.roomfreshener.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object TaskRepositoryProvider {

    private const val DEFAULT_WS_URL = "ws://192.168.137.1:82"

    @Volatile
    private var repository: TaskRepository? = null

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun provide(): TaskRepository {
        return repository ?: synchronized(this) {
            repository ?: buildRepository().also { repository = it }
        }
    }

    private fun buildRepository(): TaskRepository {
        val websocketChannel = EspWebSocketClient(DEFAULT_WS_URL)
        val blynkChannel = BlynkHttpChannel(
            token = BuildConfig.BLYNK_TOKEN,
            scope = appScope
        )
        val hybrid = HybridDeviceChannel(
            listOf(
                HybridDeviceChannel.ChannelEntry(ChannelKind.WEBSOCKET, websocketChannel),
                HybridDeviceChannel.ChannelEntry(ChannelKind.BLYNK, blynkChannel)
            ),
            appScope
        )
        val remote = TaskRemoteDataSource(hybrid, appScope)
        return TaskRepository(remote, hybrid, appScope)
    }
}
