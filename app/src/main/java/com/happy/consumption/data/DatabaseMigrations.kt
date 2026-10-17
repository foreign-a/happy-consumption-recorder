package com.happy.consumption.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (field in listOf("breakfastCurrency", "lunchCurrency", "dinnerCurrency", "otherCurrency")) {
            db.execSQL("ALTER TABLE notes ADD COLUMN $field TEXT DEFAULT NULL")
        }
    }
}
