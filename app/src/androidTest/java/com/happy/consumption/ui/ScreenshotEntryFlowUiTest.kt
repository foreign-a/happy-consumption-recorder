package com.happy.consumption.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.happy.consumption.MainActivity
import com.happy.consumption.R
import com.happy.consumption.data.*
import com.happy.consumption.ui.theme.HappyConsumptionTheme
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real dialog and repository using a disposable, synthetic QA account. */
@RunWith(AndroidJUnit4::class)
class ScreenshotEntryFlowUiTest {
    companion object {
        @JvmStatic @BeforeClass fun onlyIsolatedQaPackage() {
            assumeTrue("Only the isolated QA package may run this test",
                InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.happy.consumption.qa")
        }
    }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private lateinit var database: AppDatabase
    private lateinit var manager: UserManager
    private lateinit var owner: UserSession
    private lateinit var repository: UserRepository
    private val open = mutableStateOf(false)
    private val closed = AtomicBoolean(false)
    private val savedCount = AtomicInteger(0)

    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "screenshot-ui-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "users.preferences_pb") })
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        manager = UserManager(store, database, root)
        manager.initialize()
        manager.register("synthetic-ui", "fixture-password")
        manager.login("synthetic-ui", "fixture-password")
        owner = checkNotNull(manager.session.value)
        repository = UserRepository(manager, database, owner, Clock.systemUTC())
    }

    @After fun cleanup() {
        rule.runOnUiThread { open.value = false }
        rule.waitForIdle()
        if (::scope.isInitialized) scope.cancel()
        if (::database.isInitialized) database.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun show(initial: String, requestId: String = UUID.randomUUID().toString()) {
        closed.set(false); savedCount.set(0)
        rule.runOnUiThread {
            open.value = true
            rule.activity.setContent {
                HappyConsumptionTheme {
                    if (open.value) ScreenshotEntryDialog(initial, requestId, repository,
                        close = { open.value = false; closed.set(true) }, saved = { savedCount.incrementAndGet() })
                }
            }
        }
        rule.waitUntil(15000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun text(id: Int) = rule.activity.getString(id)
    private fun button(id: Int) = rule.onNode(hasText(text(id)) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun amountField() = rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
    private fun waitClosed() = rule.waitUntil(15000) { closed.get() }
    private fun entries() = runBlocking { database.ledger().allEntries(owner.id) }
    private fun breakfast(): Long = runBlocking {
        val day = LocalDate.now(ZoneOffset.UTC)
        (-1L..1L).sumOf { offset -> database.notes().queryByUserAndDate(owner.id, day.plusDays(offset).toString())?.breakfast ?: 0L }
    }

    @Test fun cancelAfterEditingMakesNoRecord() {
        show("1.00")
        button(R.string.screenshot_record).assertIsNotEnabled()
        amountField().performTextReplacement("14.25")
        button(R.string.screenshot_expense).performClick()
        button(R.string.screenshot_record).assertIsNotEnabled()
        button(R.string.cancel).performClick()
        waitClosed()
        assertEquals(0, savedCount.get())
        assertEquals(0L, breakfast())
        assertTrue(entries().isEmpty())
    }

    @Test fun correctedExpenseRequiresRemarkAndAddsOnlyTheCorrectAmount() {
        show("1.00")
        amountField().performTextReplacement("12.34")
        button(R.string.screenshot_expense).performClick()
        button(R.string.screenshot_record).assertIsNotEnabled()
        button(R.string.breakfast).performScrollTo().performClick()
        button(R.string.screenshot_record).assertIsEnabled().performClick()
        waitClosed()
        assertEquals(1, savedCount.get())
        assertEquals(1234L, breakfast())
        assertTrue(entries().isEmpty())
    }

    @Test fun correctedIncomeRequiresRemarkAndCreatesSeparateIncomeEntry() {
        show("2.00")
        amountField().performTextReplacement("45.67")
        button(R.string.screenshot_income).performClick()
        button(R.string.screenshot_record).assertIsNotEnabled()
        button(R.string.screenshot_other).performScrollTo().performClick()
        button(R.string.screenshot_record).assertIsEnabled().performClick()
        waitClosed()
        assertEquals(1, savedCount.get())
        val entry = entries().single()
        assertEquals(owner.id, entry.userId)
        assertEquals(EntryKind.INCOME.name, entry.kind)
        assertEquals(4567L, entry.amount)
        assertTrue(entry.isOtherIncome)
        assertEquals(0L, breakfast())
    }

    @Test fun previouslyCommittedRequestDoesNotSilentlySaveAnEditedAmount() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        assertTrue(repository.recordScreenshot(requestId, 111, EntryKind.INCOME, ScreenshotRemark.OtherIncome))
        show("1.11", requestId)
        amountField().performTextReplacement("9.99")
        button(R.string.screenshot_income).performClick()
        button(R.string.screenshot_other).performScrollTo().performClick()
        button(R.string.screenshot_record).performClick()
        rule.waitUntil(15000) { rule.onAllNodesWithText(text(R.string.screenshot_already_recorded)).fetchSemanticsNodes().isNotEmpty() }
        button(R.string.screenshot_record).assertIsNotEnabled()
        assertFalse(closed.get())
        assertEquals(0, savedCount.get())
        assertEquals(111L, entries().single().amount)
        button(R.string.cancel).performClick()
        waitClosed()
    }
}
