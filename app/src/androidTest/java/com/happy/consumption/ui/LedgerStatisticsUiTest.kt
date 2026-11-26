package com.happy.consumption.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.happy.consumption.MainActivity
import com.happy.consumption.R
import com.happy.consumption.data.*
import com.happy.consumption.ui.theme.HappyConsumptionTheme
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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

/** Exercises the actual user page with a disposable in-memory account and database. */
@RunWith(AndroidJUnit4::class)
class LedgerStatisticsUiTest {
    companion object {
        @JvmStatic @BeforeClass fun onlyIsolatedQaPackage() {
            assumeTrue("Only the isolated QA package may run this test",
                InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.happy.consumption.qa")
        }
    }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var preferenceName: String
    private lateinit var dataScope: CoroutineScope
    private lateinit var viewScope: CoroutineScope
    private lateinit var database: AppDatabase
    private lateinit var manager: UserManager
    private lateinit var repository: UserRepository
    private lateinit var owner: UserSession
    private lateinit var store: ViewModelStore
    private val clock = Clock.fixed(Instant.parse("2026-11-26T03:00:00Z"), ZoneOffset.UTC)

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val nonce = UUID.randomUUID().toString()
        root = File(context.cacheDir, "ledger-ui-fixture-$nonce").apply { check(mkdirs()) }
        preferenceName = "ledger-ui-fixture-$nonce"
        dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = dataScope, produceFile = { File(root, "users.preferences_pb") })
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        manager = UserManager(preferences, database, root)
        manager.initialize()
        manager.register("synthetic-statistics", "fixture-password")
        manager.login("synthetic-statistics", "fixture-password")
        owner = checkNotNull(manager.session.value)
        repository = UserRepository(manager, database, owner, clock)

        manager.selectCurrency(owner, MoneyUnit.YUAN)
        repository.saveCustomRemark(EntryKind.EXPENSE, "synthetic-yuan-expense")
        assertTrue(repository.recordScreenshot("yuan-meal", 1234, EntryKind.EXPENSE, ScreenshotRemark.BuiltInMeal(Meal.BREAKFAST)))
        assertTrue(repository.recordScreenshot("yuan-custom", 321, EntryKind.EXPENSE, ScreenshotRemark.Custom("synthetic-yuan-expense")))
        assertTrue(repository.recordScreenshot("yuan-income", 5555, EntryKind.INCOME, ScreenshotRemark.OtherIncome))

        manager.selectCurrency(owner, MoneyUnit.DOLLAR)
        repository.saveCustomRemark(EntryKind.EXPENSE, "synthetic-dollar-expense")
        assertTrue(repository.recordScreenshot("dollar-meal", 876, EntryKind.EXPENSE, ScreenshotRemark.BuiltInMeal(Meal.LUNCH)))
        assertTrue(repository.recordScreenshot("dollar-custom", 420, EntryKind.EXPENSE, ScreenshotRemark.Custom("synthetic-dollar-expense")))
        assertTrue(repository.recordScreenshot("dollar-income", 999, EntryKind.INCOME, ScreenshotRemark.OtherIncome))

        val row = checkNotNull(database.notes().queryByUserAndDate(owner.id, "2026-11-26"))
        assertEquals(1234L, row.breakfast)
        assertEquals(876L, row.lunch)
        assertEquals(MoneyUnit.YUAN, row.toItem().currencies.breakfast)
        assertEquals(MoneyUnit.DOLLAR, row.toItem().currencies.lunch)
        assertEquals(4, database.ledger().allEntries(owner.id).size)
    }

    @After fun cleanup() {
        rule.runOnUiThread {
            rule.activity.setContent { }
            if (::viewScope.isInitialized) viewScope.cancel()
            if (::store.isInitialized) store.clear()
        }
        rule.waitForIdle()
        if (::dataScope.isInitialized) dataScope.cancel()
        if (::database.isInitialized) database.close()
        if (::preferenceName.isInitialized) context.deleteSharedPreferences(preferenceName)
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun userPageSeparatesIncomeAndSpendingByCurrencyWithoutDoubleCounting() {
        val isolatedPreferences = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(preferenceName, mode)
        }
        val schedule = PersonalSchedule(isolatedPreferences, owner.id)
        rule.runOnUiThread {
            viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            store = ViewModelStore()
            val auth = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(manager) as T
            })[AppViewModel::class.java]
            val records = AllRecordsViewModel(repository, viewScope)
            rule.activity.setContent {
                HappyConsumptionTheme {
                    UserScreen(owner.username, auth, language = null, backup = null,
                        schedule = schedule, records = records, ledger = repository)
                }
            }
        }
        rule.waitUntil(20000) {
            rule.onAllNodesWithTag("ledger_month_spending_YUAN").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag("ledger_month_spending_DOLLAR").fetchSemanticsNodes().isNotEmpty()
        }
        fun expect(tag: String, label: Int, unit: MoneyUnit, cents: Long) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .assertTextEquals(rule.activity.getString(label, unit.format(cents)))
        }
        // The meal amounts already live in the daily row, not in the independent ledger.
        expect("ledger_month_spending_YUAN", R.string.ledger_month_spending, MoneyUnit.YUAN, 1555)
        expect("ledger_month_income_YUAN", R.string.ledger_month_income, MoneyUnit.YUAN, 5555)
        expect("ledger_month_spending_DOLLAR", R.string.ledger_month_spending, MoneyUnit.DOLLAR, 1296)
        expect("ledger_month_income_DOLLAR", R.string.ledger_month_income, MoneyUnit.DOLLAR, 999)
        expect("ledger_day_spending_YUAN", R.string.ledger_day_spending, MoneyUnit.YUAN, 1555)
        expect("ledger_day_income_YUAN", R.string.ledger_day_income, MoneyUnit.YUAN, 5555)
        expect("ledger_day_spending_DOLLAR", R.string.ledger_day_spending, MoneyUnit.DOLLAR, 1296)
        expect("ledger_day_income_DOLLAR", R.string.ledger_day_income, MoneyUnit.DOLLAR, 999)
    }
}
