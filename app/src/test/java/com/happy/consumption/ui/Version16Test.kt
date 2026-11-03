package com.happy.consumption.ui

import com.happy.consumption.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class Version16Test {
    private val original = ConsumptionItem(1, 1, "2026-11-03", 1000, 2000, 3000, 4000, true, 1)

    private class Fake(var item: ConsumptionItem) : RecordsRepository {
        override val session = UserSession(1, "fixture", 1)
        override val dates = DatePolicy(Clock.fixed(Instant.parse("2026-11-03T04:00:00Z"), ZoneId.of("Asia/Shanghai")))
        override val changes = emptyFlow<Unit>()
        var writes = 0
        var fail = false
        var writeGate: CompletableDeferred<Unit>? = null
        override suspend fun snapshot(pages: Int) = RecordSnapshot(listOf(item), 1)
        override suspend fun recordDates(start: String, end: String) = setOf(item.date).filter { it in start..end }.toSet()
        override suspend fun positionOf(date: String) = 0
        override suspend fun ensureToday() = false
        override suspend fun generatePastThirtyDays() = 0
        override suspend fun add(date: String, amounts: Amounts, currencies: EntryCurrencies?) = Unit
        override suspend fun delete(permit: DeletionPermit) = Unit
        override suspend fun modify(permit: ModificationPermit) {
            writeGate?.await()
            if (fail) throw IOException("synthetic write failure")
            if (permit.checkSnapshot && permit.item != item) throw UserOperationException(UserError.RECORD_CHANGED)
            permit.consume()
            val currencies = permit.currencies ?: item.currencies
            item = item.copy(breakfast = permit.amounts.breakfast, lunch = permit.amounts.lunch,
                dinner = permit.amounts.dinner, other = permit.amounts.other,
                breakfastCurrency = currencies.breakfast, lunchCurrency = currencies.lunch,
                dinnerCurrency = currencies.dinner, otherCurrency = currencies.other, updatedAt = item.updatedAt + 1)
            writes++
        }
    }

    private fun withModel(preferences: EntryPreferences = EntryPreferences(confirmation = ConfirmationPlan.QUICK),
        action: (Fake, AllRecordsViewModel) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { val repository = Fake(original); action(repository, AllRecordsViewModel(repository, scope, preferences)) }
        finally { scope.cancel() }
    }

    @Test fun quickModeCommitsLunchWhenAnyOtherAmountIsSelected() {
        for (target in Meal.entries.filter { it != Meal.LUNCH }) withModel { repo, vm ->
            vm.editAmount(repo.item, Meal.LUNCH)
            vm.pressKey("8")
            val oldSnapshot = repo.item
            vm.editAmount(oldSnapshot, target)
            assertEquals(1, repo.writes)
            assertEquals(800L, repo.item.lunch)
            assertEquals(original.breakfast, repo.item.breakfast)
            assertEquals(original.dinner, repo.item.dinner)
            assertEquals(original.other, repo.item.other)
            assertEquals(target, vm.inline.value!!.selected)
            assertEquals(repo.item, vm.inline.value!!.item)
            assertEquals(repo.item.id to target, vm.focusedAmount.value)
            assertTrue(vm.inline.value!!.inputs.all { it.isEmpty() })
            assertFalse(vm.leavePrompt.value)
        }
    }

    @Test fun sameAmountTapsPreservePendingInputWithoutSaving() = withModel { repo, vm ->
        vm.editAmount(repo.item, Meal.LUNCH)
        vm.pressKey("8")
        vm.editAmount(repo.item, Meal.LUNCH)
        vm.editAmount(repo.item, Meal.LUNCH)
        vm.doubleTapAmount(repo.item, Meal.LUNCH, 0f, 0f)
        assertEquals(0, repo.writes)
        assertEquals("8", vm.inline.value!!.inputs[Meal.LUNCH.ordinal])
        assertEquals(Meal.LUNCH, vm.inline.value!!.selected)
        vm.editAmount(repo.item, Meal.DINNER)
        assertEquals(1, repo.writes)
        vm.editAmount(repo.item, Meal.OTHER)
        assertEquals(1, repo.writes)
    }

    @Test fun cautiousModeKeepsMultipleAmountsUntilExplicitConfirmation() =
        withModel(EntryPreferences(confirmation = ConfirmationPlan.CAREFUL)) { repo, vm ->
            vm.editAmount(repo.item, Meal.LUNCH)
            vm.pressKey("8")
            vm.editAmount(repo.item, Meal.DINNER)
            vm.pressKey("9")
            assertEquals(0, repo.writes)
            assertFalse(vm.leavePrompt.value)
            assertEquals("8", vm.inline.value!!.inputs[Meal.LUNCH.ordinal])
            assertEquals("9", vm.inline.value!!.inputs[Meal.DINNER.ordinal])
            vm.saveInline()
            vm.saveInline()
            assertEquals(1, repo.writes)
            assertEquals(800L, repo.item.lunch)
            assertEquals(900L, repo.item.dinner)
        }

    @Test fun failedAmountSwitchKeepsOriginalFocusAndRetriesExactlyOnce() =
        withModel(EntryPreferences(EntryMode.ADJUST, ConfirmationPlan.QUICK)) { repo, vm ->
            vm.editAmount(repo.item, Meal.LUNCH)
            vm.pressKey("2")
            val draft = vm.inline.value
            repo.fail = true
            vm.editAmount(repo.item, Meal.DINNER)
            assertEquals(0, repo.writes)
            assertEquals(draft, vm.inline.value)
            assertEquals(repo.item.id to Meal.LUNCH, vm.focusedAmount.value)
            assertNotNull(vm.message.value)
            assertFalse(vm.busy.value)
            repo.fail = false
            vm.editAmount(repo.item, Meal.DINNER)
            assertEquals(1, repo.writes)
            assertEquals(2200L, repo.item.lunch)
            assertEquals(Meal.DINNER, vm.inline.value!!.selected)
            vm.editAmount(repo.item, Meal.OTHER)
            vm.leave()
            assertEquals(1, repo.writes)
        }

    @Test fun invalidAmountCannotMoveFocusOrLoseTheDraft() = withModel { repo, vm ->
        vm.editAmount(repo.item, Meal.LUNCH)
        vm.pressKey("8")
        vm.pressKey(".")
        vm.editAmount(repo.item, Meal.DINNER)
        assertEquals(0, repo.writes)
        assertEquals(Meal.LUNCH, vm.inline.value!!.selected)
        assertEquals(repo.item.id to Meal.LUNCH, vm.focusedAmount.value)
        assertEquals("8.", vm.inline.value!!.inputs[Meal.LUNCH.ordinal])
        assertNotNull(vm.message.value)
        vm.pressKey("5")
        vm.editAmount(repo.item, Meal.DINNER)
        assertEquals(1, repo.writes)
        assertEquals(850L, repo.item.lunch)
        assertEquals(Meal.DINNER, vm.inline.value!!.selected)
    }

    @Test fun repeatedRelativeSwitchesUseSavedSnapshotAndNeverApplyDeltaTwice() {
        for (subtract in listOf(false, true)) withModel(EntryPreferences(EntryMode.ADJUST, ConfirmationPlan.QUICK)) { repo, vm ->
            if (subtract) vm.doubleTapAmount(repo.item, Meal.LUNCH, 0f, 0f)
            vm.editAmount(repo.item, Meal.LUNCH)
            vm.pressKey("2")
            val staleSnapshot = repo.item
            vm.editAmount(staleSnapshot, Meal.DINNER)
            vm.pressKey("3")
            vm.editAmount(staleSnapshot, Meal.LUNCH)
            vm.pressKey("1")
            vm.editAmount(staleSnapshot, Meal.OTHER)
            assertEquals(3, repo.writes)
            assertEquals(if (subtract) 1700L else 2300L, repo.item.lunch)
            assertEquals(if (subtract) 2700L else 3300L, repo.item.dinner)
            assertEquals(repo.item, vm.inline.value!!.item)
            vm.leave()
            assertEquals(3, repo.writes)
        }
    }

    @Test fun currencyOnlyChangeSavesOnSwitchAndSurvivesNextSave() = withModel { repo, vm ->
        vm.editAmount(repo.item, Meal.LUNCH)
        vm.changeEntryCurrency(MoneyUnit.DOLLAR)
        val staleSnapshot = repo.item
        vm.editAmount(staleSnapshot, Meal.DINNER)
        assertEquals(1, repo.writes)
        assertEquals(MoneyUnit.DOLLAR, repo.item.lunchCurrency)
        vm.pressKey("9")
        vm.editAmount(staleSnapshot, Meal.OTHER)
        assertEquals(2, repo.writes)
        assertEquals(MoneyUnit.DOLLAR, repo.item.lunchCurrency)
        assertEquals(900L, repo.item.dinner)
    }

    @Test fun switchesDuringPendingSaveCannotDuplicateWriteOrChangeTheTarget() = withModel { repo, vm ->
        val gate = CompletableDeferred<Unit>()
        repo.writeGate = gate
        vm.editAmount(repo.item, Meal.LUNCH)
        vm.pressKey("8")
        vm.editAmount(repo.item, Meal.DINNER)
        assertTrue(vm.busy.value)
        vm.editAmount(repo.item, Meal.OTHER)
        vm.pressKey("9")
        vm.leave()
        vm.saveInline()
        assertEquals(0, repo.writes)
        assertEquals(Meal.LUNCH, vm.inline.value!!.selected)
        gate.complete(Unit)
        assertEquals(1, repo.writes)
        assertEquals(800L, repo.item.lunch)
        assertEquals(Meal.DINNER, vm.inline.value!!.selected)
        assertTrue(vm.inline.value!!.inputs.all { it.isEmpty() })
        assertFalse(vm.busy.value)
    }

    @Test fun staleRelativeSnapshotRejectsSwitchWithoutRebasingTheDelta() =
        withModel(EntryPreferences(EntryMode.ADJUST, ConfirmationPlan.QUICK)) { repo, vm ->
            vm.editAmount(repo.item, Meal.LUNCH)
            vm.pressKey("2")
            val draft = vm.inline.value
            repo.item = repo.item.copy(lunch = 2500)
            vm.editAmount(repo.item, Meal.DINNER)
            assertEquals(0, repo.writes)
            assertEquals(2500L, repo.item.lunch)
            assertEquals(draft, vm.inline.value)
            assertEquals(repo.item.id to Meal.LUNCH, vm.focusedAmount.value)
            assertNotNull(vm.message.value)
        }
}
