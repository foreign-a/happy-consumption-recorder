package com.happy.consumption.data

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class Version11Test {
    private val item = ConsumptionItem(1, 2, "2024-02-29", 123, 456, 789, 1000, true, 9)
    @Test fun blankBuffersPreserveOldAndExplicitZeroReplaces() {
        assertEquals(Amounts(123, 456, 789, 1000), Amounts.editing(item, "", "", "", ""))
        assertEquals(Amounts(100, 456, 0, 1000), Amounts.editing(item, "1", "", "0", ""))
        assertEquals(Amounts(0, 1, 0, 0), Amounts.adding("", "0.01", "", ""))
    }
    @Test fun invalidEditedAmountsStillReject() {
        for (bad in listOf("-1", "10000", "1.001", "abc")) {
            try { Amounts.editing(item, bad, "", "", ""); fail(bad) } catch (_: UserOperationException) { }
        }
    }
    @Test fun currencyFormattingKeepsExactValues() {
        assertEquals("¥0.01",MoneyUnit.YUAN.format(1))
        assertEquals("$9999.99",MoneyUnit.DOLLAR.format(999999))
        assertEquals("£10000.00",MoneyUnit.POUND.format(1000000))
        assertEquals(MoneyUnit.YUAN,MoneyUnit.stored(null))
    }
    @Test fun thirtyDaysPrecedeEarliestAcrossLeapAndYearBoundaries() {
        val dates = DatePolicy(Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC))
        val batch = dates.previousThirtyDays("2024-03-01")
        assertEquals(30, batch.size); assertEquals(30, batch.toSet().size)
        assertEquals("2024-02-29", batch.first()); assertEquals("2024-01-31", batch.last())
        assertEquals("2023-12-31", dates.previousThirtyDays("2024-01-01").first())
        assertEquals("2026-10-10", dates.previousThirtyDays(null).first())
    }
    @Test fun backupRoundTripUsesRandomSaltAndAuthenticatedEncryption() {
        val data = "synthetic accounts and notes".toByteArray()
        val password = "backup-test-only".toCharArray()
        val first = BackupCipher.encrypt(data, password)
        assertArrayEquals(data, BackupCipher.decrypt(first, password))
        assertFalse(first.contentEquals(BackupCipher.encrypt(data, password)))
        try { BackupCipher.decrypt(first, "wrong-password".toCharArray()); fail() } catch (_: Exception) { }
        for (offset in listOf(0, 10, first.lastIndex)) {
            val broken = first.copyOf(); broken[offset] = (broken[offset].toInt() xor 1).toByte()
            try { BackupCipher.decrypt(broken, password); fail() } catch (_: Exception) { }
        }
    }
}
