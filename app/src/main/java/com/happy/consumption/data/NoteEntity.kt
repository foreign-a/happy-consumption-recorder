package com.happy.consumption.data

import androidx.room.*

@Entity(tableName = "notes", indices = [Index(value = ["userId", "date"], unique = true)],
    foreignKeys = [ForeignKey(entity = UserIdentity::class, parentColumns = ["id"],
        childColumns = ["userId"], onDelete = ForeignKey.CASCADE)])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val date: String,
    val breakfast: Long,
    val lunch: Long,
    val dinner: Long,
    val other: Long,
    val isManual: Boolean,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val breakfastCurrency: String? = null,
    @ColumnInfo(defaultValue = "NULL") val lunchCurrency: String? = null,
    @ColumnInfo(defaultValue = "NULL") val dinnerCurrency: String? = null,
    @ColumnInfo(defaultValue = "NULL") val otherCurrency: String? = null,
) {
    fun toItem() = ConsumptionItem(id, userId, date, breakfast, lunch, dinner, other, isManual, updatedAt,
        MoneyUnit.stored(breakfastCurrency), MoneyUnit.stored(lunchCurrency), MoneyUnit.stored(dinnerCurrency), MoneyUnit.stored(otherCurrency))
    fun withCurrencies(c: EntryCurrencies) = copy(breakfastCurrency = c.breakfast.name, lunchCurrency = c.lunch.name, dinnerCurrency = c.dinner.name, otherCurrency = c.other.name)
}
