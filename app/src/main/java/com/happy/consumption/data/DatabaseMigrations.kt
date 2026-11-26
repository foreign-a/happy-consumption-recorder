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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `ledger_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `requestId` TEXT NOT NULL, `date` TEXT NOT NULL, `kind` TEXT NOT NULL, `amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, `remark` TEXT NOT NULL, `isOtherIncome` INTEGER NOT NULL, FOREIGN KEY(`userId`) REFERENCES `user_identities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ledger_entries_userId_requestId` ON `ledger_entries` (`userId`, `requestId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_userId_date` ON `ledger_entries` (`userId`, `date`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `custom_remarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, FOREIGN KEY(`userId`) REFERENCES `user_identities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_remarks_userId_kind_name` ON `custom_remarks` (`userId`, `kind`, `name`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `screenshot_receipts` (`userId` INTEGER NOT NULL, `requestId` TEXT NOT NULL, PRIMARY KEY(`userId`, `requestId`), FOREIGN KEY(`userId`) REFERENCES `user_identities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}
