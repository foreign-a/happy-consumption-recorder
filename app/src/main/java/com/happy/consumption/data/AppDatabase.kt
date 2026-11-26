package com.happy.consumption.data

import androidx.room.*

@Database(entities = [UserIdentity::class, NoteEntity::class, LedgerEntity::class, CustomRemarkEntity::class, ScreenshotReceipt::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun ledger(): LedgerDao
    abstract fun identities(): UserIdentityDao
}
