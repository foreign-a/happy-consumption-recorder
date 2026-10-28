package com.happy.consumption.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*

@Composable fun UserScreen(username: String, auth: AppViewModel, language: LanguageSettings?, backup: BackupViewModel?, schedule: PersonalSchedule? = null, records: AllRecordsViewModel? = null) {
    var deletingUser by remember { mutableStateOf(false) }
    var deletionPassword by remember { mutableStateOf("") }
    var deletionSession by remember { mutableStateOf<UserSession?>(null) }
    val authBusy by auth.busy.collectAsState()
    val authMessage by auth.message.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp).testTag("user_page"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.current_user, username), style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        if (schedule != null && records != null) {
            EntryPreferencesControl(schedule, records)
            ScheduleControl(schedule) { records.refreshToday() }
            MonthlyStatistics(records, schedule)
            HorizontalDivider()
        }
        if (backup != null) BackupControl(backup, loggedIn = true)
        if (language != null) LanguageControl(language)
        CurrencyControl(auth)
        HorizontalDivider()
        OutlinedButton(onClick = auth::logout, enabled = !authBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logout)) }
        OutlinedButton(onClick = { deletionSession = auth.session.value; deletionPassword = ""; auth.message.value = null; deletingUser = deletionSession != null }, enabled = !authBusy,
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete_user)) }
        if (authMessage != null && !deletingUser) Text(stringResource(authMessage!!), color = MaterialTheme.colorScheme.error)
    }
    if (deletingUser) AlertDialog(onDismissRequest = { if (!authBusy) { deletionPassword = ""; deletingUser = false } },
        title = { Text(stringResource(R.string.delete_user)) },
        text = { Column {
            Text(stringResource(R.string.delete_user_warning))
            OutlinedTextField(value = deletionPassword, onValueChange = { deletionPassword = it }, enabled = !authBusy,
                label = { Text(stringResource(R.string.current_password)) }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                modifier = Modifier.testTag("delete_password"))
            if (authMessage != null) Text(stringResource(authMessage!!), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { val entered = deletionPassword; deletionPassword = ""; deletionSession?.let { auth.deleteUser(it, entered) } }, enabled = !authBusy && deletionPassword.isNotEmpty()) { Text(stringResource(R.string.confirm_delete)) } },
        dismissButton = { TextButton(onClick = { deletionPassword = ""; auth.message.value = null; deletingUser = false }, enabled = !authBusy) { Text(stringResource(R.string.cancel)) } })
}
