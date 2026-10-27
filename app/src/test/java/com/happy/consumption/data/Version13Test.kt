package com.happy.consumption.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class Version13Test {
    @Test fun zoneControlsTodayAcrossDateBoundary() {
        val instant = Instant.parse("2026-10-21T23:30:00Z")
        assertEquals("2026-10-22", DatePolicy(Clock.fixed(instant, ZoneId.of("Asia/Shanghai"))).today().toString())
        assertEquals("2026-10-21", DatePolicy(Clock.fixed(instant, ZoneId.of("America/Los_Angeles"))).today().toString())
    }
    @Test fun closestMealUsesConfiguredZoneAndTimes() {
        val schedule = MealSchedule("Asia/Shanghai")
        assertEquals(Meal.BREAKFAST, schedule.suggested(Instant.parse("2026-10-21T00:00:00Z")))
        assertEquals(Meal.LUNCH, schedule.suggested(Instant.parse("2026-10-21T04:30:00Z")))
        assertEquals(Meal.DINNER, schedule.suggested(Instant.parse("2026-10-21T11:00:00Z")))
        assertEquals(Meal.OTHER, schedule.suggested(Instant.parse("2026-10-21T15:30:00Z")))
        assertEquals(Meal.BREAKFAST, schedule.copy(breakfast=LocalTime.of(6,0)).suggested(Instant.parse("2026-10-20T22:00:00Z")))
    }
    @Test fun mealWindowIsInclusiveAndWrapsMidnight() {
        val schedule=MealSchedule("UTC",LocalTime.of(1,0),LocalTime.of(12,0),LocalTime.of(18,0))
        assertEquals(Meal.BREAKFAST,schedule.suggested(Instant.parse("2026-10-21T22:00:00Z")))
        assertEquals(Meal.OTHER,schedule.suggested(Instant.parse("2026-10-21T21:59:59Z")))
    }
    @Test fun daylightSavingUsesActualZoneRules() {
        val schedule=MealSchedule("America/New_York")
        assertEquals(Meal.BREAKFAST,schedule.suggested(Instant.parse("2026-07-01T12:00:00Z")))
        assertEquals(Meal.BREAKFAST,schedule.suggested(Instant.parse("2026-12-01T13:00:00Z")))
    }
    @Test fun invalidScheduleIsRejected() {
        for (block in listOf<() -> Unit>({ MealSchedule("Not/AZone") },
            { MealSchedule("UTC",LocalTime.NOON,LocalTime.of(8,0)) },
            { MealSchedule("UTC", dinner=LocalTime.NOON) })) {
            try { block(); fail("Expected invalid schedule") } catch (_: RuntimeException) { }
        }
    }
}
