package com.happy.consumption.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.happy.consumption.HappyApplication
import com.happy.consumption.MainActivity
import com.happy.consumption.R
import com.happy.consumption.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class Version17UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as HappyApplication
    private lateinit var owner: UserSession
    private var id = 0L
    private val amountTag get() = "amount_${id}_BREAKFAST"
    private val maximum = "92233720368547758.07"

    private fun waitTag(tag: String) {
        rule.waitUntil(20000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tap(tag: String) {
        waitTag(tag)
        rule.onNodeWithTag(tag).performClick()
    }

    private fun enter(input: String) { input.forEach { tap("key_$it") } }
    private fun outside() { rule.onNodeWithTag("outside_confirm_area").performTouchInput { click() } }
    private fun value(): Long = runBlocking { app.database.notes().queryByIdAndUser(id, owner.id)!!.breakfast }
    private fun waitValue(amount: Long) { rule.waitUntil(15000) { value() == amount } }

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.children.flatMap(::nodeTexts)

    private fun preview(): String = nodeTexts(rule.onNodeWithTag(amountTag).fetchSemanticsNode())
        .single { it.startsWith("¥") || it.startsWith("+¥") || it.startsWith("−¥") }

    private fun setModes(mode: EntryMode, confirmation: ConfirmationPlan) {
        tap("tab_user")
        rule.onNodeWithTag("entry_mode_${mode.name}").performScrollTo().performClick()
        rule.onNodeWithTag("confirmation_${confirmation.name}").performScrollTo().performClick()
        tap("tab_home")
        waitTag("phone_keypad")
        tap(amountTag)
    }

    private fun expectError(resource: Int) {
        val message = rule.activity.getString(resource)
        rule.waitUntil(10000) { rule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("phone_keypad").assertIsDisplayed()
    }

    private fun saveMaximum() {
        setModes(EntryMode.REPLACE, ConfirmationPlan.QUICK)
        enter(maximum)
        outside()
        waitValue(Long.MAX_VALUE)
        rule.waitUntil(10000) { preview() == "¥$maximum" }
    }

    @Before fun setup() {
        runBlocking {
            app.language.select("zh")
            app.userManager.logout()
            val name = "v17-${System.nanoTime()}"
            app.userManager.register(name, "fixture-v17")
            app.userManager.login(name, "fixture-v17")
            owner = app.userManager.session.value!!
            val settings = PersonalSchedule(app, owner.id)
            assertTrue(settings.save(MealSchedule("Asia/Shanghai")))
            UserRepository(app.userManager, app.database, owner, settings.clock()).ensureToday()
            val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
            id = app.database.notes().queryByUserAndDate(owner.id, today)!!.id
            app.database.notes().updateByIdAndUser(id, owner.id, 1000, 0, 0, 0, true,
                System.currentTimeMillis(), MoneyUnit.YUAN.name, MoneyUnit.YUAN.name,
                MoneyUnit.YUAN.name, MoneyUnit.YUAN.name)
        }
        waitTag("phone_keypad")
        waitTag(amountTag)
        rule.waitUntil(15000) { preview() == "¥10.00" }
    }

    @Test fun carefulReplaceSavesAmountAboveFormerLimitToRoom() {
        setModes(EntryMode.REPLACE, ConfirmationPlan.CAREFUL)
        enter("10000.01")
        assertEquals(1000L, value())
        tap("key_confirm")
        waitValue(1_000_001L)
        rule.waitUntil(10000) { preview() == "¥10000.01" }
    }

    @Test fun quickReplaceSavesLongMaximumWithoutLosingTheLastCent() {
        saveMaximum()
        rule.activityRule.scenario.recreate()
        waitTag(amountTag)
        rule.waitUntil(15000) { preview() == "¥$maximum" }
        assertEquals(Long.MAX_VALUE, value())
    }

    @Test fun multipleMaximumAmountsShowAnExactTotalBeyondTheLongStorageRange() {
        setModes(EntryMode.REPLACE, ConfirmationPlan.CAREFUL)
        enter(maximum)
        tap("amount_${id}_LUNCH")
        enter(maximum)
        tap("key_confirm")
        waitValue(Long.MAX_VALUE)
        rule.waitUntil(15000) {
            runBlocking { app.database.notes().queryByIdAndUser(id, owner.id)!!.lunch == Long.MAX_VALUE }
        }
        rule.onNodeWithTag("total_$id").assertTextEquals("¥184467440737095516.14")
    }

    @Test fun oneCentBeyondLongMaximumIsRejectedAndTheDraftRemainsEditable() {
        setModes(EntryMode.REPLACE, ConfirmationPlan.CAREFUL)
        enter("92233720368547758.08")
        tap("key_confirm")
        expectError(R.string.invalid_amount)
        assertEquals(1000L, value())
        assertEquals("¥92233720368547758.08", preview())
        tap("key_backspace")
        tap("key_7")
        tap("key_confirm")
        waitValue(Long.MAX_VALUE)
    }

    @Test fun quickAdditionReachesLongMaximumThenRejectsOneCentOverflow() {
        setModes(EntryMode.REPLACE, ConfirmationPlan.QUICK)
        enter("92233720368547758.06")
        outside()
        waitValue(Long.MAX_VALUE - 1)
        setModes(EntryMode.ADJUST, ConfirmationPlan.QUICK)
        enter("0.01")
        outside()
        waitValue(Long.MAX_VALUE)
        enter("0.01")
        outside()
        expectError(R.string.invalid_adjustment)
        assertEquals(Long.MAX_VALUE, value())
        assertEquals("+¥0.01", preview())
    }

    @Test fun subtractionOfLargeAmountKeepsExactCentsAndRejectsNegativeResult() {
        saveMaximum()
        setModes(EntryMode.ADJUST, ConfirmationPlan.QUICK)
        rule.onNodeWithTag(amountTag).performTouchInput { doubleClick() }
        rule.waitUntil(10000) { rule.onAllNodesWithText("减模式", substring = true).fetchSemanticsNodes().isNotEmpty() }
        enter("92233720368547758.06")
        outside()
        waitValue(1L)
        enter("0.02")
        outside()
        expectError(R.string.invalid_adjustment)
        assertEquals(1L, value())
        assertEquals("−¥0.02", preview())
        tap("key_backspace")
        tap("key_1")
        outside()
        waitValue(0L)
    }
}
