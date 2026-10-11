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
        var fail=false;var writes=0
        override suspend fun generatePastThirtyDays(): Int = 30
        override suspend fun ensureToday():Boolean { if(fail) throw IOException("read failed");return false }
        override suspend fun snapshot(pages:Int):RecordSnapshot { if(fail)throw IOException("read failed");return RecordSnapshot(notes.take(pages*200),notes.size) }
        override suspend fun add(date:String,amounts:Amounts){writes++}
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
}
