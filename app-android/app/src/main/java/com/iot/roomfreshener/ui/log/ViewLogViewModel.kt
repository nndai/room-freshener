package com.iot.roomfreshener.ui.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iot.roomfreshener.data.di.DeviceRepositoryProvider
import com.iot.roomfreshener.data.model.LogChunk
import com.iot.roomfreshener.data.model.LogFileInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ViewLogViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DeviceRepositoryProvider.provide()
    private val logDir = File(application.filesDir, "spray_logs").apply { mkdirs() }

    private val _logsText = MutableStateFlow("Nhấn ĐỒNG BỘ để tải log...")
    val logsText: StateFlow<String> = _logsText.asStateFlow()

    private var currentFileIndex = 0
    private var remoteFiles = emptyList<LogFileInfo>()
    private var isDownloadingChunks = false

    init {
        viewModelScope.launch {
            repository.logFilesEvents.collect { files ->
                remoteFiles = files
                if (!isDownloadingChunks) {
                    currentFileIndex = 0
                    syncNextFile()
                }
            }
        }

        viewModelScope.launch {
            repository.logChunkEvents.collect { chunk ->
                handleChunk(chunk)
            }
        }
        
        displayLocalLogs()
    }

    fun startSync(silent: Boolean = false) {
        if (!silent) {
            _logsText.value = "Đang yêu cầu danh sách log từ thiết bị...\n"
        }
        viewModelScope.launch {
            repository.requestLogFiles()
        }
    }

    private fun syncNextFile() {
        if (currentFileIndex >= remoteFiles.size) {
            isDownloadingChunks = false
            displayLocalLogs()
            return
        }

        isDownloadingChunks = true

        val fileInfo = remoteFiles[currentFileIndex]
        val localFileName = fileInfo.name.substringAfterLast("/")
        val localFile = File(logDir, localFileName)
        val localSize = if (localFile.exists()) localFile.length() else 0L

        if (fileInfo.size > localSize) {
            _logsText.value = "Đang tải thêm dữ liệu cho ${localFileName} (${localSize}/${fileInfo.size} bytes)...\n"
            viewModelScope.launch {
                repository.readLogFile(fileInfo.name, localSize)
            }
        } else {
            currentFileIndex++
            syncNextFile()
        }
    }

    private fun handleChunk(chunk: LogChunk) {
        val localFileName = chunk.name.substringAfterLast("/")
        val localFile = File(logDir, localFileName)
        
        localFile.appendText(chunk.data)

        if (!chunk.isEOF) {
            val newOffset = localFile.length()
            viewModelScope.launch {
                repository.readLogFile(chunk.name, newOffset)
            }
        } else {
            currentFileIndex++
            syncNextFile()
        }
    }

    private fun displayLocalLogs() {
        val allLogs = StringBuilder()
        val files = logDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        
        if (files.isEmpty()) {
            _logsText.value = "Chưa có log nào được lưu trên điện thoại.\nHãy nhấn ĐỒNG BỘ."
            return
        }

        for (file in files) {
            allLogs.append("=== ${file.name} ===\n")
            allLogs.append(file.readText())
            allLogs.append("\n\n")
        }
        _logsText.value = allLogs.toString()
    }
}
