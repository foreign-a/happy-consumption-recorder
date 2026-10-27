package com.happy.consumption.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*
import java.time.*

@Composable fun ScheduleControl(store: PersonalSchedule, onSaved: () -> Unit) {
    var current by remember { mutableStateOf(store.read()) }
    var editing by remember { mutableStateOf(false) }
    var zone by remember { mutableStateOf(current.zone) }
    var breakfast by remember { mutableStateOf(current.breakfast.toString()) }
    var lunch by remember { mutableStateOf(current.lunch.toString()) }
    var dinner by remember { mutableStateOf(current.dinner.toString()) }
    var error by remember { mutableStateOf(false) }
    Text(stringResource(R.string.schedule_title), style = MaterialTheme.typography.titleLarge)
    Text(current.zone + " · " + listOf(current.breakfast, current.lunch, current.dinner).joinToString(" / "))
    TextButton(onClick = {
        zone = current.zone; breakfast = current.breakfast.toString(); lunch = current.lunch.toString()
        dinner = current.dinner.toString(); error = false; editing = true
    }, modifier = Modifier.testTag("edit_schedule")) { Text(stringResource(R.string.schedule_edit)) }
    if (editing) AlertDialog(onDismissRequest = { editing = false },
        title = { Text(stringResource(R.string.schedule_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.schedule_hint))
            OutlinedTextField(zone, { zone = it; error = false }, singleLine = true,
                label = { Text(stringResource(R.string.time_zone)) }, modifier = Modifier.testTag("schedule_zone"))
            for ((label, value, change) in listOf(
                Triple(R.string.breakfast, breakfast, { v: String -> breakfast = v }),
                Triple(R.string.lunch, lunch, { v: String -> lunch = v }),
                Triple(R.string.dinner, dinner, { v: String -> dinner = v }))) {
                OutlinedTextField(value, { change(it); error = false }, singleLine = true,
                    label = { Text(stringResource(label) + " (HH:mm)") })
            }
            if (error) Text(stringResource(R.string.schedule_error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            try {
                val value = MealSchedule(ZoneId.of(zone.trim()).id, LocalTime.parse(breakfast.trim()),
                    LocalTime.parse(lunch.trim()), LocalTime.parse(dinner.trim()))
                if (store.save(value)) { current = value; editing = false; onSaved() } else error = true
            } catch (_: Exception) { error = true }
        }) { Text(stringResource(R.string.confirm_save)) } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable fun MonthlyStatistics(model: AllRecordsViewModel, colorStore: PersonalSchedule? = null) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(model.dates.today()).toString()) }
    val month = YearMonth.parse(monthText)
    val rows by model.monthRecords.collectAsState()
    val loading by model.monthLoading.collectAsState()
    val error by model.monthError.collectAsState()
    var day by rememberSaveable(monthText) { mutableIntStateOf(if (month == YearMonth.from(model.dates.today())) model.dates.today().dayOfMonth else 1) }
    LaunchedEffect(monthText) { model.loadMonth(month) }
    val previousLabel = stringResource(R.string.calendar_previous)
    val nextLabel = stringResource(R.string.calendar_next)
    Text(stringResource(R.string.monthly_statistics), style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { monthText = month.minusMonths(1).toString() },
            modifier = Modifier.semantics { contentDescription = previousLabel }) { Text("‹") }
        Text(monthText, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { monthText = month.plusMonths(1).toString() },
            enabled = month < YearMonth.from(model.dates.today()),
            modifier = Modifier.semantics { contentDescription = nextLabel }) { Text("›") }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if (error) {
        Text(stringResource(R.string.storage_error))
        TextButton(onClick = { model.loadMonth(month) }) { Text(stringResource(R.string.retry)) }
    } else {
        val units = rows.flatMap { row -> Meal.entries.filter { row.value(it) > 0 }.map { row.currencies[it] } }.distinct()
            .ifEmpty { listOf(LocalMoneyUnit.current) }
        Text(stringResource(R.string.chart_hint), style = MaterialTheme.typography.bodySmall)
        for (unit in units) {
            fun sum(row: ConsumptionItem?) = row?.let { item -> Meal.entries.filter { item.currencies[it] == unit }.sumOf { item.value(it) } } ?: 0L
            Text(unit.format(rows.sumOf { sum(it) }), style = MaterialTheme.typography.titleMedium)
            val values = (1..month.lengthOfMonth()).map { d -> sum(rows.firstOrNull { it.date == month.atDay(d).toString() }) }
            val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            val chartScroll = rememberScrollState()
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            LaunchedEffect(monthText, unit) {
                withFrameNanos { }
                chartScroll.scrollTo(((day - 4).coerceAtLeast(0) * 40 * density).toInt())
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(chartScroll).testTag("monthly_chart_${unit.name}"),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                values.forEachIndexed { index, value ->
                    val date = month.atDay(index + 1)
                    Column(Modifier.width(36.dp).clickable { day = index + 1 }
                        .testTag("chart_${unit.name}_$date").semantics {
                            contentDescription = "$date ${unit.format(value)}"; selected = day == index + 1
                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(120.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.width(24.dp).height((value.toFloat() / max * 114).coerceAtLeast(3f).dp)
                                .background(if (day == index + 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        }
                        Text((index + 1).toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        val selected = rows.firstOrNull { it.date == month.atDay(day).toString() }
        Text(month.atDay(day).toString(), style = MaterialTheme.typography.titleMedium)
        if (selected == null) Text(stringResource(R.string.empty_records))
        else {
            for (meal in Meal.entries) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(mealLabel(meal))); Text(selected.currencies[meal].format(selected.value(meal)))
            }
            Text(stringResource(R.string.daily_total) + " " + selected.totals(), style = MaterialTheme.typography.titleMedium)
            DailyPieStatistics(selected, colorStore)
        }
    }
}
