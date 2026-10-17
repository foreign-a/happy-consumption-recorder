package com.happy.consumption.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.happy.consumption.*
import com.happy.consumption.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*

class CalendarNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as HappyApplication
    private val today = LocalDate.now()
    private lateinit var owner: UserSession
    private var todayId = 0L
    private fun waitTag(tag: String) { rule.waitUntil(15000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun tap(tag: String) { waitTag(tag); rule.onNodeWithTag(tag).performClick() }
    @Before fun setup() {
        runBlocking {
            app.language.select("zh"); app.userManager.logout()
            val name="calendar-${System.nanoTime()}";app.userManager.register(name,"calendar-only");app.userManager.login(name,"calendar-only")
            owner=app.userManager.session.value!!
            UserRepository(app.userManager,app.database,owner).ensureToday()
            todayId=app.database.notes().queryByUserAndDate(owner.id,today.toString())!!.id
        }
        waitTag("date_calendar")
        rule.waitUntil(15000) { rule.onAllNodesWithTag("calendar_date_$today").fetchSemanticsNodes().isNotEmpty() &&
            try { rule.onNodeWithTag("calendar_date_$today").assertIsEnabled(); true } catch (_: AssertionError) { false } }
    }
    @Test fun rollingWeekShowsSevenDaysMissingDatesDisabledAndMonthHasAllDays() {
        for (i in 0L..6L) rule.onNodeWithTag("calendar_date_${today.minusDays(i)}").assertExists()
        rule.onNodeWithTag("calendar_date_${today.minusDays(7)}").assertDoesNotExist()
        rule.onNodeWithTag("calendar_date_${today.minusDays(1)}").assertIsNotEnabled()
        tap("calendar_toggle")
        for (i in 1..today.lengthOfMonth()) rule.onNodeWithTag("calendar_date_${today.withDayOfMonth(i)}").assertExists()
        rule.onNodeWithTag("calendar_next").assertIsNotEnabled()
        assertEquals(1,runBlocking { app.database.notes().countByUserOnce(owner.id) })
        tap("calendar_toggle");rule.onNodeWithTag("calendar_previous").assertDoesNotExist()
    }
    @Test fun monthJumpLoadsOldPageAndTabRoundTripPreservesCalendarAndPosition() {
        val old=today.minusDays(210)
        val oldId=runBlocking {
            for (i in 0L..200L) app.database.notes().insertIfAbsent(NoteEntity(userId=owner.id,date=old.plusDays(i).toString(),breakfast=i,lunch=0,dinner=0,other=0,isManual=true,updatedAt=0))
            app.database.notes().queryByUserAndDate(owner.id,old.toString())!!.id
        }
        rule.waitUntil(15000) { rule.onAllNodesWithText("共 202 条").fetchSemanticsNodes().isNotEmpty() }
        tap("calendar_toggle")
        repeat(java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(old),YearMonth.from(today)).toInt()) { tap("calendar_previous") }
        rule.waitUntil(15000) { try { rule.onNodeWithTag("calendar_date_$old").assertIsEnabled();true } catch (_:AssertionError) { false } }
        tap("calendar_date_$old")
        rule.waitUntil(15000) { rule.onAllNodesWithTag("record_$oldId").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("record_$oldId").assertIsDisplayed()
        rule.onNodeWithTag("calendar_date_$old").assertIsSelected()
        val position=rule.onNodeWithTag("record_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        tap("tab_user");rule.onNodeWithTag("language_control").assertExists();rule.onNodeWithTag("currency_control").assertExists()
        tap("tab_home");rule.onNodeWithTag("calendar_date_$old").assertIsSelected()
        val after=rule.onNodeWithTag("record_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(position,after,1f);rule.onNodeWithTag("record_$oldId").assertIsDisplayed()
    }
    @Test fun userTabWaitsForDraftDecisionThenProvidesSettingsAndPasswordDeletion() {
        tap("amount_${todayId}_OTHER");tap("key_5");tap("tab_user")
        waitTag("leave_keep");rule.onNodeWithTag("user_page").assertDoesNotExist()
        tap("leave_keep");rule.onNodeWithTag("phone_keypad").assertExists()
        tap("tab_user");tap("leave_save");waitTag("user_page")
        assertEquals(500L,runBlocking { app.database.notes().queryByIdAndUser(todayId,owner.id)!!.other })
        rule.onNodeWithTag("language_control").assertExists();rule.onNodeWithTag("currency_control").assertExists()
        rule.onNodeWithText("备份与恢复").assertExists();rule.onNodeWithText("登出").assertExists()
        rule.onNodeWithText("删除用户").performScrollTo().performClick()
        rule.onNodeWithTag("delete_password").assertExists();rule.onNodeWithText("确认删除").assertIsNotEnabled()
        rule.onNodeWithText("取消").performClick();tap("tab_home")
        rule.onNodeWithTag("phone_keypad").assertDoesNotExist()
        tap("amount_${todayId}_OTHER");tap("key_7");tap("tab_user");tap("leave_discard");waitTag("user_page")
        assertEquals(500L,runBlocking { app.database.notes().queryByIdAndUser(todayId,owner.id)!!.other })
    }
}
