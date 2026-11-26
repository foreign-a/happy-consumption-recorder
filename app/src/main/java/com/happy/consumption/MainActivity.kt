package com.happy.consumption

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import com.happy.consumption.util.LogHelper
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.happy.consumption.ui.AppScreen
import com.happy.consumption.ui.theme.HappyConsumptionTheme
import com.happy.consumption.capture.ScreenshotSelection
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private var screenshotSelection by mutableStateOf<ScreenshotSelection.Pending?>(null)
    private lateinit var screenshotOwner: String

    private fun receiveScreenshot() {
        if (screenshotSelection == null) screenshotSelection = ScreenshotSelection.claim(screenshotOwner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenshotOwner = savedInstanceState?.getString("screenshot_owner") ?: UUID.randomUUID().toString()
        val restoredId = savedInstanceState?.getString("screenshot_request_id")
        val restoredProcess = savedInstanceState?.getString("screenshot_process_id")
        if (restoredId != null && restoredProcess == ScreenshotSelection.processId)
            screenshotSelection = ScreenshotSelection.restore(screenshotOwner, restoredId)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ScreenshotSelection.changes.collect { receiveScreenshot() }
            }
        }
        enableEdgeToEdge()
        val app = application as HappyApplication
        var localesReady by mutableStateOf(false)
        lifecycleScope.launch {
            app.language.tag.collect { tag ->
                if (tag != null) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                    localesReady = true
                    val version = packageManager.getPackageInfo(packageName, 0).versionName
                    LogHelper.info("Startup", "SDK_INT=${android.os.Build.VERSION.SDK_INT} version=$version language=$tag")
                }
            }
        }
        setContent { HappyConsumptionTheme {
            if (localesReady) AppScreen(app, screenshotSelection) { id ->
                if (screenshotSelection?.id == id) {
                    screenshotSelection = null
                    ScreenshotSelection.complete(screenshotOwner, id)
                    receiveScreenshot()
                }
            }
            else {
                val failed by app.language.failed.collectAsState()
                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    if (failed) Column {
                        Text(stringResource(R.string.storage_error))
                        Button(onClick = { lifecycleScope.launch { app.language.load() } }) { Text(stringResource(R.string.retry)) }
                    } else CircularProgressIndicator()
                }
            }
        } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        receiveScreenshot()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screenshot_owner", screenshotOwner)
        screenshotSelection?.let {
            outState.putString("screenshot_request_id", it.id)
            outState.putString("screenshot_process_id", it.processId)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // A finished activity no longer owns its editable draft. Never requeue
        // its OCR initial value under a request ID that may already be committed.
        if (isFinishing && !isChangingConfigurations) ScreenshotSelection.abandon(screenshotOwner)
        super.onDestroy()
    }
}
