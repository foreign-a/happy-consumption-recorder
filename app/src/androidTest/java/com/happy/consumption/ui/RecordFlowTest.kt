package com.happy.consumption.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.happy.consumption.*
import com.happy.consumption.data.*
import com.happy.consumption.ui.theme.HappyConsumptionTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.*
import android.graphics.Bitmap

class RecordFlowTest {
    @org.junit.Before fun useChineseForFixedTextAssertions() {
        runBlocking { (rule.activity.application as HappyApplication).language.select("zh") }
        rule.waitUntil(15000) { rule.activity.resources.configuration.locales[0].language == "zh" }
        rule.waitForIdle()
    }

    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as HappyApplication
    private fun expect(text:String){rule.waitUntil(15000){rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun click(text:String){rule.onNodeWithText(text).performClick()}
    private fun fill(label:String,value:String){rule.onNodeWithText(label).performTextReplacement(value)}
    private fun shot(name:String){
        rule.waitForIdle();android.os.SystemClock.sleep(350)
        val dir=File(app.filesDir,"phase03-evidence").apply{mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun enterPicker(date:LocalDate){
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("date_picker"))).performTextReplacement(date.toString())
    }
    private fun chooseDate(date:LocalDate){click("选择日期");enterPicker(date);rule.onNodeWithText("确定日期").assertIsEnabled().performClick()}
    private fun action(id:Long,text:String){
        val owner=app.userManager.session.value!!.id
        val index=runBlocking{app.database.notes().queryByUserPaged(owner,200,0).indexOfFirst{it.id==id}}
        rule.onNodeWithTag("record_list").performScrollToIndex(index)
        rule.waitForIdle()
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("record_$id"))).performClick()
    }
    private fun editAmounts(){fill("早餐","0.01");fill("午餐","9999.99");fill("晚餐","0");fill("其他","1.00")}
    @Test fun autoConfirmationManualDateAndDelete() {
        val username="流程验收${System.currentTimeMillis()}"
        runBlocking{app.userManager.logout();app.userManager.register(username,"record-test-only");app.userManager.login(username,"record-test-only")}
        expect("当前用户：$username");expect("共 1 条")
        val today=LocalDate.now();val id=app.userManager.session.value!!.id
        val original=runBlocking{app.database.notes().queryByUserAndDate(id,today.toString())!!}
        assertFalse(original.isManual);assertEquals(0L,original.breakfast+original.lunch+original.dinner+original.other)
        shot("01-auto-today")
        rule.activityRule.scenario.recreate();expect("共 1 条")
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED);rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);expect("共 1 条")
        assertEquals(original,runBlocking{app.database.notes().queryByUserAndDate(id,today.toString())})
        action(original.id,"修改");expect("留空保留原金额；输入 0 可归零");rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().forEach { assertEquals("", it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text) };shot("02-blank-edit");click("取消")
        rule.onNodeWithText("早餐").assertDoesNotExist();assertEquals(original,runBlocking{app.database.notes().queryByIdAndUser(original.id,id)})
        action(original.id,"修改");editAmounts();click("保存");expect("确认更改数据？");shot("03-second-confirm");click("取消")
        assertEquals(original,runBlocking{app.database.notes().queryByIdAndUser(original.id,id)})
        action(original.id,"修改");editAmounts();click("保存");click("确认保存")
        rule.waitUntil(10000){runBlocking{app.database.notes().queryByIdAndUser(original.id,id)!!.isManual}}
        val changed=runBlocking{app.database.notes().queryByIdAndUser(original.id,id)!!}
        assertEquals(original.id,changed.id);assertEquals(original.date,changed.date);assertEquals(original.userId,changed.userId)
        assertTrue(changed.updatedAt>original.updatedAt);assertEquals(1L,changed.breakfast);assertEquals(999999L,changed.lunch)
        shot("04-modified-manual")
        click("添加过去记录");expect("日期：$today");click("保存");expect("该日期已有记录");shot("05-blank-add-duplicate-today")
        val past=today.minusDays(2);chooseDate(past)
        fill("午餐","0");fill("晚餐","0");fill("其他","0")
        for(value in listOf("-0.01","10000.00","1.001")){fill("早餐",value);click("保存");expect("金额须为 0 至 9999.99，最多两位小数")}
        fill("早餐","0.01");rule.onNodeWithText("保存").performTouchInput{doubleClick()};expect("共 2 条")
        val manual=runBlocking{app.database.notes().queryByUserAndDate(id,past.toString())!!};assertTrue(manual.isManual);assertEquals(1L,manual.breakfast)
        shot("06-manual-added")
        click("添加过去记录");chooseDate(past);fill("早餐","1");fill("午餐","1");fill("晚餐","1");fill("其他","1")
        click("保存");expect("该日期已有记录");shot("07-duplicate-date");click("取消")
        assertEquals(manual,runBlocking{app.database.notes().queryByIdAndUser(manual.id,id)})
        click("添加过去记录");click("选择日期");enterPicker(today.plusDays(1));rule.onNodeWithText("确定日期").assertIsNotEnabled();shot("08-future-rejected")
        rule.onAllNodesWithText("取消").onLast().performClick();click("取消")
        action(manual.id,"删除");expect("是否继续操作？");shot("09-delete-confirm");click("取消");expect("共 2 条")
        assertNotNull(runBlocking{app.database.notes().queryByIdAndUser(manual.id,id)})
        action(manual.id,"删除");click("确认删除");expect("共 1 条")
        assertNull(runBlocking{app.database.notes().queryByIdAndUser(manual.id,id)})
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED);rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);expect("共 1 条")
        assertEquals(changed,runBlocking{app.database.notes().queryByIdAndUser(original.id,id)})
        shot("10-final-record")
        File(app.filesDir,"phase03-evidence/record-flow-result.json").writeText(org.json.JSONObject().put("userId",id)
            .put("today",today.toString()).put("stableId",original.id).put("oldUpdatedAt",original.updatedAt).put("newUpdatedAt",changed.updatedAt)
            .put("editAndFinalCancelPreserved",true).put("manualDuplicatePreserved",true).put("futureRejected",true)
            .put("doubleSaveSingleRecord",true).put("deletePhysical",true).put("finalCount",1).toString())
    }
    @Test fun readErrorShowsRetryAndBlocksSaving() {
        val snapshot=RecordSnapshot(listOf(ConsumptionItem(99,1,"2024-02-29",1,2,3,4,true,1)),1)
        val fake=object:RecordsRepository{
            var fails=true;var writes=0
            override val session=UserSession(1,"故障验收",1)
            override val dates=DatePolicy(Clock.systemDefaultZone())
            override val changes=emptyFlow<Unit>()
            override suspend fun snapshot(pages:Int):RecordSnapshot{if(fails)throw IOException("read failure");return snapshot}
            override suspend fun generatePastThirtyDays(): Int = 30
        override suspend fun ensureToday():Boolean{if(fails)throw IOException("read failure");return false}
            override suspend fun add(date:String,amounts:Amounts){writes++}
            override suspend fun modify(permit:ModificationPermit){writes++}
            override suspend fun delete(permit:DeletionPermit){writes++}
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            val vm=AllRecordsViewModel(fake,scope);val auth=AppViewModel(app.userManager)
            rule.runOnUiThread{rule.activity.setContent{HappyConsumptionTheme{AllRecordsScreen(vm,"故障验收",auth)}}}
            expect("无法读取或保存数据，请重试");rule.onNodeWithText("还没有消费记录").assertDoesNotExist()
            rule.onNodeWithText("添加过去记录").assertDoesNotExist();shot("11-read-error")
            vm.add("2024-01-01","1","2","3","4");assertEquals(0,fake.writes)
            fake.fails=false;click("重试");expect("共 1 条");expect("2024-02-29");shot("12-read-retried")
            assertEquals(snapshot.records,vm.state.value.records);assertEquals(0,fake.writes)
        } finally {scope.cancel()}
    }
}
