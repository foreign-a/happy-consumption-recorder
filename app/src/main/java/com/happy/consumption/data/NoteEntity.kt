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
) {
    fun toItem() = ConsumptionItem(id, userId, date, breakfast, lunch, dinner, other, isManual, updatedAt)
}
