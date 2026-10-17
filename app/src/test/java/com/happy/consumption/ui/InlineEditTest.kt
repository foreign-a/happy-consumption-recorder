package com.happy.consumption.ui

import com.happy.consumption.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class InlineEditTest {
    private val original = ConsumptionItem(1,1,"2026-10-01",900,0,0,500,true,1)
    private class Fake(val item: ConsumptionItem) : RecordsRepository {
        override val session = UserSession(1,"fixture",1)
        override val dates = DatePolicy(Clock.systemUTC())
        override val changes = emptyFlow<Unit>()
        var writes = 0; var fail = false; var permit: ModificationPermit? = null
        override suspend fun recordDates(start: String, end: String) = snapshot(1).records.map { it.date }.filter { it >= start && it <= end }.toSet()
        override suspend fun positionOf(date: String): Int? = snapshot(1).records.indexOfFirst { it.date == date }.takeIf { it >= 0 }
        override suspend fun snapshot(pages: Int) = RecordSnapshot(listOf(item),1)
        override suspend fun ensureToday() = false
        override suspend fun generatePastThirtyDays() = 30
        override suspend fun add(date: String, amounts: Amounts, currencies: EntryCurrencies?) = Unit
        override suspend fun delete(permit: DeletionPermit) = Unit
        override suspend fun modify(permit: ModificationPermit) {
            if (fail) throw IOException("synthetic write failure")
            permit.consume(); writes++; this.permit = permit
        }
    }
    @Test fun perAmountUnitsAreGroupedWithoutConversion() {
        val mixed=original.copy(otherCurrency=MoneyUnit.DOLLAR)
        assertEquals("¥9.00 + $5.00",mixed.totals())
        assertEquals("¥14.00",original.totals())
    }
    @Test fun phoneKeysReplaceOneFieldAndPreserveOthers() {
        var draft=InlineDraft(original,Meal.BREAKFAST)
        assertFalse(draft.changed)
        draft=draft.press("1").press(".").press("2").press("backspace").press("3")
        assertEquals(Amounts(130,0,0,500),draft.amounts())
        assertTrue(draft.changed)
        assertEquals("1.3",draft.inputs.first())
    }
    @Test fun currencyOnlyChangeAndExplicitZeroAreChanges() {
        val draft=InlineDraft(original,Meal.OTHER).let { it.copy(currencies=it.currencies.replacing(Meal.OTHER,MoneyUnit.DOLLAR)) }
        assertTrue(draft.changed);assertEquals(Amounts(900,0,0,500),draft.amounts())
        assertEquals("$5.00",draft.preview(Meal.OTHER))
        assertEquals(Amounts(0,0,0,500),InlineDraft(original,Meal.BREAKFAST).press("0").amounts())
    }
    @Test fun invalidAndIncompleteInputsCannotCommit() {
        for (text in listOf("10000","0.001","0.")) {
            val draft=InlineDraft(original,Meal.BREAKFAST,inputs=listOf(text,"","",""))
            try { draft.amounts();fail() } catch (_:UserOperationException) { }
        }
    }
    @Test fun confirmWritesOnceAndOutsideTapRequiresDecision() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake(original);val vm=AllRecordsViewModel(repo,scope)
            vm.editAmount(original,Meal.OTHER);vm.pressKey("7");vm.changeEntryCurrency(MoneyUnit.DOLLAR)
            vm.leave();assertTrue(vm.leavePrompt.value);assertEquals(0,repo.writes)
            vm.keepEditing();assertNotNull(vm.inline.value)
            vm.saveInline();vm.saveInline();assertEquals(1,repo.writes)
            assertEquals(700L,repo.permit!!.amounts.other);assertEquals(MoneyUnit.DOLLAR,repo.permit!!.currencies!!.other)
            assertNull(vm.inline.value)
        } finally { scope.cancel() }
    }
    @Test fun discardAndUnchangedLeaveNeverWrite() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake(original);val vm=AllRecordsViewModel(repo,scope)
            vm.editAmount(original,Meal.OTHER);vm.leave();assertFalse(vm.leavePrompt.value);assertNull(vm.inline.value)
            vm.editAmount(original,Meal.OTHER);vm.pressKey("7");vm.leave();vm.discardInline()
            assertEquals(0,repo.writes);assertNull(vm.inline.value)
        } finally { scope.cancel() }
    }
    @Test fun failureKeepsDraftForRetryAndDoesNotNavigate() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake(original);val vm=AllRecordsViewModel(repo,scope);var navigated=false
            vm.editAmount(original,Meal.BREAKFAST);vm.pressKey("2");vm.leave { navigated=true }
            repo.fail=true;vm.saveInline();assertNotNull(vm.inline.value);assertFalse(navigated);assertEquals(0,repo.writes)
            repo.fail=false;vm.saveInline();assertEquals(1,repo.writes);assertNull(vm.inline.value)
        } finally { scope.cancel() }
    }
    @Test fun switchingRecordsSavesBeforeOpeningTheRequestedAmount() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake(original);val vm=AllRecordsViewModel(repo,scope)
            val target=original.copy(id=2,date="2026-09-30")
            vm.editAmount(original,Meal.BREAKFAST);vm.pressKey("2")
            vm.editAmount(target,Meal.OTHER);assertTrue(vm.leavePrompt.value);assertEquals(original.id,vm.inline.value!!.item.id)
            vm.saveInline();assertEquals(1,repo.writes)
            assertEquals(target.id,vm.inline.value!!.item.id);assertEquals(Meal.OTHER,vm.inline.value!!.selected)
        } finally { scope.cancel() }
    }
}
