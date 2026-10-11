package com.happy.consumption.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.happy.consumption.*
import com.happy.consumption.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Clock
import android.graphics.Bitmap

class DatasetSmokeTest {
    @org.junit.Before fun useChineseForFixedTextAssertions() {
        runBlocking { (rule.activity.application as HappyApplication).language.select("zh") }
        rule.waitUntil(15000) { rule.activity.resources.configuration.locales[0].language == "zh" }
        rule.waitForIdle()
    }

    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as HappyApplication
    private fun expect(text:String) { rule.waitUntil(20000){rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()} }
    @Test fun fullDatasetImportAndScrollPaging() {
        val result=runBlocking {
            app.userManager.logout()
            DebugDatasetImporter(app.userManager,app.database,Clock.systemDefaultZone()).import(
                app.assets.open("daily_consumption_testdata.json"),"数据验收${System.currentTimeMillis()}","dataset-test-only")
        }
        assertEquals(31104,result.imported);assertEquals(32,result.userMapping.size)
        runBlocking {
            var total=0
            for((sourceId,userId) in result.userMapping) {
                val rows=mutableListOf<NoteEntity>();var offset=0
                while(true){val page=app.database.notes().queryByUserPaged(userId,200,offset);rows.addAll(page);if(page.size<200)break;offset+=200}
                assertEquals(rows.size,rows.map{it.id}.toSet().size);assertTrue(rows.all{it.userId==userId})
                assertEquals(rows.size,app.database.notes().countByUserOnce(userId));total+=rows.size
                assertEquals(rows.size,rows.map{it.date}.toSet().size)
                for(row in rows.filterIndexed{index,_->index%97==0}) assertEquals(row,app.database.notes().queryByUserAndDate(userId,row.date))
                if(sourceId==1L) assertEquals(1011,rows.size)
                if(sourceId==24L) assertEquals(999999L,app.database.notes().queryByUserAndDate(userId,"2026-10-07")!!.dinner)
            }
            assertEquals(31104,total)
            app.userManager.login(result.usernames.getValue(1),"dataset-test-only")
        }
        expect("共 1012 条");expect("已加载 200 / 1012 条")
        for((index,loaded) in listOf(199 to 400,399 to 600,599 to 800,799 to 1000,999 to 1012)) {
            rule.onNodeWithTag("record_list").performScrollToIndex(index)
            expect("已加载 $loaded / 1012 条")
        }
        rule.onNodeWithTag("record_list").performScrollToIndex(1011)
        rule.onNodeWithText("2024-01-01").assertIsDisplayed()
        android.os.SystemClock.sleep(350)
        val out=File(app.filesDir,"phase03-evidence").apply{mkdirs()}
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(out,"dataset-last-page.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
        File(out,"dataset-result.json").writeText(org.json.JSONObject().put("imported",result.imported).put("users",32)
            .put("elapsedMillis",result.elapsedMillis).put("sourceUser1Rows",1011).put("UI_user1_count",1012)
            .put("pagesLoaded",6).put("noDuplicates",true).put("samplesValid",true)
            .put("mappedUser1",result.userMapping.getValue(1)).put("prefix",result.usernames.getValue(1).substringBeforeLast('-')).toString())
        runBlocking { app.userManager.logout() }
    }
}
