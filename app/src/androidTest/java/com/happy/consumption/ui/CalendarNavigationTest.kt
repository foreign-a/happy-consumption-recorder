package com.happy.consumption.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.happy.consumption.*
import com.happy.consumption.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*

class CalendarNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as HappyApplication
    private lateinit var owner: UserSession
    private lateinit var today: LocalDate
    private fun waitTag(tag: String) { rule.waitUntil(20000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun tap(tag: String) { waitTag(tag);rule.onNodeWithTag(tag).performClick() }
    private fun row(date: LocalDate) { waitTag("batch_date_$date");rule.onNodeWithTag("batch_date_$date").performScrollTo().performClick() }
    private fun openDelete() { rule.onNodeWithTag("record_actions").performScrollTo().performClick();tap("batch_delete_entry");waitTag("batch_delete_dialog") }
    @Before fun setup() = runBlocking {
        app.language.select("zh");app.userManager.logout()
        val name="v14-calendar-${System.nanoTime()}"
        app.userManager.register(name,"fixture-v14");app.userManager.login(name,"fixture-v14")
        owner=app.userManager.session.value!!
        val settings=PersonalSchedule(app,owner.id);settings.save(MealSchedule("Asia/Shanghai"))
        today=LocalDate.now(ZoneId.of("Asia/Shanghai"))
        UserRepository(app.userManager,app.database,owner,settings.clock()).ensureToday()
        Unit
    }
    @Test fun missingPastDateCreatesZeroImmediatelyAndRepeatingDoesNotOverwrite() {
        val date=today.minusDays(1)
        waitTag("calendar_date_$date")
        rule.waitUntil(15000) { try { rule.onNodeWithTag("calendar_date_$date").assertIsEnabled();true } catch (_:AssertionError) { false } }
        tap("calendar_date_$date")
        rule.waitUntil(15000) { runBlocking { app.database.notes().queryByUserAndDate(owner.id,date.toString())!=null } }
        val note=runBlocking { app.database.notes().queryByUserAndDate(owner.id,date.toString())!! }
        waitTag("record_${note.id}");assertEquals(0L,note.breakfast+note.lunch+note.dinner+note.other)
        rule.onNodeWithTag("batch_delete_dialog").assertDoesNotExist()
        tap("amount_${note.id}_OTHER");tap("key_7");tap("keypad_confirm")
        rule.waitUntil(15000) { runBlocking { app.database.notes().queryByUserAndDate(owner.id,date.toString())!!.other==700L } }
        tap("calendar_date_$date")
        assertEquals(700L,runBlocking { app.database.notes().queryByUserAndDate(owner.id,date.toString())!!.other })
        assertEquals(2,runBlocking { app.database.notes().countByUserOnce(owner.id) })
        rule.onNodeWithTag("calendar_toggle").performScrollTo().performClick()
        if (today.dayOfMonth<today.lengthOfMonth()) {
            rule.onNodeWithTag("calendar_date_${today.plusDays(1)}").assertIsNotEnabled()
        }
    }
    @Test fun multiMonthSelectionCancelsSafelyThenDeletesOnlyConfirmedDates() {
        val recent=today.minusDays(1)
        val older=YearMonth.from(recent).minusMonths(1).atEndOfMonth()
        val keep=generateSequence(today.minusDays(2)) { it.minusDays(1) }.first { it!=recent && it!=older }
        runBlocking {
            for (date in listOf(recent,older,keep)) app.database.notes().insertIfAbsent(NoteEntity(userId=owner.id,date=date.toString(),breakfast=123,lunch=200,dinner=300,other=400,isManual=true,updatedAt=0))
        }
        waitTag("record_actions");openDelete()
        rule.onNodeWithTag("batch_date_$today").assertDoesNotExist()
        rule.onNodeWithTag("batch_review_delete").assertIsNotEnabled()
        var month=YearMonth.from(today)
        while (month>YearMonth.from(recent)) { tap("batch_previous");month=month.minusMonths(1) }
        row(recent)
        while (month>YearMonth.from(older)) { tap("batch_previous");month=month.minusMonths(1) }
        row(older);rule.onNodeWithTag("batch_selected_count").assertTextEquals("已选 2 条")
        tap("batch_review_delete")
        assertEquals(4,runBlocking { app.database.notes().countByUserOnce(owner.id) })
        tap("batch_cancel");tap("batch_cancel")
        assertEquals(4,runBlocking { app.database.notes().countByUserOnce(owner.id) })
        openDelete();month=YearMonth.from(today)
        while (month>YearMonth.from(recent)) { tap("batch_previous");month=month.minusMonths(1) }
        row(recent)
        while (month>YearMonth.from(older)) { tap("batch_previous");month=month.minusMonths(1) }
        row(older);tap("batch_review_delete");tap("batch_confirm_delete")
        rule.waitUntil(15000) { runBlocking { app.database.notes().countByUserOnce(owner.id)==2 } }
        assertNotNull(runBlocking { app.database.notes().queryByUserAndDate(owner.id,today.toString()) })
        assertNotNull(runBlocking { app.database.notes().queryByUserAndDate(owner.id,keep.toString()) })
        assertNull(runBlocking { app.database.notes().queryByUserAndDate(owner.id,older.toString()) })
        rule.waitUntil(15000) { try { rule.onNodeWithTag("calendar_date_$recent").assertIsEnabled();true } catch (_:AssertionError) { false } }
        tap("calendar_date_$recent")
        rule.waitUntil(15000) { runBlocking { app.database.notes().queryByUserAndDate(owner.id,recent.toString())!=null } }
        assertEquals(0L,runBlocking { app.database.notes().queryByUserAndDate(owner.id,recent.toString())!!.breakfast })
    }
    @Test fun creatingPastDateStillProtectsUnsavedAmounts() {
        waitTag("phone_keypad")
        val id=runBlocking { app.database.notes().queryByUserAndDate(owner.id,today.toString())!!.id }
        tap("amount_${id}_OTHER");tap("key_5");tap("calendar_date_${today.minusDays(1)}")
        waitTag("leave_keep")
        assertNull(runBlocking { app.database.notes().queryByUserAndDate(owner.id,today.minusDays(1).toString()) })
        tap("leave_keep");tap("calendar_date_${today.minusDays(1)}");tap("leave_save")
        rule.waitUntil(15000) { runBlocking { app.database.notes().queryByUserAndDate(owner.id,today.minusDays(1).toString())!=null } }
        assertEquals(500L,runBlocking { app.database.notes().queryByUserAndDate(owner.id,today.toString())!!.other })
    }
}
