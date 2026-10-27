package com.happy.consumption.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*

private fun chartColor(hex: String) = Color(android.graphics.Color.parseColor(hex))

@Composable fun DailyPieStatistics(item: ConsumptionItem, store: PersonalSchedule?) {
    var colors by remember(store) { mutableStateOf(store?.chartColors() ?: ChartColors.defaults) }
    var choosing by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    Text(stringResource(R.string.daily_shares), style = MaterialTheme.typography.titleLarge)
    if (store != null) TextButton(onClick = { choosing = true }, modifier = Modifier.testTag("chart_colors")) {
        Text(stringResource(R.string.custom_chart_colors))
    }
    val units = Meal.entries.filter { item.value(it) > 0 }.map { item.currencies[it] }.distinct()
    if (units.isEmpty()) Text(stringResource(R.string.no_spending_shares), modifier = Modifier.testTag("pie_empty"))
    else {
        if (units.size > 1) Text(stringResource(R.string.pie_currency_hint), style = MaterialTheme.typography.bodySmall)
        for (unit in units) {
            val shares = dailyShares(item, unit)
            val summary = shares.filter { item.currencies[it.meal] == unit }.joinToString("; ") {
                item.currencies[it.meal].format(it.amount) + " " + String.format(locale, "%.1f%%", it.fraction * 100)
            }
            Text(unit.format(shares.sumOf { it.amount }), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(192.dp).padding(4.dp).testTag("daily_pie_${unit.name}")
                    .semantics { contentDescription = item.date + " " + summary }) {
                    var angle = -90f
                    val positive = shares.filter { it.amount > 0 }
                    positive.forEachIndexed { index, share ->
                        val sweep = if (index == positive.lastIndex) 270f - angle else share.fraction * 360f
                        drawArc(chartColor(colors[share.meal.ordinal]), angle, sweep, useCenter = true)
                        angle += sweep
                    }
                }
            }
            for (share in shares.filter { item.currencies[it.meal] == unit }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(chartColor(colors[share.meal.ordinal]), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(mealLabel(share.meal)), Modifier.weight(1f))
                    Text(unit.format(share.amount) + " · " + String.format(locale, "%.1f%%", share.fraction * 100),
                        modifier = Modifier.testTag("share_${share.meal.name}"))
                }
            }
        }
    }
    if (choosing && store != null) ChartColorDialog(colors, { choosing = false }) { updated ->
        if (store.saveChartColors(updated)) { colors = updated; choosing = false; true } else false
    }
}

@Composable private fun ChartColorDialog(current: List<String>, close: () -> Unit, save: (List<String>) -> Boolean) {
    var drafts by remember { mutableStateOf(current) }
    var active by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val parsed = drafts.map { ChartColors.normalize(it) }
    val presets = listOf("#387EF5", "#F4A340", "#8D67D5", "#35A889", "#EF5B68", "#16A6B6", "#667085", "#D75AC6")
    AlertDialog(onDismissRequest = close,
        title = { Text(stringResource(R.string.custom_chart_colors)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chart_color_hint), style = MaterialTheme.typography.bodySmall)
            for (meal in Meal.entries) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = active == meal.ordinal, onClick = { active = meal.ordinal })
                    Box(Modifier.size(18.dp).background(chartColor(parsed[meal.ordinal] ?: current[meal.ordinal]), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(drafts[meal.ordinal], { value ->
                        active = meal.ordinal; failed = false
                        drafts = drafts.toMutableList().apply { this[meal.ordinal] = value.take(7) }
                    }, label = { Text(stringResource(mealLabel(meal))) }, singleLine = true,
                        isError = parsed[meal.ordinal] == null, modifier = Modifier.weight(1f).testTag("color_${meal.name}"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                presets.forEach { preset ->
                    Box(Modifier.size(32.dp).background(chartColor(preset), CircleShape)
                        .clickable { drafts = drafts.toMutableList().apply { this[active] = preset }; failed = false }
                        .semantics { contentDescription = preset }.testTag("preset_${preset.removePrefix("#")}"))
                }
            }
            TextButton(onClick = { drafts = ChartColors.defaults; failed = false }) { Text(stringResource(R.string.reset_chart_colors)) }
            if (failed) Text(stringResource(R.string.storage_error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { failed = !save(parsed.filterNotNull()) },
            enabled = parsed.all { it != null }, modifier = Modifier.testTag("save_chart_colors")) { Text(stringResource(R.string.confirm_save)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } })
}
