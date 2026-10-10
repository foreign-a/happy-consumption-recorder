package com.happy.consumption.data

import androidx.room.*

@Database(entities = [UserIdentity::class, NoteEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun identities(): UserIdentityDao
}
