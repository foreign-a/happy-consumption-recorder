package com.happy.consumption.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.happy.consumption.HappyApplication
import com.happy.consumption.MainActivity
import com.happy.consumption.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class Version16UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as HappyApplication
    private lateinit var owner: UserSession
    private var id = 0L

    private fun waitTag(tag: String) {
        rule.waitUntil(20000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tap(tag: String) {
        waitTag(tag)
        rule.onNodeWithTag(tag).performClick()
    }

    private fun amountTag(meal: Meal) = "amount_${id}_${meal.name}"

    private fun tapAmount(meal: Meal) {
        waitTag(amountTag(meal))
        rule.onNodeWithTag(amountTag(meal)).performTouchInput { click() }
    }

    private fun enter(input: String) {
        input.forEach { tap("key_$it") }
    }

    private fun outside() {
        rule.onNodeWithTag("outside_confirm_area").performTouchInput { click() }
    }

    private fun value(meal: Meal): Long = runBlocking {
        val row = app.database.notes().queryByIdAndUser(id, owner.id)!!
        when (meal) {
            Meal.BREAKFAST -> row.breakfast
            Meal.LUNCH -> row.lunch
            Meal.DINNER -> row.dinner
            Meal.OTHER -> row.other
        }
    }

    private fun waitValue(meal: Meal, amount: Long) {
        rule.waitUntil(15000) { value(meal) == amount }
    }

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.children.flatMap(::nodeTexts)

    private fun preview(meal: Meal = Meal.BREAKFAST): String =
        nodeTexts(rule.onNodeWithTag(amountTag(meal)).fetchSemanticsNode())
            .single { it.startsWith("¥") || it.startsWith("+¥") || it.startsWith("−¥") }

    private fun setModes(mode: EntryMode, confirmation: ConfirmationPlan) {
        tap("tab_user")
        rule.onNodeWithTag("entry_mode_${mode.name}").performScrollTo().performClick()
        rule.onNodeWithTag("confirmation_${confirmation.name}").performScrollTo().performClick()
        tap("tab_home")
        waitTag("phone_keypad")
        tap(amountTag(Meal.BREAKFAST))
    }

    private fun selectOperation(operation: DraftOperation, confirmation: ConfirmationPlan) {
        setModes(if (operation == DraftOperation.REPLACE) EntryMode.REPLACE else EntryMode.ADJUST, confirmation)
        if (operation == DraftOperation.SUBTRACT) {
            rule.onNodeWithTag(amountTag(Meal.BREAKFAST)).performTouchInput { doubleClick() }
            rule.waitUntil(10000) {
                rule.onAllNodesWithText("减模式", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Before fun setup() {
        runBlocking {
            app.language.select("zh")
            app.userManager.logout()
            val name = "v16-${System.nanoTime()}"
            app.userManager.register(name, "fixture-v16")
            app.userManager.login(name, "fixture-v16")
            owner = app.userManager.session.value!!
            val settings = PersonalSchedule(app, owner.id)
            assertTrue(settings.save(MealSchedule("Asia/Shanghai")))
            UserRepository(app.userManager, app.database, owner, settings.clock()).ensureToday()
            val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
            id = app.database.notes().queryByUserAndDate(owner.id, today)!!.id
            app.database.notes().updateByIdAndUser(id, owner.id, 1000, 2000, 3000, 4000, true,
                System.currentTimeMillis(), MoneyUnit.YUAN.name, MoneyUnit.YUAN.name,
                MoneyUnit.YUAN.name, MoneyUnit.YUAN.name)
        }
        waitTag("phone_keypad")
        waitTag(amountTag(Meal.BREAKFAST))
        rule.waitUntil(15000) { preview() == "¥10.00" }
    }

    @Test fun quickReplaceTappingEachOtherAmountSavesLunchAndEditsTheTappedAmount() {
        setModes(EntryMode.REPLACE, ConfirmationPlan.QUICK)
        rule.onNodeWithTag("key_confirm").assertDoesNotExist()
        listOf(Meal.BREAKFAST, Meal.DINNER, Meal.OTHER).forEachIndexed { index, destination ->
            val oldLunch = value(Meal.LUNCH)
            tapAmount(Meal.LUNCH)
            enter((25 + index).toString())
            assertEquals(oldLunch, value(Meal.LUNCH))

            tapAmount(destination)
            waitValue(Meal.LUNCH, (25 + index) * 100L)
            rule.onNodeWithTag("leave_save").assertDoesNotExist()

            enter("1")
            outside()
            waitValue(destination, 100L)
            assertEquals((25 + index) * 100L, value(Meal.LUNCH))
        }
    }

    @Test fun quickAdjustAmountSwitchUsesFreshTotalsWithoutApplyingAnAdjustmentTwice() {
        setModes(EntryMode.ADJUST, ConfirmationPlan.QUICK)
        tapAmount(Meal.LUNCH)
        enter("2")
        tapAmount(Meal.BREAKFAST)
        waitValue(Meal.LUNCH, 2200L)
        assertEquals(1000L, value(Meal.BREAKFAST))

        enter("3")
        tapAmount(Meal.DINNER)
        waitValue(Meal.BREAKFAST, 1300L)
        assertEquals(2200L, value(Meal.LUNCH))

        enter("4")
        outside()
        waitValue(Meal.DINNER, 3400L)
        outside()
        assertEquals(1300L, value(Meal.BREAKFAST))
        assertEquals(2200L, value(Meal.LUNCH))
        assertEquals(3400L, value(Meal.DINNER))
    }

    @Test fun carefulConfirmSavesPendingAmountsDirectlyAndKeepsTheKeypadAvailable() {
        setModes(EntryMode.REPLACE, ConfirmationPlan.CAREFUL)
        rule.onNodeWithTag("key_confirm").assertIsDisplayed()
        tapAmount(Meal.LUNCH)
        enter("25")
        tapAmount(Meal.BREAKFAST)
        enter("15")
        assertEquals(1000L, value(Meal.BREAKFAST))
        assertEquals(2000L, value(Meal.LUNCH))
        rule.onNodeWithTag("leave_save").assertDoesNotExist()

        tap("key_confirm")
        waitValue(Meal.BREAKFAST, 1500L)
        waitValue(Meal.LUNCH, 2500L)
        rule.onNodeWithTag("leave_save").assertDoesNotExist()
        rule.onNodeWithTag("phone_keypad").assertIsDisplayed()
        rule.onNodeWithTag("key_confirm").assertIsDisplayed()
        rule.onNodeWithTag("key_1").assertIsEnabled()
    }

    @Test fun carefulOutsideStillRequiresAChoiceAndConfirmCanSaveAfterKeepEditing() {
        tapAmount(Meal.LUNCH)
        enter("25")
        outside()
        waitTag("leave_keep")
        assertEquals(2000L, value(Meal.LUNCH))
        tap("leave_keep")
        assertEquals("¥25", preview(Meal.LUNCH))
        tap("key_confirm")
        waitValue(Meal.LUNCH, 2500L)
        rule.onNodeWithTag("leave_save").assertDoesNotExist()
    }

    @Test fun releasingBackspaceBeforeFourHundredMillisecondsDeletesOneCharacterAndCancelsDelayedRepeat() {
        tapAmount(Meal.BREAKFAST)
        enter("1234.56")
        withControlledMainClock {
            rule.onNodeWithTag("key_backspace").performTouchInput { down(center) }
            try {
                rule.mainClock.advanceTimeBy(200, ignoreFrameDuration = true)
                assertEquals("¥1234.56", preview())
            } finally {
                rule.onNodeWithTag("key_backspace").performTouchInput { up() }
            }
            rule.mainClock.advanceTimeByFrame()
            assertEquals("¥1234.5", preview())
            // Cross the 400 ms deadline after release to catch an uncancelled repeat job.
            rule.mainClock.advanceTimeBy(480)
            assertEquals("¥1234.5", preview())
        }
        assertEquals(1000L, value(Meal.BREAKFAST))
    }

    private fun withControlledMainClock(action: () -> Unit) {
        val autoAdvance = rule.mainClock.autoAdvance
        rule.mainClock.autoAdvance = false
        try { action() } finally { rule.mainClock.autoAdvance = autoAdvance }
    }

    private fun assertFirstHeldDeletion(initial: String) {
        // Gesture delays and recomposition use MainTestClock under Compose instrumentation.
        // 384 ms is the last whole 16 ms frame before the 400 ms repeat deadline.
        rule.mainClock.advanceTimeBy(384)
        assertEquals("Holding must not delete before 400 ms", initial, preview())
        rule.mainClock.advanceTimeBy(16)
        // Render the state changed by delay(400) on the next frame, before the 480 ms repeat.
        rule.mainClock.advanceTimeByFrame()
        assertEquals("Holding must delete the first character at 400 ms", initial.dropLast(1), preview())
    }

    private fun assertHeldBackspaceRepeatsAndStops(operation: DraftOperation, confirmation: ConfirmationPlan) {
        selectOperation(operation, confirmation)
        val input = "123456789012345678901234"
        val prefix = when (operation) {
            DraftOperation.REPLACE -> "¥"
            DraftOperation.ADD -> "+¥"
            DraftOperation.SUBTRACT -> "−¥"
        }
        enter(input)
        val initial = prefix + input
        assertEquals(initial, preview())
        withControlledMainClock {
            rule.onNodeWithTag("key_backspace").performTouchInput { down(center) }
            try {
                assertFirstHeldDeletion(initial)
                repeat(5) { index ->
                    rule.mainClock.advanceTimeBy(80)
                    assertEquals("Each further 80 ms must delete exactly one character",
                        prefix + input.dropLast(index + 2), preview())
                }
                assertEquals(1000L, value(Meal.BREAKFAST))
            } finally {
                rule.onNodeWithTag("key_backspace").performTouchInput { up() }
            }
            rule.mainClock.advanceTimeByFrame()
            val released = prefix + input.dropLast(6)
            assertEquals("Releasing after repeat must not delete an extra character", released, preview())
            rule.mainClock.advanceTimeBy(400)
            assertEquals("Releasing must stop repeated deletion", released, preview())
        }
        assertEquals(1000L, value(Meal.BREAKFAST))
    }

    @Test fun heldBackspaceRepeatsAndStopsInReplaceCareful() =
        assertHeldBackspaceRepeatsAndStops(DraftOperation.REPLACE, ConfirmationPlan.CAREFUL)

    @Test fun heldBackspaceRepeatsAndStopsInReplaceQuick() =
        assertHeldBackspaceRepeatsAndStops(DraftOperation.REPLACE, ConfirmationPlan.QUICK)

    @Test fun heldBackspaceRepeatsAndStopsInAddCareful() =
        assertHeldBackspaceRepeatsAndStops(DraftOperation.ADD, ConfirmationPlan.CAREFUL)

    @Test fun heldBackspaceRepeatsAndStopsInAddQuick() =
        assertHeldBackspaceRepeatsAndStops(DraftOperation.ADD, ConfirmationPlan.QUICK)

    @Test fun heldBackspaceRepeatsAndStopsInSubtractCareful() =
        assertHeldBackspaceRepeatsAndStops(DraftOperation.SUBTRACT, ConfirmationPlan.CAREFUL)

    @Test fun heldBackspaceRepeatsAndStopsInSubtractQuick() =
        assertHeldBackspaceRepeatsAndStops(DraftOperation.SUBTRACT, ConfirmationPlan.QUICK)

    @Test fun cancellingHeldBackspaceStopsRepeatingWithoutSavingTheDraft() {
        tapAmount(Meal.BREAKFAST)
        enter("123456789012345678901234")
        val initial = preview()
        withControlledMainClock {
            rule.onNodeWithTag("key_backspace").performTouchInput { down(center) }
            try {
                assertFirstHeldDeletion(initial)
            } finally {
                rule.onNodeWithTag("key_backspace").performTouchInput { cancel() }
            }
            rule.mainClock.advanceTimeByFrame()
            val cancelled = initial.dropLast(1)
            assertEquals(cancelled, preview())
            rule.mainClock.advanceTimeBy(400)
            assertEquals("Cancelling the gesture must stop repeated deletion", cancelled, preview())
        }
        assertEquals(1000L, value(Meal.BREAKFAST))
    }
}
