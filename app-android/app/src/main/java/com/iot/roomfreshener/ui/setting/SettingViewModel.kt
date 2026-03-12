package com.iot.roomfreshener.ui.setting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iot.roomfreshener.data.di.DeviceRepositoryProvider
import com.iot.roomfreshener.data.model.ModeConnect
import com.iot.roomfreshener.data.model.WifiConfigPayload
import com.iot.roomfreshener.data.remote.DeviceConnectionState
import com.iot.roomfreshener.data.remote.DeviceCommandEvent
import com.iot.roomfreshener.data.repository.DeviceControlRepository
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import com.iot.roomfreshener.data.model.SystemInfoSnapshot
import kotlinx.coroutines.launch

class SettingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DeviceControlRepository = DeviceRepositoryProvider.provide()

    val deviceTimeSeconds: StateFlow<Long?> = repository.deviceTimeSeconds
    val connectionState: StateFlow<DeviceConnectionState> = repository.connectionState
    val systemInfoSnapshot: StateFlow<SystemInfoSnapshot?> = repository.systemInfoSnapshot

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        observeCommandResults()
        refreshDeviceTime()
    }

    fun refreshDeviceTime() {
        viewModelScope.launch {
            repository.requestDeviceTime()
        }
    }

    fun syncDeviceTimeWithPhone() {
        val nowSeconds = Instant.now().epochSecond
        viewModelScope.launch {
            _isProcessing.value = true
            repository.syncDeviceTime(nowSeconds)
        }
    }

    fun refreshSystemInfo() {
        viewModelScope.launch {
            repository.refreshSystemInfo()
        }
    }

    fun submitWifiConfig(payload: WifiConfigPayload) {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.updateWifiConfig(payload)
        }
    }

    fun defaultMode(): ModeConnect = ModeConnect.WEBSOCKET

    private fun observeCommandResults() {
        viewModelScope.launch {
            repository.commandEvents.collect { event ->
                when (event.command) {
                    "setTimeResponse" -> handleTimeResponse(event)
                    "setWiFiConfigResponse" -> handleWifiResponse(event)
                    "error" -> if (!event.success) {
                        _isProcessing.value = false
                        _messages.tryEmit(event.message ?: "Không thể gửi lệnh tới thiết bị.")
                    }
                }
            }
        }
    }

    private fun handleTimeResponse(event: DeviceCommandEvent.CommandResult) {
        _isProcessing.value = false
        if (event.success) {
            _messages.tryEmit(event.message ?: "Đã đồng bộ thời gian với ESP.")
            refreshDeviceTime()
        } else {
            _messages.tryEmit(event.message ?: "Không thể đồng bộ thời gian.")
        }
    }

    private fun handleWifiResponse(event: DeviceCommandEvent.CommandResult) {
        _isProcessing.value = false
        if (event.success) {
            _messages.tryEmit(event.message ?: "Đã gửi cấu hình WiFi. Thiết bị sẽ khởi động lại.")
        } else {
            _messages.tryEmit(event.message ?: "Không thể cập nhật WiFi config.")
        }
    }
}
