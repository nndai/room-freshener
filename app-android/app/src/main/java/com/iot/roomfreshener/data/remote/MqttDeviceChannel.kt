package com.iot.roomfreshener.data.remote

import android.util.Log
import java.util.UUID
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.text.Charsets
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Kênh MQTT TLS để giao tiếp với ESP. Sử dụng cùng topic cho hai chiều,
 * nên bất cứ payload JSON nào tới topic đều được forward cho tầng trên.
 */
class MqttDeviceChannel(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val topicSend: String,
    private val topicReceive: String,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : DeviceChannel {

    private val channelKind = ChannelKind.MQTT

    private val _state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Idle)
    override val state: StateFlow<DeviceConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var client: MqttClient? = null
    private var connectJob: Job? = null
    private var handshakeJob: Job? = null
    @Volatile
    private var handshakeComplete = false

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            Log.d(TAG, "connectComplete() reconnect=$reconnect uri=$serverURI")
            scope.launch(dispatcher) {
                runCatching { client?.subscribe(topicReceive, 1) }
                requestHandshake()
            }
        }

        override fun connectionLost(cause: Throwable?) {
            Log.w(TAG, "connectionLost: ${cause?.message}", cause)
            client = null
            handshakeJob?.cancel()
            handshakeJob = null
            _state.value = DeviceConnectionState.Disconnected(channelKind, cause?.message)
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            val payload = message?.payload?.toString(Charsets.UTF_8) ?: return
            Log.v(TAG, "messageArrived payload=${payload.take(128)}")
            handleIncoming(payload)
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    override fun start() {
        if (!isConfigValid()) {
            Log.e(TAG, "start() invalid config host=$host port=$port topic=$topicSend")
            _state.value = DeviceConnectionState.Failed(channelKind, IllegalStateException("Missing MQTT config"))
            return
        }
        if (client?.isConnected == true || connectJob?.isActive == true) {
            Log.d(TAG, "start() ignored, already connecting/connected")
            return
        }
        Log.d(TAG, "start() connecting to $host:$port topic=$topicSend")
        connectJob = scope.launch(dispatcher) {
            runConnect()
        }
    }

    override fun stop() {
        Log.d(TAG, "stop() requested")
        connectJob?.cancel()
        connectJob = null
        handshakeJob?.cancel()
        handshakeJob = null
        handshakeComplete = false
        scope.launch(dispatcher) {
            disconnectInternal("stopped")
        }
    }

    override suspend fun send(raw: String): Boolean {
        val current = client ?: return false
        if (!current.isConnected) return false
        return withContext(dispatcher) {
            runCatching {
                val payload = raw.toByteArray(Charsets.UTF_8)
                current.publish(topicSend, payload, 1, false)
                Log.d(TAG, "send() publish ok payload=${raw.take(128)}")
                true
            }.getOrElse {
                Log.e(TAG, "send() publish failed", it)
                false
            }
        }
    }

    private suspend fun runConnect() {
        try {
            _state.value = DeviceConnectionState.Connecting(channelKind)
            val uri = "ssl://$host:$port"
            Log.d(TAG, "runConnect() dialing $uri")
            val mqttClient = MqttClient(uri, buildClientId(), MemoryPersistence()).apply {
                setCallback(callback)
            }
            client = mqttClient
            val options = buildOptions()
            mqttClient.connect(options)
            mqttClient.subscribe(topicReceive, 1)
            Log.d(TAG, "runConnect() connected, awaiting handshake")
            requestHandshake()
        } catch (ex: Exception) {
            Log.e(TAG, "runConnect() failed", ex)
            _state.value = DeviceConnectionState.Failed(channelKind, ex)
            disconnectInternal(ex.message, emitState = false)
        } finally {
            connectJob = null
            if (client?.isConnected != true) {
                client = null
            }
        }
    }

    private suspend fun disconnectInternal(reason: String?, emitState: Boolean = true) {
        val current = client
        if (current != null) {
            runCatching { current.unsubscribe(topicReceive) }
            runCatching { current.disconnectForcibly(1000, 1000) }
            runCatching { current.close() }
        }
        client = null
        handshakeJob?.cancel()
        handshakeJob = null
        handshakeComplete = false
        if (emitState) {
            Log.d(TAG, "disconnectInternal() reason=$reason")
            _state.value = DeviceConnectionState.Disconnected(channelKind, reason)
        }
    }

    private fun requestHandshake() {
        handshakeComplete = false
        handshakeJob?.cancel()
        handshakeJob = scope.launch(dispatcher) {
            while (isActive && client?.isConnected == true && !handshakeComplete) {
                Log.d(TAG, "requestHandshake() sending probe")
                val ok = publishInternal(HANDSHAKE_COMMAND)
                if (!ok) {
                    Log.e(TAG, "requestHandshake() failed to publish handshake, retrying in ${HANDSHAKE_RETRY_INTERVAL_MS}ms")
                    delay(HANDSHAKE_RETRY_INTERVAL_MS)
                    continue
                }
                var waited = 0L
                while (
                    isActive &&
                    client?.isConnected == true &&
                    !handshakeComplete &&
                    waited < HANDSHAKE_RETRY_INTERVAL_MS
                ) {
                    delay(HANDSHAKE_POLL_INTERVAL_MS)
                    waited += HANDSHAKE_POLL_INTERVAL_MS
                }
                if (!handshakeComplete && client?.isConnected == true && isActive) {
                    Log.w(TAG, "requestHandshake() timeout, retrying")
                }
            }
            Log.d(TAG, "requestHandshake() loop finished handshakeComplete=$handshakeComplete connected=${client?.isConnected == true}")
        }
    }

    private fun handleIncoming(payload: String) {
        if (!handshakeComplete) {
            val command = runCatching {
                JSONObject(payload).optString("command")
            }.getOrNull()
            if (command == HANDSHAKE_RESPONSE) {
                Log.d(TAG, "handleIncoming() handshake confirmed")
                handshakeComplete = true
                handshakeJob?.cancel()
                handshakeJob = null
                _state.value = DeviceConnectionState.Connected(channelKind)
            }
        }
        if (handshakeComplete) {
            scope.launch(dispatcher) {
                Log.v(TAG, "handleIncoming() emit payload=${payload.take(128)}")
                _incoming.emit(payload)
            }
        }
    }

    private fun buildOptions(): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 20
            val user = this@MqttDeviceChannel.username
            if (user.isNotBlank()) {
                userName = user
            }
            val pwd = this@MqttDeviceChannel.password
            if (pwd.isNotBlank()) {
                password = pwd.toCharArray()
            }
            socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        }
    }

    private fun buildClientId(): String {
        return "room-freshener-app-${UUID.randomUUID()}"
    }

    private fun isConfigValid(): Boolean {
        return host.isNotBlank() && topicSend.isNotBlank() && port > 0
    }

    private suspend fun publishInternal(raw: String): Boolean {
        val current = client ?: return false
        return runCatching {
            current.publish(topicSend, raw.toByteArray(Charsets.UTF_8), 1, false)
            Log.v(TAG, "publishInternal() sent payload=${raw.take(128)}")
            true
        }.getOrElse {
            Log.e(TAG, "publishInternal() failed", it)
            false
        }
    }

    companion object {
        private const val HANDSHAKE_RETRY_INTERVAL_MS = 5_000L
        private const val HANDSHAKE_POLL_INTERVAL_MS = 250L
        private const val HANDSHAKE_RESPONSE = "getHomeDataResponse"
        private const val HANDSHAKE_COMMAND = "{\"command\":\"getHomeData\"}"
        private const val TAG = "MqttDeviceChannel"
    }
}
