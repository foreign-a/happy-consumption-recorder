package com.happy.consumption.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*

@Composable fun EntryPreferencesControl(store: PersonalSchedule, model: AllRecordsViewModel) {
    val current by model.entryPreferences.collectAsState()
    var failed by remember { mutableStateOf(false) }
    fun select(value: EntryPreferences) {
        if (store.saveEntryPreferences(value)) { model.configureEntry(value); failed = false } else failed = true
    }
    Text(stringResource(R.string.entry_mode_title), style = MaterialTheme.typography.titleLarge)
    for (mode in EntryMode.entries) Row(Modifier.fillMaxWidth().clickable { select(current.copy(mode = mode)) }
        .testTag("entry_mode_${mode.name}"), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = current.mode == mode, onClick = null)
        Text(stringResource(if (mode == EntryMode.REPLACE) R.string.entry_replace_option else R.string.entry_adjust_option))
    }
    Text(stringResource(R.string.entry_adjust_description), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.confirmation_plan_title), style = MaterialTheme.typography.titleLarge)
    for (plan in ConfirmationPlan.entries) Row(Modifier.fillMaxWidth().clickable { select(current.copy(confirmation = plan)) }
        .testTag("confirmation_${plan.name}"), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = current.confirmation == plan, onClick = null)
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(stringResource(if (plan == ConfirmationPlan.QUICK) R.string.confirm_quick else R.string.confirm_careful))
            Text(stringResource(if (plan == ConfirmationPlan.QUICK) R.string.quick_description else R.string.careful_description),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (failed) Text(stringResource(R.string.storage_error), color = MaterialTheme.colorScheme.error)
    HorizontalDivider()
}
