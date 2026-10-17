package com.happy.consumption.ui
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.accessibilityservice.AccessibilityService
import com.happy.consumption.*
import com.happy.consumption.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.io.File
import android.graphics.Bitmap

class Version12UiTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as HappyApplication
    private lateinit var original: NoteEntity
    private lateinit var owner: UserSession
    @Before fun setup() {
        runBlocking {
            app.language.select("zh");app.userManager.logout()
            val name="inline-${System.nanoTime()}";app.userManager.register(name,"inline-test-only");app.userManager.login(name,"inline-test-only")
            owner=app.userManager.session.value!!
            UserRepository(app.userManager,app.database,owner).ensureToday()
            val row=app.database.notes().queryByUserAndDate(owner.id,LocalDate.now().toString())!!
            app.database.notes().updateByIdAndUser(row.id,owner.id,900,0,0,500,true,1000)
            original=app.database.notes().queryByIdAndUser(row.id,owner.id)!!
        }
        expect("¥14.00")
    }
    private fun expect(text:String) { rule.waitUntil(15000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun amount(meal:Meal) { rule.onNodeWithTag("amount_${original.id}_${meal.name}").performClick();rule.onNodeWithTag("phone_keypad").assertExists() }
    private fun key(value:String) { rule.onNodeWithTag("key_$value").performClick() }
    private fun row()=runBlocking { app.database.notes().queryByIdAndUser(original.id,owner.id)!! }
    private fun outside() { rule.onNodeWithText("全部记录").performClick();expect("还有未确认的更改") }
    private fun shot(name:String) {
        val dir=File(app.filesDir,"inline-evidence").apply { mkdirs() }
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun tapAmountPhoneKeysAndMainConfirmSaveWithoutSecondDialog() {
        amount(Meal.OTHER);key("5")
        rule.onNodeWithTag("entry_currency_DOLLAR").performClick();shot("01-keypad-mixed-draft")
        assertEquals(original,row())
        rule.onNodeWithTag("confirm_${original.id}").performClick();expect("¥9.00 + $5.00")
        rule.onNodeWithText("确认更改数据？").assertDoesNotExist();rule.onNodeWithTag("phone_keypad").assertDoesNotExist()
        assertEquals(500L,row().other);assertEquals(MoneyUnit.DOLLAR,row().toItem().currencies.other)
        assertEquals(900L,row().breakfast);shot("02-mixed-saved")
        val saved=row();rule.onNodeWithTag("tab_user").performClick();rule.onNodeWithTag("currency_control").performClick();rule.onNodeWithTag("currency_POUND").performClick()
        rule.waitForIdle();assertEquals(saved,row())
    }
    @Test fun outsideTapOffersKeepDiscardAndSave() {
        amount(Meal.BREAKFAST);key("2");outside();shot("03-unsaved-prompt")
        rule.onNodeWithTag("leave_keep").performClick();rule.onNodeWithTag("phone_keypad").assertExists();assertEquals(original,row())
        outside();rule.onNodeWithTag("leave_discard").performClick();assertEquals(original,row())
        amount(Meal.OTHER);rule.onNodeWithTag("entry_currency_DOLLAR").performClick()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        expect("还有未确认的更改");rule.onNodeWithTag("leave_save").performClick();expect("¥9.00 + $5.00")
        assertEquals(MoneyUnit.DOLLAR,row().toItem().currencies.other)
    }
    @Test fun invalidInputPreservesDraftAndRotationRetainsIt() {
        amount(Meal.BREAKFAST)
        for(key in "10000") key(key.toString())
        rule.onNodeWithTag("keypad_confirm").performClick();expect("金额须为 0 至 9999.99，最多两位小数")
        assertEquals(original,row());rule.onNodeWithTag("phone_keypad").assertExists()
        repeat(5) { key("backspace") };key("0");key(".");key("0");key("1")
        rule.activityRule.scenario.recreate();rule.onNodeWithTag("phone_keypad").assertExists()
        rule.onNodeWithTag("keypad_confirm").performClick()
        rule.waitUntil(15000) { row().breakfast==1L }
        assertEquals(original.other,row().other)
    }
}
