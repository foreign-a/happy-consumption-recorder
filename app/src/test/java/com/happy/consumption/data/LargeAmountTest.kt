package com.happy.consumption.data

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class LargeAmountTest {
    private val item = ConsumptionItem(1, 1, "2026-11-13", 999999, 0, 0, 0, true, 0)

    private fun draft(base: Long, operation: DraftOperation, input: String) =
        InlineDraft(item.copy(breakfast = base), Meal.BREAKFAST, operation = operation)
            .let { original -> input.fold(original) { current, key -> current.press(key.toString()) } }

    @Test fun replacingAndAddingCrossThePreviousLimit() {
        assertEquals(1000000L, draft(0, DraftOperation.REPLACE, "10000").amounts().breakfast)
        assertEquals(1000000L, draft(999999L, DraftOperation.ADD, "0.01").amounts().breakfast)
        assertEquals(Long.MAX_VALUE, draft(0, DraftOperation.REPLACE, "92233720368547758.07").amounts().breakfast)
        assertEquals(Long.MAX_VALUE, draft(Long.MAX_VALUE - 1, DraftOperation.ADD, "0.01").amounts().breakfast)
    }

    @Test fun adjustmentRejectsLongOverflowAndNegativeResults() {
        for (invalid in listOf(
            draft(Long.MAX_VALUE, DraftOperation.ADD, "0.01"),
            draft(Long.MAX_VALUE, DraftOperation.ADD, "92233720368547758.07"),
            draft(Long.MAX_VALUE - 1, DraftOperation.SUBTRACT, "92233720368547758.07")
        )) {
            try { invalid.amounts(); fail("invalid adjustment accepted") }
            catch (e: UserOperationException) { assertEquals(UserError.INVALID_ADJUSTMENT, e.reason) }
        }
    }

    @Test fun subtractionAtLongBoundaryRemainsExact() {
        assertEquals(Long.MAX_VALUE - 1, draft(Long.MAX_VALUE, DraftOperation.SUBTRACT, "0.01").amounts().breakfast)
        assertEquals(0L, draft(Long.MAX_VALUE, DraftOperation.SUBTRACT, "92233720368547758.07").amounts().breakfast)
    }

    @Test fun bulkEntryAndEditingKeepFullLongMinorUnits() {
        val amounts = Amounts.adding("92233720368547758.07", "10000", "", "0.01")
        assertEquals(Amounts(Long.MAX_VALUE, 1000000, 0, 1), amounts)
        assertEquals(Amounts(Long.MAX_VALUE, 0, 0, 0), Amounts.editing(item, "92233720368547758.07", "", "", ""))
    }

    @Test fun dailyAndMonthlyTotalsDoNotOverflowStoredLongAmounts() {
        val maximum = item.copy(breakfast = Long.MAX_VALUE, lunch = Long.MAX_VALUE, dinner = Long.MAX_VALUE, other = Long.MAX_VALUE)
        assertEquals(BigInteger("36893488147419103228"), maximum.totalCents(MoneyUnit.YUAN))
        assertEquals("¥368934881474191032.28", maximum.totals())
        assertEquals("¥11436981325699922000.68", MoneyUnit.YUAN.format(List(31) { maximum }.totalCents(MoneyUnit.YUAN)))
        assertEquals("£92233720368547758.07", MoneyUnit.POUND.format(Long.MAX_VALUE))
        assertEquals("¥0.00", MoneyUnit.YUAN.format(emptyList<ConsumptionItem>().totalCents(MoneyUnit.YUAN)))
    }

    @Test fun largeMixedCurrencySharesAndTotalsStaySeparate() {
        val mixed = item.copy(breakfast = Long.MAX_VALUE, lunch = Long.MAX_VALUE, dinner = Long.MAX_VALUE, other = Long.MAX_VALUE,
            dinnerCurrency = MoneyUnit.DOLLAR, otherCurrency = MoneyUnit.DOLLAR)
        assertEquals("¥184467440737095516.14 + $184467440737095516.14", mixed.totals())
        val shares = dailyShares(mixed, MoneyUnit.YUAN)
        assertEquals(listOf(0.5f, 0.5f, 0f, 0f), shares.map { it.fraction })
    }
}
