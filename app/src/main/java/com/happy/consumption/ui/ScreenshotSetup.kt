package com.happy.consumption.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.happy.consumption.R

@Composable
fun ScreenshotSetup() {
    var open by remember { mutableStateOf(false) }
    var licensesOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.screenshot_setup_title)) }
    TextButton(onClick = { licensesOpen = true }) { Text(stringResource(R.string.screenshot_licenses)) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.screenshot_setup_title)) },
        text = { Text(stringResource(if (Build.VERSION.SDK_INT >= 30) R.string.screenshot_setup_instructions else R.string.screenshot_setup_unsupported)) },
        confirmButton = {
            if (Build.VERSION.SDK_INT >= 30) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); open = false
            }) { Text(stringResource(R.string.screenshot_setup_accessibility)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } }
    )
    if (licensesOpen) {
        val text = remember {
            listOf("NOTICE.txt", "apache-2.0.txt", "leptonica.txt", "libpng.txt", "ijg-readme.txt").joinToString("\n\n") { name ->
                "$name\n\n" + context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(onDismissRequest = { licensesOpen = false }, title = { Text(stringResource(R.string.screenshot_licenses)) },
            text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { licensesOpen = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
