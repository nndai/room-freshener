package com.iot.roomfreshener.data.remote

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class EspWebSocketClient(
    private val baseUrl: String,
    okHttpClient: OkHttpClient? = null
) : DeviceChannel {

    private val client: OkHttpClient = okHttpClient ?: defaultOkHttpClient()
    private var socket: WebSocket? = null
    private val channelKind = ChannelKind.WEBSOCKET

    private val _state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Idle)
    override val state: StateFlow<DeviceConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected: $baseUrl")
            _state.value = DeviceConnectionState.Connected(channelKind)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket closing: code=$code reason=$reason")
            socket = null
            _state.value = DeviceConnectionState.Disconnected(channelKind, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: code=$code reason=$reason")
            socket = null
            _state.value = DeviceConnectionState.Disconnected(channelKind, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            socket = null
            _state.value = DeviceConnectionState.Failed(channelKind, t)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.v(TAG, "Received payload: ${text.take(128)}")
            _incoming.tryEmit(text)
        }
    }

    override fun start() {
        Log.d(TAG, "start() called, current state=${_state.value}")
        if (_state.value is DeviceConnectionState.Connected || _state.value is DeviceConnectionState.Connecting) {
            return
        }
        _state.value = DeviceConnectionState.Connecting(channelKind)
        val request = Request.Builder().url(baseUrl).build()
        socket = client.newWebSocket(request, listener)
    }

    override fun stop() {
        Log.d(TAG, "stop() called")
        socket?.close(1000, "client-disconnect")
        socket = null
        _state.value = DeviceConnectionState.Disconnected(channelKind, "stopped")
    }

    override suspend fun send(raw: String): Boolean {
        val sent = socket?.send(raw) ?: false
        Log.d(TAG, "send() success=$sent payload=${raw.take(128)}")
        return sent
    }

    private fun defaultOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .addInterceptor(logging)
            .build()
    }

    companion object {
        private const val TAG = "EspWebSocket"
    }
}
