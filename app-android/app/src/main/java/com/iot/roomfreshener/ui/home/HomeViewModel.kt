package com.iot.roomfreshener.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iot.roomfreshener.data.di.DeviceRepositoryProvider
import com.iot.roomfreshener.data.model.HomeSnapshot
import com.iot.roomfreshener.data.remote.DeviceConnectionState
import com.iot.roomfreshener.data.repository.DeviceControlRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import com.iot.roomfreshener.data.remote.DeviceCommandEvent

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DeviceControlRepository = DeviceRepositoryProvider.provide()

    val homeSnapshot: StateFlow<HomeSnapshot?> = repository.homeSnapshot
    val connectionState: StateFlow<DeviceConnectionState> = repository.connectionState

    val activeTasksCount: StateFlow<Int> = repository.tasks
        .map { list -> list.count { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var requestStartTime = 0L

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val prefs = getApplication<Application>()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _sprayDurationMs = MutableStateFlow(
        prefs.getLong(PREF_APP_BUTTON_DURATION_MS, DEFAULT_SPRAY_DURATION_MS)
    )
    val sprayDurationMs: StateFlow<Long> = _sprayDurationMs.asStateFlow()

    init {
        viewModelScope.launch {
            repository.commandEvents.collect { event ->
                if (event.command == "sprayNowResponse") {
                    _isProcessing.value = false
                    if (!event.success) {
                        _messages.tryEmit(event.message ?: "Lệnh xịt thất bại.")
                    } else {
                        _messages.tryEmit("Đã gửi lệnh xịt thành công.")
                        refreshHome()
                    }
                } else if (event.command == "stopSprayResponse") {
                    _isProcessing.value = false
                    if (!event.success) {
                        _messages.tryEmit(event.message ?: "Không thể tắt spray.")
                    } else {
                        _messages.tryEmit(event.message ?: "Đã gửi lệnh tắt spray.")
                        refreshHome()
                    }
                } else if (event.command == "getHomeDataResponse") {
                    if (requestStartTime > 0L) {
                        _latencyMs.value = System.currentTimeMillis() - requestStartTime
                        requestStartTime = 0L
                    }
                }
            }
        }
    }

    fun refreshHome() {
        requestStartTime = System.currentTimeMillis()
        viewModelScope.launch {
            repository.refreshHome()
        }
    }

    fun sprayNow() {
        val durationMs = _sprayDurationMs.value
        viewModelScope.launch {
            _isProcessing.value = true
            runCatching { repository.sprayNow(durationMs) }
                .onFailure { 
                    _isProcessing.value = false
                    _messages.tryEmit("Không thể gửi lệnh phun ngay.") 
                }
        }
    }

    fun stopSpray() {
        viewModelScope.launch {
            _isProcessing.value = true
            runCatching { repository.stopSpray() }
                .onFailure {
                    _isProcessing.value = false
                    _messages.tryEmit("Không thể gửi lệnh tắt spray.")
                }
        }
    }

    fun setSprayDurationSeconds(seconds: Long) {
        val clampedSeconds = seconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
        val durationMs = clampedSeconds * 1000L
        prefs.edit().putLong(PREF_APP_BUTTON_DURATION_MS, durationMs).apply()
        _sprayDurationMs.value = durationMs
        _messages.tryEmit("Đã cập nhật thời gian phun: ${clampedSeconds}s")
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val PREF_APP_BUTTON_DURATION_MS = "app_button_duration_ms"
        private const val DEFAULT_SPRAY_DURATION_MS = 1_000L
        private const val MIN_DURATION_SECONDS = 1L
        private const val MAX_DURATION_SECONDS = 4000000000L
    }
}
