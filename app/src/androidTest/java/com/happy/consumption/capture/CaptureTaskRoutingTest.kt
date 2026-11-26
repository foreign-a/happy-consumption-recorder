package com.happy.consumption.capture

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureTaskRoutingTest {
    @Test fun screenshotTasksAreIsolatedAndReturnDoesNotClearAppTasks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = gatewayIntent(context, "routing-test", 123L)
        val preview = previewIntent(context, "routing-test")
        val main = screenshotMainIntent(context)
        val newTask = Intent.FLAG_ACTIVITY_NEW_TASK
        val multiple = Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        for (intent in listOf(gateway, preview)) {
            assertEquals(newTask or multiple, intent.flags and (newTask or multiple))
            assertEquals("routing-test", intent.getStringExtra(CAPTURE_REQUEST_ID))
        }
        assertEquals(123L, gateway.getLongExtra(CAPTURE_STARTED_AT, -1))
        assertNotEquals(0, accessibilitySettingsIntent().flags and newTask)
        assertNotEquals(0, main.flags and newTask)
        assertNotEquals(0, main.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
        assertEquals(0, main.flags and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK))

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val gatewayInfo = pm.getActivityInfo(ComponentName(context, CaptureGatewayActivity::class.java), 0)
        @Suppress("DEPRECATION")
        val previewInfo = pm.getActivityInfo(ComponentName(context, CapturePreviewActivity::class.java), 0)
        assertFalse(gatewayInfo.exported)
        assertFalse(previewInfo.exported)
        assertEquals(context.packageName + ".capture.gateway", gatewayInfo.taskAffinity)
        assertEquals(context.packageName + ".capture.preview", previewInfo.taskAffinity)
        assertNotEquals(gatewayInfo.taskAffinity, previewInfo.taskAffinity)
    }
}
