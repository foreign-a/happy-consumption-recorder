package com.happy.consumption.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ScreenshotLedgerTest {
    private class Fixture(context: Context) {
        val root = File(context.cacheDir, "screenshot-ledger-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "users.preferences_pb") })
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = UserManager(store, db, root)
        fun close() { scope.cancel(); db.close(); root.deleteRecursively() }
    }
    private lateinit var source: Fixture
    private lateinit var target: Fixture
    private val clock = Clock.fixed(Instant.parse("2026-11-26T03:00:00Z"), ZoneOffset.UTC)
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        source = Fixture(context); target = Fixture(context)
        source.manager.initialize(); target.manager.initialize()
        source.manager.register("one", "test-password"); source.manager.login("one", "test-password")
    }
    @After fun cleanup() { source.close(); target.close() }
    private fun repo(f: Fixture) = UserRepository(f.manager, f.db, f.manager.session.value!!, clock)

    @Test fun repeatedInitializationPreservesSessionButReloginDoesNot() = runBlocking {
        val first = source.manager.session.value!!
        source.manager.initialize()
        source.manager.initialize()
        assertEquals(first, source.manager.session.value)
        source.manager.logout()
        source.manager.initialize()
        assertNull(source.manager.session.value)
        source.manager.login("one", "test-password")
        val next = source.manager.session.value!!
        assertEquals(first.id, next.id)
        assertNotEquals(first, next)
        source.manager.initialize()
        assertEquals(next, source.manager.session.value)
        try { source.manager.withCurrentUser(first) { }; fail("stale session") } catch (_: AccessDeniedException) { }
    }

    @Test fun transactionIdempotencyIsolationAndBackupRoundTrip() = runBlocking {
        val first = repo(source)
        first.saveCustomRemark(EntryKind.EXPENSE, "bus")
        first.saveCustomRemark(EntryKind.INCOME, "salary")
        assertEquals(listOf("bus"), first.customRemarks(EntryKind.EXPENSE))
        assertTrue(first.recordScreenshot("meal", 100, EntryKind.EXPENSE, ScreenshotRemark.BuiltInMeal(Meal.BREAKFAST)))
        assertFalse(first.recordScreenshot("meal", 100, EntryKind.EXPENSE, ScreenshotRemark.BuiltInMeal(Meal.BREAKFAST)))
        assertFalse(first.recordScreenshot("meal", 999, EntryKind.EXPENSE, ScreenshotRemark.BuiltInMeal(Meal.BREAKFAST)))
        assertEquals(100L, source.db.notes().queryByUserAndDate(first.session.id, "2026-11-26")!!.breakfast)
        assertTrue(first.recordScreenshot("bus", 250, EntryKind.EXPENSE, ScreenshotRemark.Custom("bus")))
        assertTrue(first.recordScreenshot("income", 500, EntryKind.INCOME, ScreenshotRemark.OtherIncome))
        assertTrue(first.ledgerEntries("2026-11-26", "2026-11-26").single { it.kind == EntryKind.INCOME }.isOtherIncome)
        val backup = source.manager.exportBackup(first.session)
        source.manager.register("two", "test-password"); source.manager.login("two", "test-password")
        assertTrue(repo(source).ledgerEntries("2026-01-01", "2026-12-31").isEmpty())
        assertTrue(repo(source).customRemarks(EntryKind.EXPENSE).isEmpty())
        try { first.ledgerEntries("2026-01-01", "2026-12-31"); fail("stale session") } catch (_: AccessDeniedException) { }
        target.manager.register("existing", "test-password")
        target.manager.importBackup(backup)
        target.manager.login("one", "test-password")
        val restored = repo(target)
        assertEquals(listOf("bus"), restored.customRemarks(EntryKind.EXPENSE))
        assertEquals(listOf("salary"), restored.customRemarks(EntryKind.INCOME))
        assertEquals(2, restored.ledgerEntries("2026-11-26", "2026-11-26").size)
        assertTrue(restored.ledgerEntries("2026-11-26", "2026-11-26").single { it.kind == EntryKind.INCOME }.isOtherIncome)
        assertEquals(100L, target.db.notes().queryByUserAndDate(restored.session.id, "2026-11-26")!!.breakfast)
    }

    @Test fun retryAfterMidnightKeepsClickDate() = runBlocking {
        val repository = repo(source)
        val clicked = Instant.parse("2026-11-25T23:59:59Z").toEpochMilli()
        assertTrue(repository.recordScreenshot("before-midnight", 75, EntryKind.INCOME, ScreenshotRemark.OtherIncome, clicked))
        assertFalse(repository.recordScreenshot("before-midnight", 75, EntryKind.INCOME, ScreenshotRemark.OtherIncome, clicked))
        assertEquals("2026-11-25", repository.ledgerEntries("2026-11-25", "2026-11-26").single().date)
    }

    @Test fun currencyConflictAndOverflowRollbackPermitRetry() = runBlocking {
        val repository = repo(source)
        repository.add("2026-11-26", Amounts(Long.MAX_VALUE, 200, 0, 0), EntryCurrencies.uniform(MoneyUnit.YUAN))
        try { repository.recordScreenshot("overflow", 1, EntryKind.EXPENSE, ScreenshotRemark.BuiltInMeal(Meal.BREAKFAST)); fail() }
        catch (e: UserOperationException) { assertEquals(UserError.INVALID_ADJUSTMENT, e.reason) }
        assertTrue(repository.recordScreenshot("overflow", 0, EntryKind.EXPENSE, ScreenshotRemark.BuiltInMeal(Meal.DINNER)))
        source.manager.selectCurrency(repository.session, MoneyUnit.DOLLAR)
        try { repository.recordScreenshot("currency", 1, EntryKind.EXPENSE, ScreenshotRemark.BuiltInMeal(Meal.LUNCH)); fail() }
        catch (e: UserOperationException) { assertEquals(UserError.CURRENCY_CONFLICT, e.reason) }
        assertTrue(repository.recordScreenshot("currency", 1, EntryKind.EXPENSE, ScreenshotRemark.BuiltInMeal(Meal.OTHER)))
        val row = source.db.notes().queryByUserAndDate(repository.session.id, "2026-11-26")!!
        assertEquals(200L, row.lunch); assertEquals(1L, row.other); assertEquals(MoneyUnit.DOLLAR.name, row.otherCurrency)
    }
}
