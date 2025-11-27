package com.iot.roomfreshener.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iot.roomfreshener.data.di.DeviceRepositoryProvider
import com.iot.roomfreshener.data.model.HomeSnapshot
import com.iot.roomfreshener.data.remote.DeviceConnectionState
import com.iot.roomfreshener.data.repository.DeviceControlRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DeviceControlRepository = DeviceRepositoryProvider.provide()

    val homeSnapshot: StateFlow<HomeSnapshot?> = repository.homeSnapshot
    val connectionState: StateFlow<DeviceConnectionState> = repository.connectionState

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun refreshHome() {
        viewModelScope.launch {
            repository.refreshHome()
        }
    }

    fun sprayNow(durationMs: Long = DEFAULT_SPRAY_DURATION_MS) {
        viewModelScope.launch {
            runCatching { repository.sprayNow(durationMs) }
                .onFailure { _messages.tryEmit("Không thể gửi lệnh phun ngay.") }
        }
    }

    companion object {
        private const val DEFAULT_SPRAY_DURATION_MS = 5_000L
    }
}
