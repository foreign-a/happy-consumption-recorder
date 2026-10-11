package com.happy.consumption

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
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

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            if (localesReady) AppScreen(app)
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
}
