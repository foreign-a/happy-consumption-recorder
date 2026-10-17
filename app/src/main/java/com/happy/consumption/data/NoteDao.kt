package com.happy.consumption.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class NoteDao {
    @Query("SELECT date FROM notes WHERE userId=:userId AND date BETWEEN :start AND :end ORDER BY date")
    abstract suspend fun datesInRange(userId: Long, start: String, end: String): List<String>
    @Query("SELECT COUNT(*) FROM notes WHERE userId=:userId AND date>:date")
    abstract suspend fun countBeforeDate(userId: Long, date: String): Int
    @Query("UPDATE notes SET breakfastCurrency=COALESCE(breakfastCurrency,:currency), lunchCurrency=COALESCE(lunchCurrency,:currency), dinnerCurrency=COALESCE(dinnerCurrency,:currency), otherCurrency=COALESCE(otherCurrency,:currency) WHERE userId=:userId AND (breakfastCurrency IS NULL OR lunchCurrency IS NULL OR dinnerCurrency IS NULL OR otherCurrency IS NULL)")
    abstract suspend fun fillLegacyCurrencies(userId: Long, currency: String): Int
    @Query("SELECT MIN(date) FROM notes WHERE userId = :userId")
    abstract suspend fun earliestDate(userId: Long): String?
    @Query("SELECT * FROM notes WHERE userId = :userId AND date = :date LIMIT 1")
    abstract suspend fun queryByUserAndDate(userId: Long, date: String): NoteEntity?
    @Query("SELECT * FROM notes WHERE userId = :userId ORDER BY date DESC, id DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun queryByUserPaged(userId: Long, limit: Int, offset: Int): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE userId = :userId ORDER BY date DESC, id DESC LIMIT :limit")
    abstract fun observeByUser(userId: Long, limit: Int): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE id = :id AND userId = :userId LIMIT 1")
    abstract suspend fun queryByIdAndUser(id: Long, userId: Long): NoteEntity?
    @Query("SELECT COUNT(*) FROM notes WHERE userId = :userId")
    abstract fun countByUser(userId: Long): Flow<Int>
    @Query("SELECT COUNT(*) FROM notes WHERE userId = :userId")
    abstract suspend fun countByUserOnce(userId: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(note: NoteEntity): Long
    @Query("UPDATE notes SET breakfast=:breakfast, lunch=:lunch, dinner=:dinner, other=:other, isManual=:manual, updatedAt=:updatedAt, breakfastCurrency=COALESCE(:breakfastCurrency,breakfastCurrency), lunchCurrency=COALESCE(:lunchCurrency,lunchCurrency), dinnerCurrency=COALESCE(:dinnerCurrency,dinnerCurrency), otherCurrency=COALESCE(:otherCurrency,otherCurrency) WHERE id=:id AND userId=:userId")
    abstract suspend fun updateByIdAndUser(id: Long, userId: Long, breakfast: Long, lunch: Long, dinner: Long, other: Long, manual: Boolean, updatedAt: Long, breakfastCurrency: String? = null, lunchCurrency: String? = null, dinnerCurrency: String? = null, otherCurrency: String? = null): Int
    // 传入 id 不用于跨用户覆盖：以 userId/date 定位本用户的记录。
    @Transaction
    open suspend fun upsert(note: NoteEntity) {
        val old = queryByUserAndDate(note.userId, note.date)
        if (old == null) insertIfAbsent(note.copy(id = 0))
        else updateByIdAndUser(old.id, note.userId, note.breakfast, note.lunch, note.dinner, note.other, note.isManual, note.updatedAt, note.breakfastCurrency, note.lunchCurrency, note.dinnerCurrency, note.otherCurrency)
    }
    @Query("DELETE FROM notes WHERE id = :id AND userId = :userId")
    abstract suspend fun deleteByIdAndUser(id: Long, userId: Long): Int
    @Query("DELETE FROM notes WHERE userId = :userId")
    abstract suspend fun deleteByUser(userId: Long): Int
}
