package com.happy.consumption.data

import androidx.room.*

// 仅用来约束外键；用户名和凭据仍仅由 DataStore 管理。
@Entity(tableName = "user_identities")
data class UserIdentity(@PrimaryKey val id: Long)

@Dao
interface UserIdentityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(user: UserIdentity)
    @Query("DELETE FROM user_identities WHERE id = :userId")
    suspend fun deleteByUser(userId: Long)
    @Query("SELECT COUNT(*) FROM user_identities WHERE id = :userId")
    suspend fun countByUser(userId: Long): Int
}
