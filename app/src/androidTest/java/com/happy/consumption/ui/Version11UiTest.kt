package com.happy.consumption.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.happy.consumption.*
import com.happy.consumption.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class Version11UiTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as HappyApplication
    private fun expect(text:String) { rule.waitUntil(15000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun blankEditingFinalConfirmationBatchAndThreeCurrencies() {
        val owner=runBlocking {
            app.language.select("zh"); app.userManager.logout()
            val name="v11-ui-${System.currentTimeMillis()}"
            app.userManager.register(name,"v11-ui-password"); app.userManager.login(name,"v11-ui-password")
            val session=app.userManager.session.value!!
            UserRepository(app.userManager,app.database,session).add("2024-03-01",Amounts(123,456,789,1000));session
        }
        expect("共 2 条")
        val original=runBlocking { app.database.notes().queryByUserAndDate(owner.id,"2024-03-01")!! }
        fun modify() {
            rule.onNodeWithTag("record_list").performScrollToIndex(1)
            rule.onNode(hasText("修改") and hasAnyAncestor(hasTestTag("record_${original.id}"))).performClick()
            expect("留空保留原金额；输入 0 可归零")
        }
        modify()
        rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().forEach { assertEquals("",it.config[SemanticsProperties.EditableText].text) }
        rule.onNodeWithText("是否继续操作？").assertDoesNotExist()
        rule.onNodeWithText("早餐").performTextReplacement("4")
        rule.onNodeWithText("保存").performClick(); expect("确认更改数据？")
        rule.onNodeWithText("取消").performClick()
        assertEquals(original,runBlocking { app.database.notes().queryByIdAndUser(original.id,owner.id) })
        modify(); rule.onNodeWithText("早餐").performTextReplacement("4")
        rule.onNodeWithText("保存").performClick(); rule.onNodeWithText("确认保存").performClick()
        rule.waitUntil(15000) { runBlocking { app.database.notes().queryByIdAndUser(original.id,owner.id)!!.breakfast==400L } }
        val changed=runBlocking { app.database.notes().queryByIdAndUser(original.id,owner.id)!! }
        assertEquals(original.lunch,changed.lunch); assertEquals(original.dinner,changed.dinner);assertEquals(original.other,changed.other)
        for(unit in listOf(MoneyUnit.DOLLAR,MoneyUnit.POUND,MoneyUnit.YUAN)) {
            rule.onNodeWithTag("currency_control").performClick()
            rule.onNodeWithTag("currency_${unit.name}").performClick();expect(unit.format(2645))
            assertEquals(changed,runBlocking { app.database.notes().queryByIdAndUser(original.id,owner.id) })
        }
        rule.onNodeWithTag("generate_history").performClick();expect("共 32 条");expect("已生成 30 条零金额记录")
        rule.activityRule.scenario.recreate();expect("共 32 条")
        assertEquals(changed,runBlocking { app.database.notes().queryByIdAndUser(original.id,owner.id) })
    }
}
