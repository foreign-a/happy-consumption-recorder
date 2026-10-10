package com.happy.consumption.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class NoteDao {
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
    @Query("UPDATE notes SET breakfast=:breakfast, lunch=:lunch, dinner=:dinner, other=:other, isManual=:manual, updatedAt=:updatedAt WHERE id=:id AND userId=:userId")
    abstract suspend fun updateByIdAndUser(id: Long, userId: Long, breakfast: Long, lunch: Long, dinner: Long, other: Long, manual: Boolean, updatedAt: Long): Int
    // 传入 id 不用于跨用户覆盖：以 userId/date 定位本用户的记录。
    @Transaction
    open suspend fun upsert(note: NoteEntity) {
        val old = queryByUserAndDate(note.userId, note.date)
        if (old == null) insertIfAbsent(note.copy(id = 0))
        else updateByIdAndUser(old.id, note.userId, note.breakfast, note.lunch, note.dinner, note.other, note.isManual, note.updatedAt)
    }
    @Query("DELETE FROM notes WHERE id = :id AND userId = :userId")
    abstract suspend fun deleteByIdAndUser(id: Long, userId: Long): Int
    @Query("DELETE FROM notes WHERE userId = :userId")
    abstract suspend fun deleteByUser(userId: Long): Int
}
