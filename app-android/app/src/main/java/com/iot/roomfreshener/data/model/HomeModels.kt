package com.iot.roomfreshener.data.model

/**
 * Thông tin tổng quan dùng cho màn hình Home.
 */
data class HomeSnapshot(
    val temperature: Double? = null,
    val humidity: Double? = null,
    val lastSpray: SprayMoment? = null,
    val nextSpray: SprayMoment? = null,
    val totalSprayCount: Long = 0,
    val totalSprayDuration: Long = 0,
    val sprayActive: Boolean = false
)

/**
 * Mô tả một lần phun (trước đó hoặc kế tiếp).
 */
data class SprayMoment(
    val hour: Int?,
    val minute: Int?,
    val durationMs: Long,
    val reason: Int? = null
) {
    val hasValidTime: Boolean
        get() = hour != null && hour in 0..23 && minute != null && minute in 0..59
}
