package com.happy.consumption.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.happy.consumption.HappyApplication
import com.happy.consumption.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File
import org.json.JSONObject

class Phase05PerformanceTest {
    @org.junit.Before fun useChineseForFixedTextAssertions() {
        runBlocking { (rule.activity.application as HappyApplication).language.select("zh") }
        rule.waitUntil(15000) { rule.activity.resources.configuration.locales[0].language == "zh" }
        rule.waitForIdle()
    }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun preserveDatasetSessionForMeasuredColdStarts() {
        val app = rule.activity.application as HappyApplication
        val imported = JSONObject(File(app.filesDir, "phase03-evidence/dataset-result.json").readText())
        runBlocking {
            app.userManager.logout()
            app.userManager.login(imported.getString("prefix") + "-1", "dataset-test-only")
            assertEquals(1012, app.database.notes().countByUserOnce(imported.getLong("mappedUser1")))
        }
        rule.waitUntil(20000) { rule.onAllNodesWithText("已加载 200 / 1012 条").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("record_list").performScrollToIndex(199)
        rule.waitUntil(20000) { rule.onAllNodesWithText("已加载 400 / 1012 条").fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20000) { rule.onAllNodesWithText("已加载 400 / 1012 条").fetchSemanticsNodes().isNotEmpty() }
        File(app.filesDir, "phase03-evidence/performance-session.json").writeText(JSONObject()
            .put("total", 1012).put("initialPage", 200).put("afterNextPage", 400)
            .put("recreatedPageCountRetained", true).put("userId", imported.getLong("mappedUser1")).toString())
    }
}
