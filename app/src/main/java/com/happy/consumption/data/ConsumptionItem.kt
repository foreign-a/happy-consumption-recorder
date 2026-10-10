package com.happy.consumption.data

data class ConsumptionItem(
    val id: Long,
    val userId: Long,
    val date: String,
    val breakfast: Long,
    val lunch: Long,
    val dinner: Long,
    val other: Long,
    val isManual: Boolean,
    val updatedAt: Long,
)
