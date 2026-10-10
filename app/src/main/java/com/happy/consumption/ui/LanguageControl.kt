package com.happy.consumption.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.happy.consumption.R
import com.happy.consumption.data.LanguageSettings
import kotlinx.coroutines.launch

@Composable fun LanguageControl(settings: LanguageSettings) {
    var choosing by remember { mutableStateOf(false) }
    val tag by settings.tag.collectAsState()
    val failed by settings.failed.collectAsState()
    val busy by settings.busy.collectAsState()
    val scope = rememberCoroutineScope()
    TextButton(onClick = { choosing = true }, enabled = !busy, modifier = Modifier.testTag("language_control")) { Text(stringResource(R.string.language)) }
    if (choosing) AlertDialog(onDismissRequest = { if (!busy) choosing = false },
        title = { Text(stringResource(R.string.language)) },
        text = { Column {
            for ((code, label) in listOf("zh" to R.string.language_zh, "en" to R.string.language_en, "ja" to R.string.language_ja)) {
                TextButton(onClick = { scope.launch { settings.select(code); if (!settings.failed.value) choosing = false } },
                    enabled = !busy, modifier = Modifier.testTag("language_$code")) {
                    RadioButton(selected = code == tag, onClick = null)
                    Text(stringResource(label))
                }
            }
            if (failed) Text(stringResource(R.string.storage_error), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { choosing = false }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}
