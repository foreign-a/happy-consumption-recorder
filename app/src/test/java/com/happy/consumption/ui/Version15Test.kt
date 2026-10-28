package com.happy.consumption.ui

import com.happy.consumption.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.io.IOException

class Version15Test {
    private val original = ConsumptionItem(1,1,"2026-10-28",1000,200,300,400,true,1)
    private class Fake(var item: ConsumptionItem) : RecordsRepository {
        override val session=UserSession(1,"A",1)
        override val dates=DatePolicy(Clock.fixed(Instant.parse("2026-10-28T04:00:00Z"),ZoneId.of("Asia/Shanghai")))
        override val changes=emptyFlow<Unit>()
        var writes=0;var fail=false
        override suspend fun snapshot(pages: Int)=RecordSnapshot(listOf(item),1)
        override suspend fun recordDates(start: String,end: String)=setOf(item.date).filter { it in start..end }.toSet()
        override suspend fun positionOf(date: String)=0
        override suspend fun ensureToday()=false
        override suspend fun generatePastThirtyDays()=0
        override suspend fun add(date: String,amounts: Amounts,currencies: EntryCurrencies?)=Unit
        override suspend fun delete(permit: DeletionPermit)=Unit
        override suspend fun modify(permit: ModificationPermit) {
            if(fail)throw IOException("synthetic failure")
            if(permit.checkSnapshot && permit.item!=item)throw UserOperationException(UserError.RECORD_CHANGED)
            permit.consume();writes++
            item=item.copy(breakfast=permit.amounts.breakfast,lunch=permit.amounts.lunch,dinner=permit.amounts.dinner,
                other=permit.amounts.other,updatedAt=item.updatedAt+1)
        }
    }
    private fun amount(draft: InlineDraft,text: String)=text.fold(draft) { d,key -> d.press(key.toString()) }
    @Test fun replacementsAndRelativeAmountsUseMinorUnits() {
        assertEquals(123L,amount(InlineDraft(original,Meal.BREAKFAST),"1.23").amounts().breakfast)
        assertEquals(1123L,amount(InlineDraft(original,Meal.BREAKFAST,operation=DraftOperation.ADD),"1.23").amounts().breakfast)
        assertEquals(877L,amount(InlineDraft(original,Meal.BREAKFAST,operation=DraftOperation.SUBTRACT),"1.23").amounts().breakfast)
        assertFalse(amount(InlineDraft(original,Meal.BREAKFAST,operation=DraftOperation.ADD),"0").changed)
        assertEquals(0L,amount(InlineDraft(original,Meal.BREAKFAST,operation=DraftOperation.SUBTRACT),"10").amounts().breakfast)
    }
    @Test fun underflowOverflowAndIncompleteInputsNeverBecomeWrites() {
        for(draft in listOf(amount(InlineDraft(original,Meal.BREAKFAST,operation=DraftOperation.SUBTRACT),"10.01"),
            amount(InlineDraft(original.copy(breakfast=999999),Meal.BREAKFAST,operation=DraftOperation.ADD),"0.01"),
            amount(InlineDraft(original,Meal.BREAKFAST,operation=DraftOperation.ADD),"0."))) {
            try { draft.amounts();fail() } catch(_:UserOperationException) { }
        }
    }
    @Test fun quickRepeatedConfirmationAddsExactlyOnceAndKeepsTheSelectedMeal() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake(original);val vm=AllRecordsViewModel(repo,scope,EntryPreferences(EntryMode.ADJUST,ConfirmationPlan.QUICK))
            vm.editAmount(repo.item,Meal.BREAKFAST);vm.pressKey("2");vm.leave();vm.leave()
            assertEquals(1200L,repo.item.breakfast);assertEquals(1,repo.writes)
            assertEquals(Meal.BREAKFAST,vm.focusedAmount.value!!.second)
            vm.editAmount(vm.selectedRecord.value!!,Meal.BREAKFAST);assertTrue(vm.inline.value!!.inputs.all { it.isEmpty() })
            vm.pressKey("3");vm.leave();assertEquals(1500L,repo.item.breakfast);assertEquals(2,repo.writes)
        } finally { scope.cancel() }
    }
    @Test fun cautiousSaveDiscardAndKeepApplyToBlankAndNavigationActions() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake(original);val vm=AllRecordsViewModel(repo,scope);var navigated=false
            assertEquals(ConfirmationPlan.CAREFUL,vm.entryPreferences.value.confirmation)
            vm.editAmount(repo.item,Meal.OTHER);vm.pressKey("8");vm.leave { navigated=true }
            assertTrue(vm.leavePrompt.value);assertEquals(0,repo.writes);vm.keepEditing();assertFalse(navigated)
            vm.leave { navigated=true };vm.discardInline();assertTrue(navigated);assertEquals(0,repo.writes)
            vm.editAmount(repo.item,Meal.OTHER);vm.pressKey("8");vm.leave();vm.saveInline()
            assertEquals(800L,repo.item.other);assertEquals(1,repo.writes)
        } finally { scope.cancel() }
    }
    @Test fun doubleTapResolvesPendingAddThenUsesFreshBaseForSubtractAndSwitchesGlobally() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake(original);val vm=AllRecordsViewModel(repo,scope,EntryPreferences(EntryMode.ADJUST,ConfirmationPlan.QUICK))
            assertEquals(DraftOperation.ADD,vm.operation.value)
            vm.editAmount(repo.item,Meal.BREAKFAST);vm.pressKey("2")
            vm.doubleTapAmount(repo.item,Meal.BREAKFAST,12f,34f)
            assertEquals(1200L,repo.item.breakfast);assertEquals(DraftOperation.SUBTRACT,vm.operation.value)
            assertEquals(1200L,vm.inline.value!!.item.breakfast);assertEquals(12f,vm.modeReveal.value.x,0f)
            vm.pressKey("1");vm.leave();assertEquals(1100L,repo.item.breakfast)
            vm.doubleTapAmount(repo.item,Meal.OTHER,44f,55f);assertEquals(DraftOperation.ADD,vm.operation.value)
            vm.editAmount(repo.item,Meal.LUNCH);assertEquals(DraftOperation.ADD,vm.inline.value!!.operation)
            vm.configureEntry(EntryPreferences());assertEquals(DraftOperation.REPLACE,vm.operation.value)
        } finally { scope.cancel() }
    }
    @Test fun failedQuickSaveDoesNotNavigateAndRetryAppliesDeltaOnce() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake(original);val vm=AllRecordsViewModel(repo,scope,EntryPreferences(EntryMode.ADJUST,ConfirmationPlan.QUICK));var navigated=false
            vm.editAmount(repo.item,Meal.BREAKFAST);vm.pressKey("2");repo.fail=true;vm.leave { navigated=true }
            assertFalse(navigated);assertEquals(0,repo.writes);assertNotNull(vm.inline.value)
            repo.fail=false;vm.leave { navigated=true };assertTrue(navigated);assertEquals(1200L,repo.item.breakfast);assertEquals(1,repo.writes)
        } finally { scope.cancel() }
    }
    @Test fun staleRelativeSnapshotIsRejectedWithoutRebasingSilently() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repo=Fake(original);val vm=AllRecordsViewModel(repo,scope,EntryPreferences(EntryMode.ADJUST,ConfirmationPlan.QUICK))
            vm.editAmount(repo.item,Meal.BREAKFAST);vm.pressKey("2");repo.item=repo.item.copy(breakfast=1500)
            vm.leave();assertEquals(0,repo.writes);assertEquals(1500L,repo.item.breakfast);assertNotNull(vm.inline.value)
        } finally { scope.cancel() }
    }
}
