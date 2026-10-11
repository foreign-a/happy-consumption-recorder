package com.happy.consumption.data

import com.happy.consumption.util.LogHelper

import java.time.*

class DatePolicy(val clock: Clock) {
    fun today(): LocalDate = LocalDate.now(clock)
    fun previousThirtyDays(earliest: String?): List<String> {
        val anchor = earliest?.let(::parseManual) ?: today()
        return try { (1L..30L).map { anchor.minusDays(it).toString() } }
        catch (_: DateTimeException) { throw UserOperationException(UserError.INVALID_DATE) }
    }
    fun parseManual(date: String): LocalDate {
        val parsed = try { LocalDate.parse(date) } catch (e: DateTimeException) { LogHelper.error("DatePolicy", "Operation failed; recovery preserved", e); throw UserOperationException(UserError.INVALID_DATE) }
        if (parsed.toString() != date || parsed > today()) { LogHelper.info("Validation", "Invalid or future date rejected"); throw UserOperationException(UserError.INVALID_DATE) }
        return parsed
    }
    fun utcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun fromUtcMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    fun generateDates(start: String, end: String): List<String> {
        val first = LocalDate.parse(start); val last = LocalDate.parse(end)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.map { it.toString() }.toList()
    }
}
