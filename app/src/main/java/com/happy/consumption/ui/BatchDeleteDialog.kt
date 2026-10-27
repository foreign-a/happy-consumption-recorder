package com.happy.consumption.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.totals
import java.time.YearMonth

@Composable fun BatchDeleteDialog(model: AllRecordsViewModel) {
    val state by model.batchDelete.collectAsState()
    val current = state ?: return
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val previous = stringResource(R.string.calendar_previous)
    val next = stringResource(R.string.calendar_next)
    AlertDialog(onDismissRequest = {
        if (current.reviewing) model.editBatchSelection() else model.closeBatchDelete()
    }, modifier = Modifier.testTag("batch_delete_dialog"),
        title = { Text(stringResource(if (current.reviewing) R.string.confirm_delete else R.string.batch_delete_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.batch_selected_count, current.selected.size), modifier = Modifier.testTag("batch_selected_count"))
            if (current.reviewing) {
                Text(stringResource(R.string.batch_delete_warning))
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(current.selected.keys.sorted(), key = { it }) { date -> Text(date, Modifier.padding(vertical = 4.dp)) }
                }
            } else {
                Text(stringResource(R.string.batch_delete_hint), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { model.loadDeleteMonth(current.month.minusMonths(1)) }, enabled = !busy,
                        modifier = Modifier.testTag("batch_previous").semantics { contentDescription = previous }) { Text("‹") }
                    Text(current.month.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { model.loadDeleteMonth(current.month.plusMonths(1)) },
                        enabled = !busy && current.month < YearMonth.from(model.dates.today()),
                        modifier = Modifier.testTag("batch_next").semantics { contentDescription = next }) { Text("›") }
                }
                if (current.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (current.error) {
                    Text(stringResource(R.string.storage_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { model.loadDeleteMonth(current.month) }) { Text(stringResource(R.string.retry)) }
                } else if (current.records.isEmpty()) Text(stringResource(R.string.batch_empty_month))
                else {
                    TextButton(onClick = model::toggleDeleteMonth, enabled = !busy, modifier = Modifier.testTag("batch_select_month")) {
                        Text(stringResource(if (current.records.all { it.date in current.selected }) R.string.batch_deselect_month else R.string.batch_select_month))
                    }
                    LazyColumn(Modifier.heightIn(max = 240.dp).testTag("batch_date_list")) {
                        items(current.records.sortedByDescending { it.date }, key = { it.id }) { item ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { model.toggleDeleteDate(item) }
                                .testTag("batch_date_${item.date}").semantics { selected = item.date in current.selected }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = item.date in current.selected, onCheckedChange = null)
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(item.date)
                                    Text(item.totals(), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            if (message != null) Text(stringResource(message!!), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = {
            TextButton(onClick = { if (current.reviewing) model.confirmBatchDelete() else model.reviewBatchDelete() },
                enabled = !busy && !current.loading && !current.error && current.selected.isNotEmpty(),
                modifier = Modifier.testTag(if (current.reviewing) "batch_confirm_delete" else "batch_review_delete")) {
                Text(stringResource(if (current.reviewing) R.string.batch_confirm_count else R.string.batch_review_count, current.selected.size))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (current.reviewing) model.editBatchSelection() else model.closeBatchDelete() }, enabled = !busy,
                modifier = Modifier.testTag("batch_cancel")) {
                Text(stringResource(if (current.reviewing) R.string.batch_back_selection else R.string.cancel))
            }
        })
}
