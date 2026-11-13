package com.happy.consumption.data

data class SpendingShare(val meal: Meal, val amount: Long, val fraction: Float)
fun dailyShares(item: ConsumptionItem, unit: MoneyUnit): List<SpendingShare> {
    val amounts = Meal.entries.map { if (item.currencies[it] == unit) item.value(it) else 0L }
    val total = item.totalCents(unit).toDouble()
    return Meal.entries.map { SpendingShare(it, amounts[it.ordinal], if (total == 0.0) 0f else (amounts[it.ordinal].toDouble() / total).toFloat()) }
}
object ChartColors {
    val defaults = listOf("#387EF5", "#F4A340", "#8D67D5", "#35A889")
    fun normalize(value: String): String? = value.trim().let {
        if (it.matches(Regex("#?[0-9a-fA-F]{6}"))) "#" + it.removePrefix("#").uppercase(java.util.Locale.ROOT) else null
    }
}
