package com.happy.consumption.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*
import com.happy.consumption.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun AllRecordsScreen(model: AllRecordsViewModel, username: String, auth: AppViewModel,
    language: LanguageSettings? = null, backup: BackupViewModel? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()) {
    val data by model.state.collectAsState()
    val list = listState
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val generatedCount by model.generatedCount.collectAsState()
    val inline by model.inline.collectAsState()
    val leavePrompt by model.leavePrompt.collectAsState()
    val jump by model.dateJump.collectAsState()
    val selectedDate by model.selectedDate.collectAsState()
    LaunchedEffect(jump, data.records) {
        val target = jump ?: return@LaunchedEffect
        val index = data.records.indexOfFirst { it.id == target.id }
        if (index >= 0) { list.scrollToItem(index); model.dateJump.value = null }
    }
    val shortScreen = LocalConfiguration.current.screenHeightDp < 500
    val regions = remember { mutableMapOf<String, Rect>() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var adding by remember { mutableStateOf(false) }
    BackHandler(inline != null) { model.leave() }
    LaunchedEffect(inline?.item?.id) { if (inline != null) { focus.clearFocus(); keyboard?.hide() } }
    LaunchedEffect(list, data.records.size, data.count, data.error) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (last >= data.records.size - 3 && last >= 0) model.loadMore()
        }
    }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress && model.inline.value != null) model.leave() }
    Scaffold(
        modifier = Modifier.fillMaxSize().pointerInput(model, regions) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (model.inline.value != null && regions.values.none { it.contains(down.position) }) {
                    down.consume()
                    model.leave()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        },
        bottomBar = { inline?.let { draft -> PhoneKeypad(model, draft, editTouchRegion(regions, "keypad")) } }

    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
          Column(Modifier.fillMaxWidth().heightIn(max = if (shortScreen) { if (inline == null) 160.dp else 48.dp } else Dp.Unspecified)
              .then(if (shortScreen) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Text(stringResource(R.string.current_user, username), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp))
            if (message != null && !adding) Text(stringResource(message!!), color = MaterialTheme.colorScheme.error)
            if (data.error) {
                Text(stringResource(R.string.storage_error))
                Button(onClick = model::retry) { Text(stringResource(R.string.retry)) }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = if (inline == null) 12.dp else 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.all_records), style = if (inline == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.record_count, data.count), fontWeight = FontWeight.Bold)
                }
                DateCalendar(model, inline != null, regions)
                if (inline == null) {
                    Text(stringResource(R.string.page_progress, data.records.size, data.count), Modifier.testTag("page_progress"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = model::generatePastThirtyDays, enabled = !busy, modifier = Modifier.weight(1f).testTag("generate_history")) { Text(stringResource(R.string.generate_history)) }
                        FilledTonalButton(onClick = { if (!busy) { model.message.value = null; adding = true } }, enabled = !busy,
                            modifier = Modifier.weight(1f)) { Text(stringResource(R.string.add_past_record)) }
                    }
                    if (generatedCount != null) Text(stringResource(R.string.history_generated, generatedCount!!))
                }
                if (data.loading) Text(stringResource(R.string.loading_records))
                if (data.records.isEmpty() && !data.loading) Text(stringResource(R.string.empty_records))
            }
          }
          if (!data.error) {
                LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("record_list"), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(data.records, key = { it.id }, contentType = { "record" }) { item ->
                        RecordCard(item, inline?.takeIf { it.item.id == item.id },
                            { meal -> model.editAmount(item, meal) }, model::saveInline, { model.requestDelete(item) }, !busy, regions, item.date == selectedDate)
                    }
                }
            }
        }
    }
    if (adding) AddRecordDialog(model) { adding = false }
    RecordDialogs(model)
    if (leavePrompt) AlertDialog(onDismissRequest = model::keepEditing,
        title = { Text(stringResource(R.string.unsaved_title)) }, text = { Text(stringResource(R.string.unsaved_message)) },
        confirmButton = { TextButton(onClick = model::saveInline, enabled = !busy, modifier = Modifier.testTag("leave_save")) { Text(stringResource(R.string.confirm_save)) } },
        dismissButton = { Row {
            TextButton(onClick = model::discardInline, enabled = !busy, modifier = Modifier.testTag("leave_discard")) { Text(stringResource(R.string.discard_changes)) }
            TextButton(onClick = model::keepEditing, enabled = !busy, modifier = Modifier.testTag("leave_keep")) { Text(stringResource(R.string.keep_editing)) }
        } })

}


@Composable
fun RecordCard(item: ConsumptionItem, draft: InlineDraft?, onAmount: (Meal) -> Unit,
    onConfirm: () -> Unit, onDelete: () -> Unit, enabled: Boolean, regions: MutableMap<String, Rect>, selected: Boolean = false) {
    Card(border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, colors = CardDefaults.cardColors(containerColor = if (item.isManual) ManualCard else AutoCard),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag("record_${item.id}")) {
        Column(Modifier.padding(if (draft == null) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.date, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (draft != null) Button(onClick = onConfirm, enabled = enabled,
                    modifier = Modifier.testTag("confirm_${item.id}").then(editTouchRegion(regions, "confirm_${item.id}"))) { Text(stringResource(R.string.confirm_amount)) }
                else Text(stringResource(if (item.isManual) R.string.manual_record else R.string.auto_record), style = MaterialTheme.typography.labelSmall)
            }
            if (draft == null) Text(item.totals(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("total_${item.id}"))
            for (pair in Meal.entries.chunked(if (draft == null) 2 else 4)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (meal in pair) TextButton(onClick = { onAmount(meal) }, enabled = enabled,
                    contentPadding = if (draft == null) ButtonDefaults.TextButtonContentPadding else PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).testTag("amount_${item.id}_${meal.name}")
                        .then(editTouchRegion(regions, "amount_${item.id}_${meal.name}"))) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(mealLabel(meal)), style = if (draft == null) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall)
                        Text(draft?.preview(meal) ?: item.currencies[meal].format(item.value(meal)), style = if (draft == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (draft == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onConfirm, enabled = false, modifier = Modifier.testTag("confirm_${item.id}")) {
                    Text(stringResource(R.string.confirm_amount))
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onDelete, enabled = enabled, border = BorderStroke(1.dp, DeleteBorder)) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}
