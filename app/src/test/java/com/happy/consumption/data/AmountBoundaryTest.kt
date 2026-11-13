package com.happy.consumption.data

// 移植自 QA AmountBoundaryTest.java；生产 Long 分校验，保留空/null 拒绝。
import org.junit.Assert.*
import org.junit.Test

class AmountBoundaryTest {
    @Test fun fullDatasetAmounts() {
        val rows = DatasetFixture.rows; assertEquals(31104, rows.size)
        val amounts = rows.flatMap { it.subList(2,6) }.map { AmountInput.cents(it) }
        assertTrue(amounts.all { it in 0L..999999L }); assertTrue(0L in amounts); assertTrue(999999L in amounts)
    }
    @Test fun legalBoundary() {
        assertEquals(0L, AmountInput.cents("0")); assertEquals(1L, AmountInput.cents("0.01"))
        assertEquals(999999L, AmountInput.cents("9999.99"))
        assertEquals(1000000L, AmountInput.cents("10000.00"))
        assertEquals(100000000000L, AmountInput.cents("1000000000"))
        assertEquals(Long.MAX_VALUE - 7L, AmountInput.cents("92233720368547758"))
        assertEquals(Long.MAX_VALUE - 1L, AmountInput.cents("92233720368547758.06"))
        assertEquals(Long.MAX_VALUE, AmountInput.cents("92233720368547758.07"))
    }
    @Test fun illegalBoundary() {
        for (value in listOf(null, "", " ", "-0.01", "0.001", "92233720368547758.08", "92233720368547759", "999999999999999999999999.00")) {
            try { AmountInput.cents(value); fail("invalid amount accepted") } catch (e: UserOperationException) { assertEquals(UserError.INVALID_AMOUNT, e.reason) }
        }
    }
    @Test fun storedAmountsAcceptTheFullNonNegativeLongRange() {
        Amounts(Long.MAX_VALUE, 1000000L, Long.MAX_VALUE, 0L).validate()
        for (amounts in listOf(Amounts(-1, 0, 0, 0), Amounts(0, -1, 0, 0), Amounts(0, 0, -1, 0), Amounts(0, 0, 0, -1))) {
            try { amounts.validate(); fail("negative amount accepted") }
            catch (e: UserOperationException) { assertEquals(UserError.INVALID_AMOUNT, e.reason) }
        }
    }
}
