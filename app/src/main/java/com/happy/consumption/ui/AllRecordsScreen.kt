package com.happy.consumption.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.ConsumptionItem
import com.happy.consumption.data.ConfirmationState
import com.happy.consumption.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun AllRecordsScreen(model: AllRecordsViewModel, username: String, auth: AppViewModel, language: com.happy.consumption.data.LanguageSettings? = null, backup: BackupViewModel? = null) {
    val data by model.state.collectAsState()
    val records = data.records
    val count = data.count
    val list = rememberLazyListState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val generatedCount by model.generatedCount.collectAsState()
    val dialog by model.dialog.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var deletingUser by remember { mutableStateOf(false) }
    var deletionPassword by remember { mutableStateOf("") }
    var deletionSession by remember { mutableStateOf<com.happy.consumption.data.UserSession?>(null) }
    val authBusy by auth.busy.collectAsState()
    val authMessage by auth.message.collectAsState()
    LaunchedEffect(list, records.size, count, data.error) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (last >= records.size - 3 && last >= 0) model.loadMore()
        }
    }
    Scaffold(
        floatingActionButton = {
            if (!data.error)
            ExtendedFloatingActionButton(
                onClick = { if (!busy && model.dialog.value == ConfirmationState.Idle) { model.message.value = null; adding = true } },
                containerColor = PrimaryBlue, contentColor = Color.White,
            ) { Text(stringResource(R.string.add_past_record)) }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.current_user, username), Modifier.weight(1f))
                if (language != null) LanguageControl(language)
                TextButton(onClick = auth::logout, enabled = !authBusy) { Text(stringResource(R.string.logout)) }
                TextButton(onClick = { deletionSession = auth.session.value; deletionPassword = ""; auth.message.value = null; deletingUser = deletionSession != null }, enabled = !authBusy) { Text(stringResource(R.string.delete_user)) }
            }
            if (authMessage != null && !deletingUser) Text(stringResource(authMessage!!), color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CurrencyControl(auth)
                if (backup != null) BackupControl(backup, loggedIn = true)
            }
            if (message != null && !adding) Text(stringResource(message!!), color = MaterialTheme.colorScheme.error)
            if (data.error) {
                Text(stringResource(R.string.storage_error))
                Button(onClick = model::retry) { Text(stringResource(R.string.retry)) }
            } else {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.all_records), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                    Text(stringResource(R.string.record_count, count), Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                }
            }
            Text(stringResource(R.string.page_progress, records.size, count), Modifier.testTag("page_progress"))
            TextButton(onClick = model::generatePastThirtyDays, enabled = !busy && dialog == ConfirmationState.Idle,
                modifier = Modifier.testTag("generate_history")) { Text(stringResource(R.string.generate_history)) }
            if (generatedCount != null) Text(stringResource(R.string.history_generated, generatedCount!!))
            if (data.loading) Text(stringResource(R.string.loading_records))
            if (records.isEmpty() && !data.loading) Text(stringResource(R.string.empty_records))
            LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("record_list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(records, key = { it.id }, contentType = { "record" }) { item ->
                    RecordCard(item, { model.requestModify(item) }, { model.requestDelete(item) }, !busy)
                }
            }
            }
        }
    }
    if (adding) AddRecordDialog(model) { adding = false }
    RecordDialogs(model)
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

// 以分为单位做整数拆分，显示始终保留两位小数。
@Composable
private fun amount(cents: Long): String = LocalMoneyUnit.current.format(cents)

@Composable
fun RecordCard(item: ConsumptionItem, onModify: () -> Unit, onDelete: () -> Unit, enabled: Boolean = true) {
    Card(colors = CardDefaults.cardColors(containerColor = if (item.isManual) ManualCard else AutoCard),
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().testTag("record_${item.id}")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.date, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(if (item.isManual) R.string.manual_record else R.string.auto_record), style = MaterialTheme.typography.labelSmall)
            Text(amount(item.breakfast + item.lunch + item.dinner + item.other),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.meal_breakdown, amount(item.breakfast), amount(item.lunch), amount(item.dinner), amount(item.other)),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onModify, enabled = enabled, shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue, contentColor = Color.White)) {
                    Text(stringResource(R.string.modify))
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onDelete, enabled = enabled, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DeleteBorder),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF333333))) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}
