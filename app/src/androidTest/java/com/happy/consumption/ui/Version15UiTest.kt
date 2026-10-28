package com.happy.consumption.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.happy.consumption.*
import com.happy.consumption.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*

class Version15UiTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as HappyApplication
    private lateinit var owner: UserSession
    private var id=0L
    private fun waitTag(tag: String) { rule.waitUntil(20000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun tap(tag: String) { waitTag(tag);rule.onNodeWithTag(tag).performClick() }
    private fun outside() { rule.onNodeWithTag("outside_confirm_area").performTouchInput { click() } }
    private fun value()=runBlocking { app.database.notes().queryByIdAndUser(id,owner.id)!!.breakfast }
    private fun waitValue(amount: Long) { rule.waitUntil(15000) { value()==amount } }
    private fun setModes(adjust: Boolean=true,quick: Boolean=true) {
        tap("tab_user")
        rule.onNodeWithTag("entry_mode_${if(adjust) "ADJUST" else "REPLACE"}").performScrollTo().performClick()
        rule.onNodeWithTag("confirmation_${if(quick) "QUICK" else "CAREFUL"}").performScrollTo().performClick()
        tap("tab_home");waitTag("phone_keypad");tap("amount_${id}_BREAKFAST")
    }
    @Before fun setup() {
        runBlocking {
            app.language.select("zh");app.userManager.logout()
            val name="v15-${System.nanoTime()}";app.userManager.register(name,"fixture-v15");app.userManager.login(name,"fixture-v15")
            owner=app.userManager.session.value!!
            val settings=PersonalSchedule(app,owner.id);settings.save(MealSchedule("Asia/Shanghai"))
            val repo=UserRepository(app.userManager,app.database,owner,settings.clock());repo.ensureToday()
            id=app.database.notes().queryByUserAndDate(owner.id,LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())!!.id
            app.database.notes().updateByIdAndUser(id,owner.id,1000,0,0,0,true,System.currentTimeMillis())
        }
        waitTag("phone_keypad");waitTag("amount_${id}_BREAKFAST")
        rule.waitUntil(15000) { rule.onAllNodesWithText("¥10.00").fetchSemanticsNodes().size>=2 }
    }
    @Test fun defaultCarefulOutsideOffersKeepDiscardAndSaveWithoutConfirmButton() {
        rule.onNodeWithTag("keypad_confirm").assertDoesNotExist()
        tap("amount_${id}_BREAKFAST");tap("key_2");outside();waitTag("leave_keep")
        assertEquals(1000L,value());tap("leave_keep");assertEquals(1000L,value())
        outside();tap("leave_discard");assertEquals(1000L,value())
        tap("amount_${id}_BREAKFAST");tap("key_3");outside();tap("leave_save");waitValue(300L)
        rule.onNodeWithTag("phone_keypad").assertIsDisplayed()
    }
    @Test fun quickAddReusesFreshTotalAndBlankTapDoesNotApplyTwice() {
        setModes();tap("key_2");assertEquals(1000L,value());outside();waitValue(1200L)
        outside();assertEquals(1200L,value())
        tap("key_3");outside();waitValue(1500L)
        assertEquals(EntryPreferences(EntryMode.ADJUST,ConfirmationPlan.QUICK),PersonalSchedule(app,owner.id).entryPreferences())
        assertEquals(EntryPreferences(),PersonalSchedule(app,owner.id+100000).entryPreferences())
    }
    @Test fun doubleTapSwitchesToRedSubtractThenAnyAmountReturnsToAdd() {
        setModes()
        rule.onNodeWithTag("amount_${id}_BREAKFAST").performTouchInput { doubleClick() }
        rule.waitUntil(10000) { rule.onAllNodesWithText("减模式",substring=true).fetchSemanticsNodes().isNotEmpty() }
        tap("key_2");outside();waitValue(800L)
        rule.onNodeWithTag("amount_${id}_OTHER").performTouchInput { doubleClick() }
        rule.waitUntil(10000) { rule.onAllNodesWithText("加模式",substring=true).fetchSemanticsNodes().isNotEmpty() }
        tap("amount_${id}_BREAKFAST");tap("key_1");outside();waitValue(900L)
    }
    @Test fun quickNavigationSavesBeforeOpeningUserPage() {
        setModes();tap("key_2");tap("tab_user");waitTag("user_page");waitValue(1200L)
        rule.onNodeWithTag("leave_save").assertDoesNotExist()
        tap("tab_home");waitTag("phone_keypad");assertEquals(1200L,value())
    }
    @Test fun negativeSubtractIsRejectedAndStaysEditable() {
        setModes();rule.onNodeWithTag("amount_${id}_BREAKFAST").performTouchInput { doubleClick() }
        tap("key_2");tap("key_0");outside()
        rule.waitUntil(10000) { rule.onAllNodesWithText("加减后的金额必须在0～9999.99之间，不能减成负数。").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1000L,value());tap("key_backspace");outside();waitValue(800L)
    }
    @Test fun cautiousNavigationKeepsPendingAddUntilExplicitChoice() {
        setModes(quick=false);tap("key_2");tap("tab_user");waitTag("leave_keep")
        rule.onNodeWithTag("user_page").assertDoesNotExist();assertEquals(1000L,value())
        tap("leave_keep");tap("tab_user");tap("leave_save");waitTag("user_page");waitValue(1200L)
    }
}
