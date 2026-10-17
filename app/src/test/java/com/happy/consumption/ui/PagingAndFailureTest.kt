package com.happy.consumption.ui

import com.happy.consumption.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.io.IOException

class PagingAndFailureTest {
    private class Fake : RecordsRepository {
        override val session=UserSession(1,"A",1)
        override val dates=DatePolicy(Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"),ZoneOffset.UTC))
        override val changes=MutableSharedFlow<Unit>(extraBufferCapacity=8)
        val notes=(0 until 405).map { i -> ConsumptionItem(i.toLong()+1,1,LocalDate.of(2025,1,1).plusDays(i.toLong()).toString(),0,0,0,0,false,0) }.reversed().toMutableList()
        var fail=false;var writes=0;var calendarFails=false
        override suspend fun generatePastThirtyDays(): Int = 30
        override suspend fun ensureToday():Boolean { if(fail) throw IOException("read failed");return false }
        override suspend fun recordDates(start: String, end: String): Set<String> {
            if (calendarFails) throw IOException("synthetic calendar failure")
            return notes.map { it.date }.filter { it >= start && it <= end }.toSet()
        }
        override suspend fun positionOf(date: String): Int? = notes.indexOfFirst { it.date == date }.takeIf { it >= 0 }
        override suspend fun snapshot(pages:Int):RecordSnapshot { if(fail)throw IOException("read failed");return RecordSnapshot(notes.take(pages*200),notes.size) }
        override suspend fun add(date: String, amounts: Amounts, currencies: EntryCurrencies?){writes++}
        override suspend fun modify(permit:ModificationPermit){permit.consume();writes++}
        override suspend fun delete(permit:DeletionPermit){permit.consume();notes.removeIf{it.id==permit.item.id};writes++}
    }
    @Test fun pagesAndDeletionRefreshNoDuplicates() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope)
            assertEquals(200,vm.state.value.records.size);assertEquals(405,vm.state.value.count)
            vm.loadMore();assertEquals(400,vm.state.value.records.size)
            repo.notes.removeAt(0);repo.changes.tryEmit(Unit)
            assertEquals(repo.notes.take(400),vm.state.value.records)
            vm.loadMore();assertEquals(repo.notes,vm.state.value.records)
            assertEquals(404,vm.state.value.records.map{it.id}.toSet().size)
        } finally { scope.cancel() }
    }
    @Test fun failedReadBlocksWritesAndRetryPreservesData() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope);val original=vm.state.value.records
            repo.fail=true;vm.refreshToday();assertTrue(vm.state.value.error)
            assertEquals(original,vm.state.value.records)
            vm.add("2024-01-01","1","2","3","4");assertEquals(0,repo.writes)
            repo.fail=false;vm.retry();assertFalse(vm.state.value.error);assertEquals(original,vm.state.value.records)
        } finally { scope.cancel() }
    }
    @Test fun viewModelCancelsBothConfirmationSteps() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope);val item=vm.state.value.records.first()
            vm.requestModify(item);vm.cancel();assertEquals(0,repo.writes)
            vm.requestModify(item);vm.review("1","2","3","4");vm.cancel();assertEquals(0,repo.writes)
            vm.requestModify(item);vm.review("1","2","3","4");vm.confirmModify();assertEquals(1,repo.writes)
            vm.requestDelete(item);vm.cancel();assertEquals(1,repo.writes)
            vm.requestDelete(item);vm.confirmDelete();assertEquals(2,repo.writes);assertEquals(404,vm.state.value.count)
        } finally { scope.cancel() }
    }

    @Test fun calendarFindsUnloadedDateWithoutWritingAndMissingDateDoesNothing() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope)
            vm.showCalendar("2025-01-01","2025-01-31")
            assertEquals(31,vm.calendar.value.available.size)
            val old=repo.notes.last();vm.jumpToDate(old.date)
            assertEquals(405,vm.state.value.records.size);assertEquals(old.id,vm.dateJump.value!!.id)
            assertEquals(old.date,vm.selectedDate.value);assertEquals(0,repo.writes)
            vm.dateJump.value=null;vm.jumpToDate("2020-01-01")
            assertNull(vm.dateJump.value);assertEquals(old.date,vm.selectedDate.value);assertEquals(0,repo.writes)
        } finally { scope.cancel() }
    }
    @Test fun calendarJumpWaitsForDraftDecisionAndCanBeCancelled() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope);val old=repo.notes.last()
            vm.editAmount(repo.notes.first(),Meal.BREAKFAST);vm.pressKey("9");vm.jumpToDate(old.date)
            assertTrue(vm.leavePrompt.value);assertNull(vm.dateJump.value)
            vm.keepEditing();assertNull(vm.dateJump.value);assertNotNull(vm.inline.value)
            vm.jumpToDate(old.date);vm.discardInline()
            assertEquals(old.id,vm.dateJump.value!!.id);assertEquals(0,repo.writes)
        } finally { scope.cancel() }
    }

    @Test fun calendarFailureShowsErrorAndRetryDoesNotMarkRecordsUnreadable() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope)
            repo.calendarFails=true;vm.showCalendar("2025-01-01","2025-01-31")
            assertTrue(vm.calendar.value.error);assertFalse(vm.calendar.value.loading);assertFalse(vm.state.value.error)
            repo.calendarFails=false;vm.refreshCalendar()
            assertFalse(vm.calendar.value.error);assertEquals(31,vm.calendar.value.available.size);assertEquals(0,repo.writes)
        } finally { scope.cancel() }
    }
    @Test fun failedPageLoadDoesNotSelectOrJumpToStaleRecord() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake();val vm=AllRecordsViewModel(repo,scope)
            val selected=vm.selectedDate.value;repo.fail=true;vm.jumpToDate(repo.notes.first().date)
            assertTrue(vm.state.value.error);assertNull(vm.dateJump.value);assertEquals(selected,vm.selectedDate.value)
            assertFalse(vm.jumping.value);assertEquals(0,repo.writes)
        } finally { scope.cancel() }
    }
}
