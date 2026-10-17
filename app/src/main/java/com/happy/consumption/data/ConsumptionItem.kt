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
    val breakfastCurrency: MoneyUnit = MoneyUnit.YUAN,
    val lunchCurrency: MoneyUnit = MoneyUnit.YUAN,
    val dinnerCurrency: MoneyUnit = MoneyUnit.YUAN,
    val otherCurrency: MoneyUnit = MoneyUnit.YUAN,
)
