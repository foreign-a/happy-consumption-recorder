package com.happy.consumption.data

// 移植自 QA ModificationConfirmationTest.java；修订规格要求手动/自动均两步确认。
import org.junit.Assert.*
import org.junit.Test

class ModificationConfirmationTest {
    private val session = UserSession(1, "A", 1)
    private fun item(manual: Boolean) = ConsumptionItem(7, 1, "2024-01-01", 10, 20, 30, 40, manual, 0)
    @Test fun firstCancelCannotEditOrConfirm() {
        val c = RecordConfirmation(session); c.requestModify(item(false)); c.cancel()
        assertEquals(ConfirmationState.Idle, c.state)
        try { c.acceptFirst(); fail() } catch (_: UserOperationException) { }
        try { c.confirmModify(); fail() } catch (_: UserOperationException) { }
    }
    @Test fun finalCancelCannotCommit() {
        val c = RecordConfirmation(session); c.requestModify(item(false)); c.acceptFirst(); c.review(Amounts(1,2,3,4)); c.cancel()
        try { c.confirmModify(); fail() } catch (_: UserOperationException) { }
    }
    @Test fun bothKindsRequireTwoStepsAndSingleUsePermit() {
        for (manual in listOf(false, true)) {
            val c = RecordConfirmation(session); c.requestModify(item(manual))
            try { c.review(Amounts(1,2,3,4)); fail() } catch (_: UserOperationException) { }
            try { c.confirmModify(); fail() } catch (_: UserOperationException) { }
            c.acceptFirst(); c.review(Amounts(1,2,3,4)); val permit = c.confirmModify()
            assertEquals(item(manual), permit.item); assertEquals(session, permit.session)
            permit.consume(); try { permit.consume(); fail() } catch (_: UserOperationException) { }
        }
    }
    @Test fun deleteCancelAndConfirmation() {
        val c=RecordConfirmation(session); c.requestDelete(item(true)); c.cancel()
        try { c.confirmDelete(); fail() } catch (_: UserOperationException) { }
        c.requestDelete(item(true)); val permit=c.confirmDelete(); permit.consume()
        try { permit.consume(); fail() } catch (_: UserOperationException) { }
    }
}
