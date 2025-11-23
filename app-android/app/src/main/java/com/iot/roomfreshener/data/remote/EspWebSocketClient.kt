package com.iot.roomfreshener.data.remote

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
) {

    private val client: OkHttpClient = okHttpClient ?: defaultOkHttpClient()
    private var socket: WebSocket? = null

    private val _state = MutableStateFlow<WebSocketState>(WebSocketState.Idle)
    val state: StateFlow<WebSocketState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = WebSocketState.Connected(baseUrl)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            _state.value = WebSocketState.Disconnected(reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            _state.value = WebSocketState.Disconnected(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            _state.value = WebSocketState.Failed(t)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _messages.tryEmit(text)
        }
    }

    fun connect() {
        if (_state.value is WebSocketState.Connected || _state.value is WebSocketState.Connecting) {
            return
        }
        _state.value = WebSocketState.Connecting
        val request = Request.Builder().url(baseUrl).build()
        socket = client.newWebSocket(request, listener)
    }

    fun reconnect() {
        disconnect("manual-reconnect")
        connect()
    }

    fun disconnect(reason: String? = null) {
        socket?.close(1000, reason ?: "client-disconnect")
        socket = null
        _state.value = WebSocketState.Disconnected(reason)
    }

    fun send(text: String): Boolean = socket?.send(text) ?: false

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
}
