package com.iot.roomfreshener.data.model

data class LogFileInfo(
    val name: String,
    val size: Long
)

data class LogChunk(
    val name: String,
    val offset: Long,
    val data: String,
    val isEOF: Boolean
)
