package com.happy.consumption.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.happy.consumption.*
import com.happy.consumption.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*

class Version13UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as HappyApplication
    private lateinit var owner: UserSession
    private lateinit var today: LocalDate
    private var todayId=0L
    private fun waitTag(tag: String) { rule.waitUntil(20000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun tap(tag: String) { waitTag(tag); rule.onNodeWithTag(tag).performClick() }
    @Before fun setup() {
        runBlocking {
            app.language.select("zh");app.userManager.logout()
            val name="v13-${System.nanoTime()}"
            app.userManager.register(name,"test-version13");app.userManager.login(name,"test-version13")
            owner=app.userManager.session.value!!
            val schedule=PersonalSchedule(app,owner.id)
            assertTrue(schedule.save(MealSchedule("Asia/Shanghai")))
            today=LocalDate.now(ZoneId.of("Asia/Shanghai"))
            UserRepository(app.userManager,app.database,owner,schedule.clock()).ensureToday()
            todayId=app.database.notes().queryByUserAndDate(owner.id,today.toString())!!.id
        }
        waitTag("phone_keypad");waitTag("amount_${todayId}_OTHER")
    }
    @Test fun topIconSwitchesAndKeypadStaysAfterConfirm() {
        rule.onNodeWithTag("tab_user").assertExists();rule.onNodeWithTag("tab_home").assertDoesNotExist()
        tap("amount_${todayId}_OTHER");tap("key_5");tap("keypad_confirm")
        rule.waitUntil(15000) { runBlocking { app.database.notes().queryByIdAndUser(todayId,owner.id)!!.other==500L } }
        rule.onNodeWithTag("phone_keypad").assertIsDisplayed()
        rule.waitUntil(10000) { try { rule.onNodeWithTag("key_1").assertIsEnabled();true } catch (_: AssertionError) { false } }
        tap("tab_user");waitTag("user_page")
        rule.onNodeWithTag("tab_home").assertExists();rule.onNodeWithTag("tab_user").assertDoesNotExist()
        tap("tab_home");waitTag("phone_keypad")
    }
    @Test fun selectedHistoryIsTheOnlyRecordAndDraftIsProtected() {
        val old=today.minusDays(1)
        val oldId=runBlocking {
            app.database.notes().insertIfAbsent(NoteEntity(userId=owner.id,date=old.toString(),breakfast=123,lunch=200,dinner=300,other=400,isManual=true,updatedAt=0))
            app.database.notes().queryByUserAndDate(owner.id,old.toString())!!.id
        }
        rule.waitUntil(15000) { try { rule.onNodeWithTag("calendar_date_$old").assertIsEnabled();true } catch (_: AssertionError) { false } }
        tap("calendar_date_$old");waitTag("record_$oldId")
        rule.onNodeWithTag("record_$todayId").assertDoesNotExist()
        tap("amount_${oldId}_BREAKFAST");tap("key_9");tap("tab_user");waitTag("leave_keep")
        tap("leave_keep");rule.onNodeWithTag("user_page").assertDoesNotExist()
        tap("tab_user");tap("leave_save");waitTag("user_page")
        assertEquals(900L,runBlocking { app.database.notes().queryByIdAndUser(oldId,owner.id)!!.breakfast })
        tap("tab_home");waitTag("phone_keypad")
    }
    @Test fun scheduleIsUserScopedAndChartShowsFourAmounts() {
        val store=PersonalSchedule(app,owner.id)
        val configured=MealSchedule("America/New_York",LocalTime.of(7,0),LocalTime.of(13,0),LocalTime.of(19,0))
        assertTrue(store.save(configured));assertEquals(configured,PersonalSchedule(app,owner.id).read())
        assertNotEquals(configured,PersonalSchedule(app,owner.id+100000).read())
        assertTrue(store.save(MealSchedule("Asia/Shanghai")))
        runBlocking { app.database.notes().updateByIdAndUser(todayId,owner.id,100,200,300,400,true,0) }
        tap("tab_user");waitTag("user_page")
        rule.onNodeWithTag("edit_schedule").assertExists()
        val chart="chart_YUAN_$today"
        waitTag(chart);rule.onNodeWithTag(chart).performScrollTo().performClick()
        rule.onNodeWithText("当天消费 ¥10.00").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("¥1.00").assertExists();rule.onNodeWithText("¥2.00").assertExists()
        rule.onNodeWithText("¥3.00").assertExists();rule.onNodeWithText("¥4.00").assertExists()
    }

    @Test fun dailyPiePercentagesAndCustomColorsPersistWithoutChangingAmounts() {
        runBlocking { app.database.notes().updateByIdAndUser(todayId,owner.id,100,200,300,400,true,0) }
        tap("tab_user");waitTag("daily_pie_YUAN")
        rule.onNodeWithTag("share_BREAKFAST").assertTextEquals("¥1.00 · 10.0%")
        rule.onNodeWithTag("share_OTHER").assertTextEquals("¥4.00 · 40.0%")
        rule.onNodeWithTag("chart_colors").performScrollTo().performClick()
        rule.onNodeWithTag("color_BREAKFAST").performTextClearance()
        rule.onNodeWithTag("color_BREAKFAST").performTextInput("#112233")
        tap("save_chart_colors")
        val store=PersonalSchedule(app,owner.id)
        rule.waitUntil(10000) { store.chartColors().first()=="#112233" }
        assertEquals(ChartColors.defaults.drop(1),store.chartColors().drop(1))
        assertEquals(ChartColors.defaults,PersonalSchedule(app,owner.id+100000).chartColors())
        rule.onNodeWithTag("chart_colors").performScrollTo().performClick()
        rule.onNodeWithTag("color_LUNCH").performTextClearance()
        rule.onNodeWithTag("color_LUNCH").performTextInput("oops")
        rule.onNodeWithTag("save_chart_colors").assertIsNotEnabled()
        rule.onNodeWithText("取消").performClick()
        assertEquals(ChartColors.defaults[1],store.chartColors()[1])
        assertEquals(100L,runBlocking { app.database.notes().queryByIdAndUser(todayId,owner.id)!!.breakfast })
    }
    @Test fun zeroAndMixedCurrencyPiesHandleEachDenominatorSeparately() {
        tap("tab_user");waitTag("pie_empty")
        rule.onNodeWithTag("daily_pie_YUAN").assertDoesNotExist()
        runBlocking { app.database.notes().updateByIdAndUser(todayId,owner.id,100,200,300,400,true,0,otherCurrency=MoneyUnit.DOLLAR.name) }
        waitTag("daily_pie_YUAN");waitTag("daily_pie_DOLLAR")
        rule.onNodeWithTag("share_DINNER").assertTextEquals("¥3.00 · 50.0%")
        rule.onNodeWithTag("share_OTHER").assertTextEquals("$4.00 · 100.0%")
        rule.onNodeWithTag("chart_YUAN_${today.minusDays(1)}").performScrollTo().performClick()
        rule.onNodeWithTag("daily_pie_YUAN").assertDoesNotExist()
        rule.onNodeWithTag("daily_pie_DOLLAR").assertDoesNotExist()
    }
}
