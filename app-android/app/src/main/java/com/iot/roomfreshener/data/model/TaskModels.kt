package com.iot.roomfreshener.data.model

/**
 * Domain representation của một lịch phun đồng bộ từ ESP07.
 */
data class Task(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val weekdayMask: Int,
    val enabled: Boolean,
    val durationMs: Long
) {
    val durationSeconds: Int get() = (durationMs / 1000L).toInt()
}

/**
 * Payload ghi dữ liệu task (dùng cho add/update/enable/...)
 */
data class TaskWriteRequest(
    val id: Int?,
    val hour: Int,
    val minute: Int,
    val weekdayMask: Int,
    val durationSeconds: Int,
    val enabled: Boolean
) {
    val durationMs: Long get() = durationSeconds * 1000L
}
