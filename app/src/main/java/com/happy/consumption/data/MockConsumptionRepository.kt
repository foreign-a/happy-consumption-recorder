package com.happy.consumption.data

import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 阶段一仅在内存中提供占位数据，不执行真实增删改。
class MockConsumptionRepository(clock: Clock = Clock.systemDefaultZone()) {
    private val today = LocalDate.now(clock)
    private val state = MutableStateFlow(List(10) { index ->
        val amounts = when (index) {
            0 -> listOf(0L, 0L, 0L, 0L)
            1 -> listOf(0L, 999999L, 0L, 0L)
            else -> listOf(1550L, 2500L + index * 100, 2000L, 500L)
        }
        ConsumptionItem(
            id = index + 1L, userId = 1L, date = today.minusDays(index.toLong()).toString(),
            breakfast = amounts[0], lunch = amounts[1], dinner = amounts[2], other = amounts[3],
            isManual = index == 2 || index == 5, updatedAt = clock.millis(),
        )
    })
    val records: StateFlow<List<ConsumptionItem>> = state.asStateFlow()
}
