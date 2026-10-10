package com.happy.consumption.data
import org.junit.Assert.*
import org.junit.Test

class SecurityAndAmountTest {
    @Test fun saltAndVerification() {
        val first = PasswordHasher.newSalt(); val second = PasswordHasher.newSalt()
        assertEquals(16, first.size); assertEquals(16, second.size)
        assertFalse(first.contentEquals(second))
        val hash = PasswordHasher.hashPassword("test-only", first)
        assertFalse(hash.contentEquals(PasswordHasher.hashPassword("test-only", second)))
        assertTrue(PasswordHasher.verify("test-only", first, hash))
        assertFalse(PasswordHasher.verify("wrong", first, hash))
        assertArrayEquals(first, PasswordHasher.fromHex(PasswordHasher.hex(first)))
    }
    @Test fun amountBoundaries() {
        assertEquals(0L, AmountInput.cents("0")); assertEquals(1L, AmountInput.cents("0.01"))
        assertEquals(999999L, AmountInput.cents("9999.99"))
        for (value in listOf("-0.01", "10000.00", "1.001", "", "NaN", "1e3")) {
            try { AmountInput.cents(value); fail("invalid amount accepted") }
            catch (e: UserOperationException) { assertEquals(UserError.INVALID_AMOUNT, e.reason) }
        }
    }
}
