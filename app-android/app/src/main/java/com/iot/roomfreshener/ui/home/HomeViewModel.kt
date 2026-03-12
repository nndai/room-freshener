package com.iot.roomfreshener.ui.home

import android.app.Application
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.iot.roomfreshener.data.remote.DeviceCommandEvent

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DeviceControlRepository = DeviceRepositoryProvider.provide()

    val homeSnapshot: StateFlow<HomeSnapshot?> = repository.homeSnapshot
    val connectionState: StateFlow<DeviceConnectionState> = repository.connectionState

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var requestStartTime = 0L

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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

    fun sprayNow(durationMs: Long = DEFAULT_SPRAY_DURATION_MS) {
        viewModelScope.launch {
            _isProcessing.value = true
            runCatching { repository.sprayNow(durationMs) }
                .onFailure { 
                    _isProcessing.value = false
                    _messages.tryEmit("Không thể gửi lệnh phun ngay.") 
                }
        }
    }

    companion object {
        private const val DEFAULT_SPRAY_DURATION_MS = 5_000L
    }
}
