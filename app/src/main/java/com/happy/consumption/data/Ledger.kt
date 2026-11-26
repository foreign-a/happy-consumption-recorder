package com.happy.consumption.data

import androidx.room.*

enum class EntryKind { INCOME, EXPENSE }
sealed interface ScreenshotRemark {
    data class BuiltInMeal(val meal: Meal) : ScreenshotRemark
    data object OtherIncome : ScreenshotRemark
    data class Custom(val name: String) : ScreenshotRemark
}

data class LedgerEntry(val id: Long, val userId: Long, val date: String, val kind: EntryKind,
    val amount: Long, val currency: MoneyUnit, val remark: String, val isOtherIncome: Boolean)

@Entity(tableName = "ledger_entries", indices = [Index(value = ["userId", "requestId"], unique = true), Index(value = ["userId", "date"])],
    foreignKeys = [ForeignKey(entity = UserIdentity::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE)])
data class LedgerEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val userId: Long,
    val requestId: String, val date: String, val kind: String, val amount: Long, val currency: String, val remark: String, val isOtherIncome: Boolean = false) {
    fun toEntry() = LedgerEntry(id, userId, date, EntryKind.valueOf(kind), amount, MoneyUnit.valueOf(currency), remark, isOtherIncome)
}

@Entity(tableName = "custom_remarks", indices = [Index(value = ["userId", "kind", "name"], unique = true)],
    foreignKeys = [ForeignKey(entity = UserIdentity::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE)])
data class CustomRemarkEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val userId: Long, val kind: String, val name: String)

@Entity(tableName = "screenshot_receipts", primaryKeys = ["userId", "requestId"],
    foreignKeys = [ForeignKey(entity = UserIdentity::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE)])
data class ScreenshotReceipt(val userId: Long, val requestId: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_entries WHERE userId=:userId AND date BETWEEN :start AND :end ORDER BY date DESC, id DESC")
    suspend fun entries(userId: Long, start: String, end: String): List<LedgerEntity>
    @Query("SELECT * FROM ledger_entries WHERE userId=:userId ORDER BY id")
    suspend fun allEntries(userId: Long): List<LedgerEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: LedgerEntity): Long
    @Query("SELECT * FROM custom_remarks WHERE userId=:userId ORDER BY id")
    suspend fun allRemarks(userId: Long): List<CustomRemarkEntity>
    @Query("SELECT name FROM custom_remarks WHERE userId=:userId AND kind=:kind ORDER BY id")
    suspend fun remarks(userId: Long, kind: String): List<String>
    @Query("SELECT COUNT(*) FROM custom_remarks WHERE userId=:userId AND kind=:kind AND name=:name")
    suspend fun hasRemark(userId: Long, kind: String, name: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemark(remark: CustomRemarkEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: ScreenshotReceipt): Long
}
