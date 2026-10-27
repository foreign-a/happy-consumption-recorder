package com.happy.consumption.data

import android.content.Context
import java.time.*

data class MealSchedule(val zone: String = ZoneId.systemDefault().id,
    val breakfast: LocalTime = LocalTime.of(8, 0), val lunch: LocalTime = LocalTime.of(12, 0),
    val dinner: LocalTime = LocalTime.of(18, 0)) {
    init { ZoneId.of(zone); require(breakfast < lunch && lunch < dinner) }
    // Select the closest meal on the circular 24-hour clock; outside a three-hour window select Other.
    fun suggested(instant: Instant): Meal {
        val now = instant.atZone(ZoneId.of(zone)).toLocalTime().toSecondOfDay()
        val nearest = listOf(breakfast, lunch, dinner).mapIndexed { i, time ->
            val delta = kotlin.math.abs(now - time.toSecondOfDay())
            Meal.entries[i] to minOf(delta, 86400 - delta)
        }.minBy { it.second }
        return if (nearest.second <= 3 * 3600) nearest.first else Meal.OTHER
    }
}
class PersonalSchedule(context: Context, userId: Long) {
    private val prefs = context.getSharedPreferences("schedule_$userId", Context.MODE_PRIVATE)
    fun read(): MealSchedule = try {
        MealSchedule(prefs.getString("zone", ZoneId.systemDefault().id)!!,
            LocalTime.parse(prefs.getString("breakfast", "08:00")),
            LocalTime.parse(prefs.getString("lunch", "12:00")), LocalTime.parse(prefs.getString("dinner", "18:00")))
    } catch (_: Exception) { MealSchedule() }
    fun save(value: MealSchedule): Boolean = prefs.edit().putString("zone", value.zone)
        .putString("breakfast", value.breakfast.toString()).putString("lunch", value.lunch.toString())
        .putString("dinner", value.dinner.toString()).commit()
    fun chartColors(): List<String> = Meal.entries.map { meal ->
        ChartColors.normalize(prefs.getString("chart_${meal.name}", null) ?: "") ?: ChartColors.defaults[meal.ordinal]
    }
    fun saveChartColors(colors: List<String>): Boolean {
        if (colors.size != 4) return false
        val valid = colors.map { ChartColors.normalize(it) ?: return false }
        val editor = prefs.edit()
        Meal.entries.forEach { editor.putString("chart_${it.name}", valid[it.ordinal]) }
        return editor.commit()
    }
    fun clock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of(read().zone)
        override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
        override fun instant(): Instant = Instant.now()
    }
}
