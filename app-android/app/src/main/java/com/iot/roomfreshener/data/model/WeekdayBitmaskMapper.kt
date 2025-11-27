package com.iot.roomfreshener.data.model

/**
 * Chuyển đổi giữa bitmask weekday của firmware và danh sách ký hiệu trong UI.
 */
object WeekdayBitmaskMapper {
    private val firmwareOrder = listOf("CN", "T2", "T3", "T4", "T5", "T6", "T7")
    private val labelToBit by lazy {
        firmwareOrder.mapIndexed { index, label -> label to index }.toMap()
    }

    fun maskToLabels(mask: Int, uiOrder: List<String> = firmwareOrder): List<String> {
        if (mask == 0) return emptyList()
        val selected = mutableSetOf<String>()
        firmwareOrder.forEachIndexed { index, label ->
            if (mask and (1 shl index) != 0) {
                selected += label
            }
        }
        return uiOrder.filter { selected.contains(it) }
    }

    fun labelsToMask(labels: Collection<String>): Int {
        var mask = 0
        labels.forEach { label ->
            val bit = labelToBit[label]?.let { 1 shl it } ?: 0
            mask = mask or bit
        }
        return mask
    }
}
