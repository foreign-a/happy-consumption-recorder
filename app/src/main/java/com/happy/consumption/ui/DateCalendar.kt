package com.happy.consumption.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Rolling seven days, or a Monday-first month. Missing past dates create zero records on selection. */
@Composable fun DateCalendar(model: AllRecordsViewModel, editing: Boolean, regions: MutableMap<String, Rect>) {
    val calendar by model.calendar.collectAsState()
    val selected by model.selectedDate.collectAsState()
    val jumping by model.jumping.collectAsState()
    val busy by model.busy.collectAsState()
    val today = model.dates.today()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month = YearMonth.parse(monthText)
    val showMonth = expanded && !editing
    val first = if (showMonth) month.atDay(1) else today.minusDays(6)
    val last = if (showMonth) month.atEndOfMonth() else today
    val previousLabel = stringResource(R.string.calendar_previous)
    val nextLabel = stringResource(R.string.calendar_next)
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(first, last) { model.showCalendar(first.toString(), last.toString()) }
    Column(Modifier.fillMaxWidth().testTag("date_calendar")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (showMonth) month.atDay(1).format(DateTimeFormatter.ofPattern("yyyy MMMM", locale))
                else first.format(DateTimeFormatter.ofPattern("M/d", locale)) + " – " + last.format(DateTimeFormatter.ofPattern("M/d", locale)),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (showMonth) {
                TextButton(onClick = { model.leave { monthText = month.minusMonths(1).toString() } },
                    modifier = Modifier.testTag("calendar_previous").then(editTouchRegion(regions, "calendar_previous"))) {
                    Text("‹", Modifier.semantics { contentDescription = previousLabel })
                }
                TextButton(onClick = { model.leave { monthText = month.plusMonths(1).toString() } }, enabled = month < YearMonth.from(today),
                    modifier = Modifier.testTag("calendar_next").then(editTouchRegion(regions, "calendar_next"))) {
                    Text("›", Modifier.semantics { contentDescription = nextLabel })
                }
            }
            val toggleLabel = stringResource(if (showMonth) R.string.calendar_collapse else R.string.calendar_expand)
            TextButton(onClick = { model.leave { expanded = !showMonth } }, modifier = Modifier.testTag("calendar_toggle").then(editTouchRegion(regions, "calendar_toggle"))) {
                Text(if (showMonth) "↑" else "↓", Modifier.semantics { contentDescription = toggleLabel })
            }
        }
        if (calendar.error) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.calendar_error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { model.leave { model.refreshCalendar() } }, modifier = Modifier.testTag("calendar_retry")) { Text(stringResource(R.string.retry)) }
        } else {
            val days: List<LocalDate?> = if (showMonth) {
                val offset = first.dayOfWeek.value - 1
                List(((offset + month.lengthOfMonth() + 6) / 7) * 7) { index ->
                    (index - offset + 1).takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
                }
            } else (0L..6L).map(first::plusDays)
            val weekdays = if (showMonth) (1..7).map(DayOfWeek::of) else days.map { it!!.dayOfWeek }
            Row(Modifier.fillMaxWidth()) {
                weekdays.forEach { day -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(day.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall)
                } }
            }
            for (week in days.chunked(7)) Row(Modifier.fillMaxWidth()) {
                for (date in week) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (date == null) Spacer(Modifier.height(48.dp)) else {
                        val exists = date.toString() in calendar.available && date <= today
                        val tag = "calendar_date_$date"
                        TextButton(onClick = { model.openCalendarDate(date.toString()) }, enabled = date <= today && !calendar.loading && !jumping && !busy,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (exists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                containerColor = if (selected == date.toString() && exists) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)),
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().height(48.dp).testTag(tag)
                                .semantics { contentDescription = date.toString(); this.selected = selected == date.toString() && exists }
                                .then(if (date <= today) editTouchRegion(regions, tag) else Modifier)) { Text(date.dayOfMonth.toString()) }
                    }
                }
            }
            if (calendar.loading || jumping) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
    }
}
