package com.happy.consumption.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*
import java.util.Locale

@Composable fun AmountFields(item: ConsumptionItem? = null, content: @Composable (List<String>) -> Unit) {
    fun initial(value: Long?) = value?.let { String.format(Locale.US, "%d.%02d", it / 100, it % 100) } ?: ""
    var breakfast by remember { mutableStateOf(initial(item?.breakfast)) }
    var lunch by remember { mutableStateOf(initial(item?.lunch)) }
    var dinner by remember { mutableStateOf(initial(item?.dinner)) }
    var other by remember { mutableStateOf(initial(item?.other)) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable fun input(value: String, change: (String) -> Unit, label: Int) {
            OutlinedTextField(value, change, singleLine = true, label = { Text(stringResource(label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        }
        input(breakfast, { breakfast = it }, R.string.breakfast); input(lunch, { lunch = it }, R.string.lunch)
        input(dinner, { dinner = it }, R.string.dinner); input(other, { other = it }, R.string.other)
        content(listOf(breakfast, lunch, dinner, other))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AddRecordDialog(model: AllRecordsViewModel, close: () -> Unit) {
    var date by remember { mutableStateOf(model.dates.today()) }
    var choosing by remember { mutableStateOf(false) }
    val busy by model.busy.collectAsState(); val message by model.message.collectAsState()
    val version by model.addedVersion.collectAsState(); val initial = remember { version }
    var values by remember { mutableStateOf(listOf("", "", "", "")) }
    LaunchedEffect(version) { if (version != initial) close() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(stringResource(R.string.add_past_record)) },
        text = { Column {
            Text(stringResource(R.string.date_selected, date.toString()))
            TextButton(onClick = { choosing = true }, enabled = !busy) { Text(stringResource(R.string.select_date)) }
            AmountFields { entered ->
                SideEffect { values = entered }
                if (message != null) Text(stringResource(message!!), color = MaterialTheme.colorScheme.error)
            }
        } },
        confirmButton = { TextButton(onClick = { model.add(date.toString(), values[0], values[1], values[2], values[3]) }, enabled = !busy) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
    if (choosing) {
        val last = model.dates.utcMillis(model.dates.today())
        val picker = rememberDatePickerState(initialSelectedDateMillis = model.dates.utcMillis(date),
            initialDisplayMode = DisplayMode.Picker,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= last
                override fun isSelectableYear(year: Int) = year <= model.dates.today().year
            })
        var dateText by remember { mutableStateOf(date.toString()) }
        var valid by remember { mutableStateOf(true) }
        LaunchedEffect(picker.selectedDateMillis) {
            picker.selectedDateMillis?.let { dateText = model.dates.fromUtcMillis(it).toString(); valid = true }
        }
        val formatter = remember { object : DatePickerFormatter {
            override fun formatDate(dateMillis: Long?, locale: Locale, forContentDescription: Boolean): String? =
                dateMillis?.let { model.dates.fromUtcMillis(it).toString() }
            override fun formatMonthYear(monthMillis: Long?, locale: Locale): String? =
                monthMillis?.let { model.dates.fromUtcMillis(it).toString().take(7) }
        } }
        DatePickerDialog(onDismissRequest = { choosing = false },
            confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { date = model.dates.fromUtcMillis(it) }; choosing = false },
                enabled = valid && picker.selectedDateMillis != null) { Text(stringResource(R.string.date_confirm)) } },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } }) {
            Column(Modifier.testTag("date_picker").heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(dateText, { entered ->
                    dateText = entered
                    val parsed = try { model.dates.parseManual(entered) }
                        catch (e: UserOperationException) { com.happy.consumption.util.LogHelper.error("DatePicker", "Date input rejected", e); null }
                    valid = parsed != null
                    picker.selectedDateMillis = parsed?.let { model.dates.utcMillis(it) }
                }, label = { Text(stringResource(R.string.date_input)) }, isError = !valid, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                if (!valid) Text(stringResource(R.string.invalid_date), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                DatePicker(picker, dateFormatter = formatter, showModeToggle = false)
            }
        }
    }
}
