package com.iot.roomfreshener.data.remote

import android.os.SystemClock
import android.util.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Kênh giao tiếp qua HTTP polling của Blynk Cloud. 
 * Thực thi đúng yêu cầu: đặt V0=0 để hỏi online, kiểm tra V2 xem có dữ liệu,
 * đọc Payload từ V3 và gửi lệnh lên V1.
 */
class BlynkHttpChannel(
    private val token: String,
    private val scope: CoroutineScope,
    private val baseEndpoint: String = "https://blynk.cloud/external/api",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    okHttpClient: OkHttpClient? = null
) : DeviceChannel {

    private val client: OkHttpClient = okHttpClient ?: defaultOkHttpClient()
    private val channelKind = ChannelKind.BLYNK

    private val _state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Idle)
    override val state: StateFlow<DeviceConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var worker: Job? = null

    override fun start() {
        // Nếu không có token thì coi như lỗi cấu hình và báo trạng thái Failed
        if (token.isBlank()) {
            Log.e(TAG, "start() missing Blynk token")
            _state.value = DeviceConnectionState.Failed(
                channelKind,
                IllegalStateException("Missing Blynk token"),
            )
            return
        }
        if (worker?.isActive == true) return
        Log.d(TAG, "start() launching polling workers")
        worker = scope.launch {
            supervisorScope {
                // Một job canh online, một job đọc inbox chạy song song
                _state.value = DeviceConnectionState.Connecting(channelKind)
                launch { onlineGuardLoop() }
                launch { inboxLoop() }
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "stop() requested")
        worker?.cancel()
        worker = null
        _state.value = DeviceConnectionState.Disconnected(channelKind, "stopped")
    }

    override suspend fun send(raw: String): Boolean {
        // Lệnh từ app -> ESP được đẩy lên V1 theo format JSON
        if (token.isBlank()) return false
        val ok = updatePin("V1", raw)
        Log.d(TAG, "send() success=$ok payload=${raw.take(128)}")
        return ok
    }

    private suspend fun onlineGuardLoop() {
        val ctx = currentCoroutineContext()
        while (ctx.isActive) {
            // Lặp lại kiểm tra online mỗi 5s bằng cách đặt V0 về 0 rồi đợi ESP set lại 1
            val connected = probeOnline()
            if (connected) {
                Log.d(TAG, "onlineGuardLoop() handshake success")
                _state.value = DeviceConnectionState.Connected(channelKind)
            } else {
                Log.w(TAG, "onlineGuardLoop() handshake timeout")
                _state.value = DeviceConnectionState.Disconnected(channelKind, "handshake-timeout")
            }
            delay(ONLINE_CHECK_INTERVAL_MS)
        }
    }

    private suspend fun inboxLoop() {
        val ctx = currentCoroutineContext()
        while (ctx.isActive) {
            if (_state.value is DeviceConnectionState.Connected) {
                // Flag V2 = 1 nghĩa là có dữ liệu JSON ở V3 chờ xử lý
                val flag = getPin("V2")?.trim()
                if (flag == "1") {
                    val payload = getPin("V3")
                    if (!payload.isNullOrBlank()) {
                        Log.v(TAG, "inboxLoop() payload=${payload.take(128)}")
                        _incoming.emit(payload)
                    }
                    // Luôn reset flag về 0 để ESP biết app đã đọc xong
                    updatePin("V2", "0")
                }
            }
            delay(INBOX_POLL_INTERVAL_MS)
        }
    }

    private suspend fun probeOnline(): Boolean {
        val ctx = currentCoroutineContext()
        if (!updatePin("V0", "0")) {
            Log.w(TAG, "probeOnline() failed to flip V0")
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + ONLINE_HANDSHAKE_TIMEOUT_MS
        while (ctx.isActive && SystemClock.elapsedRealtime() < deadline) {
            // Nếu V0 được đặt lại thành 1 bởi ESP thì coi như online thành công
            val current = getPin("V0")?.trim()
            if (current == "1") {
                //updatePin("V0", "0")
                return true
            }
            delay(ONLINE_HANDSHAKE_RETRY_MS)
        }
        return false
    }

    private suspend fun getPin(pin: String): String? {
        val suffix = "&${pin.lowercase()}"
        val url = buildUrl("get", suffix)
        return unwrapValue(executeGet(url))
    }

    private suspend fun updatePin(pin: String, value: String): Boolean {
        // API update trả về "200" (text) khi thành công
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        val suffix = "&${pin.lowercase()}=$encoded"
        val url = buildUrl("update", suffix)
        val response = executeGet(url)
        val success = response?.trim() == "200"
        Log.d(TAG, "updatePin($pin) success=$success")
        return success
    }

    private suspend fun executeGet(url: String): String? = withContext(dispatcher) {
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun buildUrl(path: String, suffix: String): String {
        // Chuẩn hóa base endpoint để tránh // dư
        val normalizedBase = baseEndpoint.trimEnd('/')
        val url = "$normalizedBase/$path?token=$token$suffix"
        Log.v(TAG, "buildUrl -> $url")
        return url
    }

    private fun unwrapValue(raw: String?): String? {
        // Phản hồi Blynk có thể ở dạng ["value"] nên cần bóc dấu [] và ""
        val trimmed = raw?.trim() ?: return null
        val withoutBrackets = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
        val unquoted = if (withoutBrackets.startsWith("\"") && withoutBrackets.endsWith("\"") && withoutBrackets.length >= 2) {
            withoutBrackets.substring(1, withoutBrackets.length - 1)
        } else {
            withoutBrackets
        }
        return unquoted.replace("\\\"", "\"")
    }

    private fun defaultOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    companion object {
        private const val ONLINE_CHECK_INTERVAL_MS = 5_000L
        private const val ONLINE_HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val ONLINE_HANDSHAKE_RETRY_MS = 500L
        private const val INBOX_POLL_INTERVAL_MS = 100L
        private const val TAG = "BlynkHttpChannel"
    }
}
