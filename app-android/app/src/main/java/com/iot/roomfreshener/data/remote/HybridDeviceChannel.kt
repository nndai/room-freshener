package com.iot.roomfreshener.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Giao tiếp “lai” giữa nhiều kênh. Kênh nào kết nối trước thì được chọn làm active,
 * đồng thời các kênh còn lại bị dừng để tiết kiệm tài nguyên.
 */
class HybridDeviceChannel(
    entries: List<ChannelEntry>,
    private val scope: CoroutineScope
) : DeviceChannel {

    private val channelEntries = entries.toList()
    private val _state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Idle)
    override val state: StateFlow<DeviceConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var active: ChannelEntry? = null

    init {
        require(channelEntries.isNotEmpty()) { "HybridDeviceChannel requires at least one entry" }
        observeChildren()
    }

    override fun start() {
        Log.d(TAG, "start() invoking on ${channelEntries.size} channels")
        if (channelEntries.isEmpty()) return
        _state.value = DeviceConnectionState.Connecting(null)
        channelEntries.forEach { it.channel.start() }
    }

    override fun stop() {
        Log.d(TAG, "stop() called, active=${active?.kind}")
        active = null
        channelEntries.forEach { it.channel.stop() }
        _state.value = DeviceConnectionState.Disconnected(null, "stopped")
    }

    override suspend fun send(raw: String): Boolean {
        val target = active?.channel ?: return false
        Log.d(TAG, "send() via ${active?.kind} payload=${raw.take(128)}")
        return target.send(raw)
    }

    private fun observeChildren() {
        // Lắng nghe dữ liệu và trạng thái của từng kênh con
        channelEntries.forEach { entry ->
            scope.launch {
                entry.channel.incoming.collect { payload ->
                    if (active?.channel == entry.channel) {
                        _incoming.emit(payload)
                    }
                }
            }
            scope.launch {
                entry.channel.state.collect { childState ->
                    handleChildState(entry, childState)
                }
            }
        }
    }

    private fun handleChildState(entry: ChannelEntry, childState: DeviceConnectionState) {
        Log.v(TAG, "handleChildState kind=${entry.kind} state=$childState active=${active?.kind}")
        when (childState) {
            is DeviceConnectionState.Connected -> activate(entry)
            is DeviceConnectionState.Disconnected -> onChildDisconnected(entry, childState.reason)
            is DeviceConnectionState.Failed -> onChildFailed(entry, childState.throwable)
            is DeviceConnectionState.Connecting -> if (active == null) {
                _state.value = DeviceConnectionState.Connecting(childState.channel ?: entry.kind)
            }
            DeviceConnectionState.Idle -> Unit
        }
    }

    private fun activate(entry: ChannelEntry) {
        // Nếu đã active rồi thì chỉ cập nhật state, ngược lại chuyển active sang entry mới
        if (active?.channel == entry.channel) {
            _state.value = DeviceConnectionState.Connected(entry.kind)
            return
        }
        Log.i(TAG, "activate() selecting ${entry.kind}")
        active = entry
        _state.value = DeviceConnectionState.Connected(entry.kind)
        // Các kênh khác được tắt để tránh chạy song song ngoài mong muốn
        channelEntries.filter { it.channel !== entry.channel }.forEach { it.channel.stop() }
    }

    private fun onChildDisconnected(entry: ChannelEntry, reason: String?) {
        Log.w(TAG, "onChildDisconnected kind=${entry.kind} reason=$reason")
        if (active?.channel == entry.channel) {
            active = null
            _state.value = DeviceConnectionState.Disconnected(entry.kind, reason)
            restartInactive(entry)
        } else if (active == null) {
            _state.value = DeviceConnectionState.Disconnected(entry.kind, reason)
        }
    }

    private fun onChildFailed(entry: ChannelEntry, throwable: Throwable) {
        Log.e(TAG, "onChildFailed kind=${entry.kind}", throwable)
        if (active?.channel == entry.channel) {
            active = null
            _state.value = DeviceConnectionState.Failed(entry.kind, throwable)
            restartInactive(entry)
        } else if (active == null) {
            _state.value = DeviceConnectionState.Failed(entry.kind, throwable)
        }
    }

    private fun restartInactive(exclude: ChannelEntry) {
        Log.d(TAG, "restartInactive() triggered by ${exclude.kind}")
        // Khi active bị mất, khởi động lại tất cả kênh để chọn kết nối mới
        channelEntries.filter { it.channel !== exclude.channel }.forEach { it.channel.start() }
        exclude.channel.start()
    }

    data class ChannelEntry(val kind: ChannelKind, val channel: DeviceChannel)

    companion object {
        private const val TAG = "HybridDeviceChannel"
    }
}
