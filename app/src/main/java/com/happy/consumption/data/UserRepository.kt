package com.happy.consumption.data

import com.happy.consumption.util.LogHelper

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

data class RecordSnapshot(val records: List<ConsumptionItem>, val count: Int)
interface RecordsRepository {
    val session: UserSession
    val changes: Flow<Unit>
    val dates: DatePolicy
    suspend fun snapshot(pages: Int): RecordSnapshot
    suspend fun ensureToday(): Boolean
    suspend fun add(date: String, amounts: Amounts)
    suspend fun modify(permit: ModificationPermit)
    suspend fun delete(permit: DeletionPermit)
}

class UserRepository(private val manager: UserManager, private val database: AppDatabase,
    private val owner: UserSession, private val clock: Clock = Clock.systemDefaultZone()) : RecordsRepository {
    override val dates = DatePolicy(clock)
    override val session get() = owner
    override val changes = database.invalidationTracker.createFlow("notes").map { Unit }
    private val generator = AutoRecordGenerator(object : AutoRecordGateway {
        override suspend fun exists(userId: Long, date: String) = queryByDate(userId, date) != null
        override suspend fun insertZeroIfAbsent(note: NoteEntity): Long = manager.withCurrentUser(owner) { id ->
            if (note.userId != id) { LogHelper.info("Security", "Rejected stale or unauthorized record request"); throw AccessDeniedException() }
            database.notes().insertIfAbsent(note)
        }
    }, clock)
    override suspend fun ensureToday(): Boolean {
        val date = generator.today()
        val generated = generator.generate(owner.id, date)
        LogHelper.info("AutoRecord", "userId=${owner.id} date=$date result=${if (generated) "generated" else "skipped"} reason=${if (generated) "missing" else "existing_or_concurrent"}")
        return generated
    }
    suspend fun shouldGenerate(userId: Long, today: String) = generator.shouldGenerate(userId, today)
    suspend fun queryRecords(userId: Long, limit: Int = 200, offset: Int = 0): List<NoteEntity> = manager.withCurrentUser(owner) {
        if (it != userId) { LogHelper.info("Security", "Rejected stale or unauthorized record request"); throw AccessDeniedException() }
        require(limit in 1..200 && offset >= 0)
        database.notes().queryByUserPaged(it, limit, offset)
    }
    suspend fun queryByDate(userId: Long, date: String): NoteEntity? = manager.withCurrentUser(owner) {
        if (it != userId) { LogHelper.info("Security", "Rejected stale or unauthorized record request"); throw AccessDeniedException() }
        database.notes().queryByUserAndDate(it, date).also {
            if (date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) LogHelper.info("Query", "查询了${date}的数据 userId=$userId result=success")
        }
    }
    override suspend fun snapshot(pages: Int): RecordSnapshot = manager.withCurrentUser(owner) { id ->
        require(pages > 0)
        database.withTransaction {
            val count = database.notes().countByUserOnce(id)
            val all = buildList {
                for (page in 0 until pages) {
                    val rows = database.notes().queryByUserPaged(id, 200, page * 200)
                    addAll(rows.map { it.toItem() })
                    if (rows.size < 200) break
                }
            }
            LogHelper.info("Paging", "userId=$id pages=$pages loaded=${all.size} count=$count result=success")
            RecordSnapshot(all, count)
        }
    }
    suspend fun add(date: String, breakfast: Long, lunch: Long, dinner: Long, other: Long) = add(date, Amounts(breakfast, lunch, dinner, other))
    override suspend fun add(date: String, amounts: Amounts) = manager.withCurrentUser(owner) { id ->
        amounts.validate(); dates.parseManual(date)
        database.withTransaction {
            if (database.notes().queryByUserAndDate(id, date) != null) { LogHelper.info("Validation", "Duplicate date rejected userId=$id"); throw UserOperationException(UserError.DATE_EXISTS) }
            try {
                if (database.notes().insertIfAbsent(NoteEntity(userId = id, date = date, breakfast = amounts.breakfast,
                    lunch = amounts.lunch, dinner = amounts.dinner, other = amounts.other, isManual = true, updatedAt = clock.millis())) == -1L)
                    { LogHelper.info("Validation", "Duplicate date rejected userId=$id"); throw UserOperationException(UserError.DATE_EXISTS) }
            } catch (e: android.database.sqlite.SQLiteConstraintException) { LogHelper.error("UserRepository", "Operation failed; recovery preserved", e); throw UserOperationException(UserError.DATE_EXISTS) }
        }
        LogHelper.info("Record", "Add userId=$id date=$date result=success")
        Unit
    }
    override suspend fun modify(permit: ModificationPermit) = manager.withCurrentUser(owner) { id ->
        if (permit.item.userId != id || permit.session != owner) { LogHelper.info("Security", "Rejected stale or unauthorized record request"); throw AccessDeniedException() }
        permit.amounts.validate(); permit.consume()
        val a = permit.amounts
        if (database.notes().updateByIdAndUser(permit.item.id, id, a.breakfast, a.lunch, a.dinner, a.other, true, clock.millis()) != 1)
            throw UserOperationException(UserError.RECORD_MISSING)
        LogHelper.info("Record", "Modify userId=$id date=${permit.item.date} result=success")
        Unit
    }
    override suspend fun delete(permit: DeletionPermit) = manager.withCurrentUser(owner) { id ->
        if (permit.item.userId != id || permit.session != owner) { LogHelper.info("Security", "Rejected stale or unauthorized record request"); throw AccessDeniedException() }
        permit.consume()
        if (database.notes().deleteByIdAndUser(permit.item.id, id) != 1) throw UserOperationException(UserError.RECORD_MISSING)
        LogHelper.info("Record", "Delete userId=$id date=${permit.item.date} result=success")
        Unit
    }
}
