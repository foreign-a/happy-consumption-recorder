package com.happy.consumption.ui
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.happy.consumption.*
import com.happy.consumption.data.*
import com.happy.consumption.ui.theme.HappyConsumptionTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.time.*
import java.io.File
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry

class RecordFlowTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as HappyApplication
    @Before fun language() { runBlocking { app.language.select("zh") }; rule.waitUntil(15000) { rule.activity.resources.configuration.locales[0].language=="zh" } }
    private fun expect(text:String) { rule.waitUntil(15000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text:String) { rule.onNodeWithText(text).performClick() }
    private fun shot(name:String) {
        val dir=File(app.filesDir,"inline-evidence").apply { mkdirs() }
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun automaticTodayBatchBlankAddAndDeleteRemainValid() {
        val owner=runBlocking {
            app.userManager.logout();val name="records-${System.nanoTime()}"
            app.userManager.register(name,"records-test-only");app.userManager.login(name,"records-test-only");app.userManager.session.value!!
        }
        expect("共 1 条")
        val today=LocalDate.now();val original=runBlocking { app.database.notes().queryByUserAndDate(owner.id,today.toString())!! }
        assertEquals(0L,original.breakfast+original.lunch+original.dinner+original.other)
        rule.activityRule.scenario.recreate();expect("共 1 条")
        click("添加过去记录");click("选择日期")
        val past=today.minusDays(2)
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("date_picker"))).performTextReplacement(past.toString())
        click("确定日期")
        rule.onNodeWithTag("add_currency_OTHER_DOLLAR").performScrollTo().performClick()
        click("保存");expect("共 2 条")
        val manual=runBlocking { app.database.notes().queryByUserAndDate(owner.id,past.toString())!! }
        assertTrue(manual.isManual);assertEquals(0L,manual.other);assertEquals(MoneyUnit.DOLLAR,manual.toItem().currencies.other)
        rule.onNodeWithTag("generate_history").performClick();expect("共 32 条")
        assertEquals(original,runBlocking { app.database.notes().queryByIdAndUser(original.id,owner.id) })
        rule.onNode(hasText("删除") and hasAnyAncestor(hasTestTag("record_${original.id}"))).performClick()
        click("取消");assertNotNull(runBlocking { app.database.notes().queryByIdAndUser(original.id,owner.id) })
        rule.onNode(hasText("删除") and hasAnyAncestor(hasTestTag("record_${original.id}"))).performClick()
        click("确认删除");expect("共 31 条")
    }
    @Test fun readErrorShowsRetryAndBlocksSaving() {
        val snapshot=RecordSnapshot(listOf(ConsumptionItem(99,1,"2024-02-29",1,2,3,4,true,1)),1)
        val fake=object:RecordsRepository{
            var fails=true;var writes=0
            override val session=UserSession(1,"故障验收",1)
            override val dates=DatePolicy(Clock.systemDefaultZone())
            override val changes=emptyFlow<Unit>()
            override suspend fun recordDates(start: String, end: String) = snapshot(1).records.map { it.date }.filter { it >= start && it <= end }.toSet()
        override suspend fun positionOf(date: String): Int? = snapshot(1).records.indexOfFirst { it.date == date }.takeIf { it >= 0 }
        override suspend fun snapshot(pages:Int):RecordSnapshot{if(fails)throw IOException("read failure");return snapshot}
            override suspend fun generatePastThirtyDays(): Int = 30
        override suspend fun ensureToday():Boolean{if(fails)throw IOException("read failure");return false}
            override suspend fun add(date: String, amounts: Amounts, currencies: EntryCurrencies?){writes++}
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
