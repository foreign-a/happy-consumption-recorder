package com.happy.consumption.data

import com.happy.consumption.util.LogHelper

import java.time.Clock

interface AutoRecordGateway {
    suspend fun exists(userId: Long, date: String): Boolean
    suspend fun insertZeroIfAbsent(note: NoteEntity): Long
}

class AutoRecordGenerator(private val gateway: AutoRecordGateway, private val clock: Clock) {
    fun today(): String = DatePolicy(clock).today().toString()
    private fun validate(date: String) {
        if (date != today()) throw UserOperationException(UserError.INVALID_DATE)
    }
    suspend fun shouldGenerate(userId: Long, today: String): Boolean { validate(today); return !gateway.exists(userId, today) }
    suspend fun generate(userId: Long, today: String): Boolean {
        validate(today)
        if (gateway.exists(userId, today)) return false
        return gateway.insertZeroIfAbsent(NoteEntity(userId = userId, date = today, breakfast = 0, lunch = 0,
            dinner = 0, other = 0, isManual = false, updatedAt = clock.millis())) != -1L
    }
}
