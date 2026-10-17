package com.happy.consumption.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.happy.consumption.HappyApplication
import com.happy.consumption.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap

class AccountFlowTest {
    @org.junit.Before fun useChineseForFixedTextAssertions() {
        runBlocking { (rule.activity.application as HappyApplication).language.select("zh") }
        rule.waitUntil(15000) { rule.activity.resources.configuration.locales[0].language == "zh" }
        rule.waitForIdle()
    }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as HappyApplication
    private fun expect(text: String) {
        rule.waitUntil(15000) { try { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false } }
    }
    private fun click(text: String) {
        if (text in listOf("登出", "删除用户") && rule.onAllNodesWithTag("tab_user").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("tab_user").performClick()
        rule.onNodeWithText(text).performClick()
        if (text == "取消" && rule.onAllNodesWithTag("user_page").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("tab_home").performClick()
    }
    private fun fill(label: String, value: String) {
        rule.waitUntil(10000) {
            try { rule.onAllNodesWithText(label).onLast().performTextReplacement(value); true }
            catch (e: AssertionError) { if (e.message?.contains("no longer in the tree") == true) false else throw e }
        }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(350)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = File(app.filesDir, "phase02-evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun register(name: String, password: String) {
        click("创建新用户"); fill("用户名", name); fill("密码", password); fill("确认密码", password)
        click("创建"); expect("创建成功，请登录")
    }
    private fun login(name: String, password: String) {
        fill("用户名", name); fill("密码", password); click("登录"); expect("当前用户：$name")
    }
    private fun add(date: String, amount: String) {
        click("添加过去记录"); chooseDate(date); fill("早餐", amount)
        fill("午餐", "0"); fill("晚餐", "0"); fill("其他", "0")
        click("保存"); expect(date)
    }
    private fun chooseDate(date: String) {
        click("选择日期")
        rule.waitUntil(10000) { try { rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("date_picker"))).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false } }
        rule.waitForIdle();android.os.SystemClock.sleep(350)
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("date_picker"))).performTextReplacement(date)
        rule.onNodeWithText("确定日期").assertIsEnabled().performClick()
    }
    @Test fun registerIsolateDeleteAndRestore() {
        runBlocking { app.userManager.logout() }
        expect("创建新用户")
        val suffix = System.currentTimeMillis().toString()
        val a = "验收A$suffix"; val b = "验收B$suffix"
        val password = "phase02-test-only"
        screenshot("01-login")
        click("创建新用户"); fill("用户名", a); fill("密码", "123"); fill("确认密码", "123")
        click("创建"); expect("密码至少四位"); screenshot("02-short-password")
        fill("密码", password); fill("确认密码", "different"); click("创建"); expect("两次密码不一致")
        fill("确认密码", password); click("创建"); expect("创建成功，请登录")
        click("创建新用户"); fill("用户名", a); fill("密码", password); fill("确认密码", password)
        click("创建"); expect("用户名已存在"); screenshot("03-duplicate"); click("取消")
        fill("用户名", a); fill("密码", "wrong-password"); click("登录")
        expect("用户名或密码错误"); screenshot("04-wrong-password")
        fill("密码", password); click("显示"); expect("隐藏"); click("隐藏")
        click("登录"); expect("当前用户：$a")
        expect("共 1 条"); add("2024-01-01", "12.34"); expect("共 2 条")
        add("2024-01-02", "9999.99"); expect("共 3 条"); screenshot("05-a-records")
        rule.onNodeWithTag("record_list").performScrollToIndex(2); screenshot("05a-a-scrolled")
        val aId = app.userManager.session.value!!.id
        assertTrue(File(app.filesDir, "users/$aId").isDirectory)
        click("登出"); expect("创建新用户"); register(b, password); login(b, password)
        expect("共 1 条"); rule.onNodeWithText("2024-01-01").assertDoesNotExist()
        screenshot("06-b-empty")
        val bId = app.userManager.session.value!!.id
        add("2024-01-01", "1.00"); expect("共 2 条"); screenshot("07-b-record")
        click("删除用户"); expect(rule.activity.getString(com.happy.consumption.R.string.delete_user_warning)); screenshot("08-delete-confirm")
        click("取消"); expect("当前用户：$b"); expect("共 2 条")
        click("删除用户"); rule.onNodeWithText("确认删除").assertIsNotEnabled()
        fill("当前密码", "wrong"); click("确认删除"); expect("密码验证失败，账号未删除")
        assertEquals(bId, app.userManager.session.value!!.id)
        screenshot("08a-delete-password-rejected")
        fill("当前密码", password); click("确认删除"); expect("创建新用户")
        runBlocking {
            assertEquals(0, app.database.notes().countByUserOnce(bId))
            assertEquals(0, app.database.identities().countByUser(bId))
        }
        assertNull(app.userManager.session.value); assertFalse(File(app.filesDir, "users/$bId").exists())
        fill("用户名", b); fill("密码", password); click("登录"); expect("用户名或密码错误")
        screenshot("09-deleted-b-rejected")
        login(a, password); expect("共 3 条"); screenshot("10-a-restored")
        runBlocking { assertEquals(3, app.database.notes().countByUserOnce(aId)) }
        File(app.filesDir, "phase02-evidence/ui-result.txt").writeText(
            "PASS: registration, wrong password, duplicate username, A auto 1 + manual 2, B only own auto 1 + manual 1, deletion cancelled then confirmed, B physical rows=0 and identity=0 and directory absent, A restored 3\nA_ID=$aId\nB_ID=$bId\nA_USERNAME=$a\n")
    }
}
