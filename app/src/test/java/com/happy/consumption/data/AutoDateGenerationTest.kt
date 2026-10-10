package com.happy.consumption.data

// 移植自 QA AutoDateGenerationTest.java；生产 DatePolicy + 注入 Clock。
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AutoDateGenerationTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val policy = DatePolicy(clock)
    @Test fun leapYear() { assertEquals(listOf("2024-02-27", "2024-02-28", "2024-02-29", "2024-03-01", "2024-03-02"), policy.generateDates("2024-02-27", "2024-03-02")) }
    @Test fun commonYear() { assertEquals(listOf("2025-02-27", "2025-02-28", "2025-03-01", "2025-03-02"), policy.generateDates("2025-02-27", "2025-03-02")) }
    @Test fun yearAndMonthBoundary() { assertEquals(listOf("2024-12-30", "2024-12-31", "2025-01-01", "2025-01-02"), policy.generateDates("2024-12-30", "2025-01-02")) }
    @Test fun complete1011Days() {
        val dates = policy.generateDates("2024-01-01", "2026-10-07")
        assertEquals(1011, dates.size); assertEquals(dates.size, dates.toSet().size)
        assertTrue(dates.zipWithNext().all { (a, b) -> LocalDate.parse(a).plusDays(1).toString() == b })
    }
    @Test fun injectedTimeAndUtcPicker() {
        assertEquals("2026-10-08", policy.today().toString())
        val date = LocalDate.parse("2024-02-29")
        assertEquals(date, policy.fromUtcMillis(policy.utcMillis(date)))
        val west = DatePolicy(Clock.fixed(Instant.parse("2026-01-01T00:30:00Z"), ZoneId.of("America/Los_Angeles")))
        assertEquals("2025-12-31", west.today().toString())
    }
    @Test fun futureAndInvalidDateRejected() {
        for (date in listOf("2026-10-09", "2025-02-29", "2024-2-29")) {
            try { policy.parseManual(date); fail("invalid date accepted") } catch (e: UserOperationException) { assertEquals(UserError.INVALID_DATE, e.reason) }
        }
    }
    @Test fun datasetUserOneComplete() {
        assertEquals(policy.generateDates("2024-01-01", "2026-10-07"), DatasetFixture.rows.filter { it[0] == "1" }.map { it[1] }.sorted())
    }
}
