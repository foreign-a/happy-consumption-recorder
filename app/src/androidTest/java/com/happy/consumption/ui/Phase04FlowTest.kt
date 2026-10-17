package com.happy.consumption.ui

import androidx.activity.compose.setContent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.happy.consumption.*
import com.happy.consumption.R
import com.happy.consumption.data.*
import com.happy.consumption.util.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate
import android.graphics.Bitmap

class Phase04FlowTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as HappyApplication
    private fun expect(text:String) { rule.waitUntil(20000) { try { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } catch (_:IllegalStateException) { false } } }
    private fun text(id:Int)=rule.activity.getString(id)
    private fun click(id:Int){rule.onNodeWithText(text(id)).performClick()}
    private fun shot(name:String){
        rule.waitForIdle();android.os.SystemClock.sleep(250)
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(app.filesDir,"phase04-evidence").apply{mkdirs()}
        File(dir,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun language(code:String){
        val loggedIn = rule.onAllNodesWithTag("tab_user").fetchSemanticsNodes().isNotEmpty()
        if (loggedIn) rule.onNodeWithTag("tab_user").performClick()
        rule.onNodeWithTag("language_control").performClick()
        rule.onNodeWithTag("language_$code").performClick()
        rule.waitUntil(15000){rule.activity.resources.configuration.locales[0].language==code}
        rule.waitForIdle()
        if (loggedIn) rule.onNodeWithTag("tab_home").performClick()
    }
    @Test fun threeLanguagesPreserveSessionPagingAndPosition() {
        runBlocking { app.language.select("zh");app.userManager.logout() }
        expect("创建新用户")
        for(code in listOf("en","ja","zh")){
            language(code);expect(text(R.string.create_user));shot("$code-login")
            click(R.string.create_user);expect(text(R.string.confirm_password));shot("$code-register");click(R.string.cancel)
        }
        val name="语言验收${System.currentTimeMillis()}"
        val session=runBlocking {
            app.userManager.register(name,"phase04-test-only");app.userManager.login(name,"phase04-test-only")
            val active=app.userManager.session.value!!
            val start=LocalDate.parse("2024-01-01")
            for(i in 0 until 401) app.database.notes().insertIfAbsent(NoteEntity(userId=active.id,date=start.plusDays(i.toLong()).toString(),breakfast=1,lunch=1234,dinner=0,other=0,isManual=true,updatedAt=0))
            active
        }
        expect(rule.activity.getString(R.string.record_count,402));expect(rule.activity.getString(R.string.page_progress,200,402))
        rule.onNodeWithTag("record_list").performScrollToIndex(199)
        expect(rule.activity.getString(R.string.page_progress,400,402))
        rule.onNodeWithTag("record_list").performScrollToIndex(240)
        val position=rule.onNodeWithTag("record_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        for(code in listOf("en","ja","zh")){
            language(code);expect(text(R.string.all_records))
            expect(rule.activity.getString(R.string.page_progress,400,402))
            assertEquals(session,app.userManager.session.value)
            val after=rule.onNodeWithTag("record_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals(position,after,1f)
            rule.onAllNodesWithText("¥12.35").onFirst().assertExists();shot("$code-list-position")
            click(R.string.add_past_record)
            rule.onNode(hasText(text(R.string.breakfast)) and hasSetTextAction()).performTextReplacement("-1")
            click(R.string.save);expect(text(R.string.invalid_amount));shot("$code-amount-error")
            click(R.string.select_date);expect(text(R.string.date_confirm));shot("$code-date-picker")
            rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("date_picker"))).assertTextContains(LocalDate.now().toString())
            rule.onAllNodesWithText(text(R.string.cancel)).onLast().performClick();click(R.string.cancel)
            rule.onNodeWithTag("tab_user").performClick();click(R.string.delete_user);expect(text(R.string.current_password));shot("$code-delete-password");click(R.string.cancel);rule.onNodeWithTag("tab_home").performClick()
        }
        runBlocking {
            assertEquals(402,app.database.notes().countByUserOnce(session.id))
            val repo=UserRepository(app.userManager,app.database,session)
            repo.queryByDate(session.id,"2024-01-01")
            val item=repo.queryByDate(session.id,"2024-01-01")!!.toItem()
            val confirm=RecordConfirmation(session);confirm.requestModify(item);confirm.review(Amounts(1,1234,0,0));repo.modify(confirm.confirmModify())
            confirm.requestDelete(item);repo.delete(confirm.confirmDelete())
            assertFalse(repo.ensureToday())
            try{repo.add("2999-01-01",Amounts(0,0,0,0));fail()}catch(_:UserOperationException){}
            LogHelper.error("Acceptance","Injected recovery test",NullPointerException("phase04-test-only"))
            LogHelper.flush()
        }
        val out=File(app.filesDir,"phase04-evidence")
        File(out,"language-result.json").writeText(org.json.JSONObject().put("userId",session.id).put("generation",session.generation)
            .put("loadedPages",2).put("position",position).put("languages",3).put("sessionPreserved",true).put("positionPreserved",true)
            .put("formatStable",true).put("finalCount",401).toString())
        for(code in listOf("en","ja","zh")) {
            language(code)
            val fake=object:RecordsRepository {
                var fails=true
                override val session: UserSession = app.userManager.session.value!!
                override val dates=DatePolicy(java.time.Clock.systemDefaultZone())
                override val changes=kotlinx.coroutines.flow.emptyFlow<Unit>()
                override suspend fun recordDates(start: String, end: String) = snapshot(1).records.map { it.date }.filter { it >= start && it <= end }.toSet()
        override suspend fun positionOf(date: String): Int? = snapshot(1).records.indexOfFirst { it.date == date }.takeIf { it >= 0 }
        override suspend fun snapshot(pages:Int):RecordSnapshot { if(fails)throw NullPointerException("phase04-test-only");return RecordSnapshot(emptyList(),0) }
                override suspend fun generatePastThirtyDays(): Int = 30
        override suspend fun ensureToday():Boolean { if(fails)throw NullPointerException("phase04-test-only");return false }
                override suspend fun add(date: String, amounts: Amounts, currencies: EntryCurrencies?){fail("saved while unreadable")}
                override suspend fun modify(permit:ModificationPermit){fail("modified while unreadable")}
                override suspend fun delete(permit:DeletionPermit){fail("deleted while unreadable")}
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val vm=AllRecordsViewModel(fake,scope)
            val auth=androidx.lifecycle.ViewModelProvider(rule.activity)[AppViewModel::class.java]
            rule.runOnUiThread { rule.activity.setContent { com.happy.consumption.ui.theme.HappyConsumptionTheme { AllRecordsScreen(vm,session.username,auth,app.language) } } }
            expect(text(R.string.storage_error));shot("$code-read-error")
            rule.onNodeWithText(text(R.string.add_past_record)).assertDoesNotExist()
            fake.fails=false;click(R.string.retry);expect(text(R.string.empty_records));shot("$code-read-retry")
            scope.cancel()
            rule.runOnUiThread { rule.activity.setContent { com.happy.consumption.ui.theme.HappyConsumptionTheme { AppScreen(app) } } }
            rule.waitUntil(15000) { rule.onAllNodesWithTag("tab_home").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("tab_home").performClick()
            expect(text(R.string.all_records))
        }
        language("ja");shot("ja-final")
        language("zh");expect("全部记录")
    }
}
