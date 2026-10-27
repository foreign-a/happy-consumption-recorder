package com.happy.consumption.ui

import com.happy.consumption.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.io.IOException

class Version14Test {
    private class Fake : RecordsRepository {
        override val session = UserSession(1,"fixture",1)
        override val dates = DatePolicy(Clock.fixed(Instant.parse("2026-10-27T04:00:00Z"),ZoneId.of("Asia/Shanghai")))
        override val changes = emptyFlow<Unit>()
        val rows = mutableListOf(item(1,"2026-10-27"),item(2,"2026-10-26"),item(3,"2026-09-30"))
        var creates=0; var deletes=0; var fail=false
        override suspend fun snapshot(pages: Int)=RecordSnapshot(rows.toList(),rows.size)
        override suspend fun recordDates(start: String,end: String)=rows.filter { it.date in start..end }.map { it.date }.toSet()
        override suspend fun positionOf(date: String)=rows.indexOfFirst { it.date==date }.takeIf { it>=0 }
        override suspend fun ensureToday()=false
        override suspend fun ensurePastDate(date: String): Boolean {
            if (dates.parseManual(date)>=dates.today()) throw UserOperationException(UserError.INVALID_DATE)
            if (rows.any { it.date==date }) return false
            rows.add(item(100+creates.toLong(),date));creates++;return true
        }
        override suspend fun deleteBatch(permit: BatchDeletionPermit): Int {
            if (fail) throw IOException("synthetic write failure")
            permit.validate(dates);permit.consume()
            rows.removeAll { row -> permit.items.any { it.id==row.id } };deletes++;return permit.items.size
        }
        override suspend fun generatePastThirtyDays()=0
        override suspend fun add(date: String,amounts: Amounts,currencies: EntryCurrencies?)=Unit
        override suspend fun modify(permit: ModificationPermit)=Unit
        override suspend fun delete(permit: DeletionPermit)=Unit
        companion object { fun item(id: Long,date: String)=ConsumptionItem(id,1,date,0,0,0,0,false,0) }
    }
    @Test fun calendarCreatesOneZeroRecordAndRepeatedSelectionIsReadOnly() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope)
            vm.openCalendarDate("2026-10-25");assertEquals(1,repo.creates)
            assertEquals("2026-10-25",vm.selectedRecord.value!!.date)
            assertEquals(0L,vm.selectedRecord.value!!.breakfast)
            vm.openCalendarDate("2026-10-25");assertEquals(1,repo.creates)
            vm.openCalendarDate("2026-10-28");assertEquals(1,repo.creates)
            assertEquals("2026-10-25",vm.selectedDate.value)
        } finally { scope.cancel() }
    }
    @Test fun dirtyDraftMustBeResolvedBeforeCreatingPastDate() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope)
            vm.editAmount(repo.rows.first(),Meal.OTHER);vm.pressKey("9")
            vm.openCalendarDate("2026-10-25");assertTrue(vm.leavePrompt.value);assertEquals(0,repo.creates)
            vm.keepEditing();assertEquals(0,repo.creates)
            vm.openCalendarDate("2026-10-25");vm.discardInline();assertEquals(1,repo.creates)
        } finally { scope.cancel() }
    }
    @Test fun selectionSurvivesMonthChangesAndDeletesOnlyAfterConfirmation() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope);vm.openBatchDelete()
            assertTrue(vm.batchDelete.value!!.records.none { it.date=="2026-10-27" })
            vm.toggleDeleteMonth();assertEquals(1,vm.batchDelete.value!!.selected.size)
            vm.loadDeleteMonth(YearMonth.of(2026,9));vm.toggleDeleteMonth()
            assertEquals(2,vm.batchDelete.value!!.selected.size)
            vm.confirmBatchDelete();assertEquals(0,repo.deletes)
            vm.reviewBatchDelete();assertEquals(0,repo.deletes)
            vm.confirmBatchDelete();assertEquals(1,repo.deletes)
            assertEquals(listOf("2026-10-27"),repo.rows.map { it.date });assertNull(vm.batchDelete.value)
            vm.confirmBatchDelete();assertEquals(1,repo.deletes)
        } finally { scope.cancel() }
    }
    @Test fun cancelAndFailurePreserveRecordsAndSelectionForRetry() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope)
            vm.openBatchDelete();vm.toggleDeleteMonth();vm.closeBatchDelete();assertEquals(3,repo.rows.size)
            vm.openBatchDelete();assertTrue(vm.batchDelete.value!!.selected.isEmpty())
            vm.toggleDeleteMonth();vm.reviewBatchDelete();repo.fail=true;vm.confirmBatchDelete()
            assertEquals(3,repo.rows.size);assertEquals(1,vm.batchDelete.value!!.selected.size)
            assertNotNull(vm.message.value);assertFalse(vm.busy.value)
            repo.fail=false;vm.confirmBatchDelete();assertEquals(2,repo.rows.size)
        } finally { scope.cancel() }
    }
    @Test fun permitsRejectTodayForeignUserDuplicatesAndReuse() {
        val repo=Fake()
        fun rejected(items: List<ConsumptionItem>) {
            try { BatchDeletionPermit(repo.session,items).validate(repo.dates);fail() }
            catch (_:UserOperationException) { } catch (_:AccessDeniedException) { }
        }
        rejected(emptyList());rejected(listOf(repo.rows.first()));rejected(listOf(repo.rows[1],repo.rows[1]))
        rejected(listOf(repo.rows[1].copy(userId=2)))
        val permit=BatchDeletionPermit(repo.session,listOf(repo.rows[1]));permit.validate(repo.dates);permit.consume()
        try { permit.consume();fail() } catch (_:UserOperationException) { }
    }
}
