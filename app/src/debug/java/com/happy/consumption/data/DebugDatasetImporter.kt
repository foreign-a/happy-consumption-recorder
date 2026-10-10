package com.happy.consumption.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.InputStream
import java.math.BigDecimal
import java.time.Clock

data class DatasetImportResult(val imported: Int, val userMapping: Map<Long, Long>, val usernames: Map<Long, String>, val elapsedMillis: Long)

// 仅 debug 源集：为数据集创建独立合成账号，不复用或覆盖现有账号。
class DebugDatasetImporter(private val manager: UserManager, private val database: AppDatabase, private val clock: Clock) {
    suspend fun import(input: InputStream, prefix: String, syntheticPassword: String): DatasetImportResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val rows = JSONArray(input.bufferedReader().use { it.readText() })
        val originalIds = (0 until rows.length()).map { rows.getJSONObject(it).getLong("userId") }.distinct().sorted()
        val mapping = mutableMapOf<Long, Long>(); val names = mutableMapOf<Long, String>()
        for (id in originalIds) {
            val name = "$prefix-$id"
            manager.register(name, syntheticPassword)
            mapping[id] = checkNotNull(manager.findUserId(name)); names[id] = name
        }
        database.withTransaction {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                fun cents(key: String) = BigDecimal(row.getString(key)).movePointRight(2).longValueExact().also {
                    if (it !in 0L..999999L) throw UserOperationException(UserError.INVALID_AMOUNT)
                }
                val note = NoteEntity(userId = mapping.getValue(row.getLong("userId")), date = row.getString("date"),
                    breakfast = cents("breakfast"), lunch = cents("lunch"), dinner = cents("dinner"), other = cents("other"),
                    isManual = row.getBoolean("isManual"), updatedAt = clock.millis())
                check(database.notes().insertIfAbsent(note) != -1L) { "Dataset duplicate" }
            }
        }
        DatasetImportResult(rows.length(), mapping, names, (System.nanoTime() - started) / 1_000_000)
    }
}
