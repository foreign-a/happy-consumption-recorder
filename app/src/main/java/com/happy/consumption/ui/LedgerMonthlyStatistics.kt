package com.happy.consumption.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*
import java.math.BigInteger
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart

/** Monthly statistics and selected-day independent entries for the current account.
 * The supplied query must be scoped to the current authenticated user.
 */
@Composable
fun LedgerMonthlyStatistics(
    model: AllRecordsViewModel,
    ledgerChanges: Flow<Unit>,
    loadLedger: suspend (String, String) -> List<LedgerEntry>,
    colorStore: PersonalSchedule? = null,
) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(model.dates.today()).toString()) }
    val month = YearMonth.parse(monthText)
    var day by rememberSaveable(monthText) {
        mutableIntStateOf(if (month == YearMonth.from(model.dates.today())) model.dates.today().dayOfMonth else 1)
    }
    val rows by model.monthRecords.collectAsState()
    val rowsLoading by model.monthLoading.collectAsState()
    val rowsError by model.monthError.collectAsState()
    var entries by remember(monthText) { mutableStateOf<List<LedgerEntry>>(emptyList()) }
    var entriesLoading by remember(monthText) { mutableStateOf(true) }
    var entriesError by remember(monthText) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(monthText) { model.loadMonth(month) }
    LaunchedEffect(monthText, ledgerChanges, retry) {
        ledgerChanges.onStart { emit(Unit) }.collectLatest {
            entriesLoading = true
            entriesError = false
            try {
                entries = loadLedger(month.atDay(1).toString(), month.atEndOfMonth().toString())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                entriesError = true
            } finally {
                entriesLoading = false
            }
        }
    }
    val previousLabel = stringResource(R.string.calendar_previous)
    val nextLabel = stringResource(R.string.calendar_next)
    Text(stringResource(R.string.ledger_monthly_statistics), style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { monthText = month.minusMonths(1).toString() },
            modifier = Modifier.semantics { contentDescription = previousLabel }) { Text("‹") }
        Text(monthText, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { monthText = month.plusMonths(1).toString() },
            enabled = month < YearMonth.from(model.dates.today()),
            modifier = Modifier.semantics { contentDescription = nextLabel }) { Text("›") }
    }
    if (rowsLoading || entriesLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else if (rowsError || entriesError) {
        Text(stringResource(R.string.storage_error))
        TextButton(onClick = { model.loadMonth(month); retry++ }) { Text(stringResource(R.string.retry)) }
    } else {
        val relevantRows = rows.filter { it.date.startsWith(monthText) }
        val units = (relevantRows.flatMap { row -> Meal.entries.filter { row.value(it) > 0 }.map { row.currencies[it] } } +
            entries.map { it.currency }).distinct().ifEmpty { listOf(LocalMoneyUnit.current) }
        Text(stringResource(R.string.chart_hint), style = MaterialTheme.typography.bodySmall)
        for (unit in units) {
            val spending = relevantRows.totalCents(unit) + ledgerSum(entries, EntryKind.EXPENSE, unit)
            val income = ledgerSum(entries, EntryKind.INCOME, unit)
            Text(stringResource(R.string.ledger_month_spending, unit.format(spending)), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("ledger_month_spending_${unit.name}"))
            Text(stringResource(R.string.ledger_month_income, unit.format(income)), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("ledger_month_income_${unit.name}"))
            val values = (1..month.lengthOfMonth()).map { d ->
                val date = month.atDay(d).toString()
                (relevantRows.firstOrNull { it.date == date }?.totalCents(unit) ?: BigInteger.ZERO) +
                    ledgerSum(entries.filter { it.date == date }, EntryKind.EXPENSE, unit)
            }
            val max = values.maxOrNull()?.coerceAtLeast(BigInteger.ONE) ?: BigInteger.ONE
            val chartScroll = rememberScrollState()
            val density = LocalDensity.current.density
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
                            Box(Modifier.width(24.dp).height((value.toDouble() / max.toDouble() * 114).toFloat().coerceAtLeast(3f).dp)
                                .background(if (day == index + 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        }
                        Text((index + 1).toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        val date = month.atDay(day).toString()
        val selected = relevantRows.firstOrNull { it.date == date }
        val selectedEntries = entries.filter { it.date == date }
        Text(date, style = MaterialTheme.typography.titleMedium)
        if (selected != null) {
            for (meal in Meal.entries) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(mealLabel(meal)), Modifier.weight(1f))
                Text(selected.currencies[meal].format(selected.value(meal)), Modifier.weight(3f), textAlign = TextAlign.End)
            }
        }
        for (unit in units) {
            val spending = (selected?.totalCents(unit) ?: BigInteger.ZERO) + ledgerSum(selectedEntries, EntryKind.EXPENSE, unit)
            val income = ledgerSum(selectedEntries, EntryKind.INCOME, unit)
            Text(stringResource(R.string.ledger_day_spending, unit.format(spending)), modifier = Modifier.testTag("ledger_day_spending_${unit.name}"))
            Text(stringResource(R.string.ledger_day_income, unit.format(income)), modifier = Modifier.testTag("ledger_day_income_${unit.name}"))
        }
        Text(stringResource(R.string.ledger_entries), style = MaterialTheme.typography.titleMedium)
        if (selectedEntries.isEmpty()) Text(stringResource(R.string.ledger_entries_empty))
        else selectedEntries.forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("ledger_entry_${entry.id}"),
                horizontalArrangement = Arrangement.SpaceBetween) {
                val type = stringResource(if (entry.kind == EntryKind.INCOME) R.string.ledger_income else R.string.ledger_expense)
                val remark = if (entry.isOtherIncome) stringResource(R.string.other) else entry.remark
                Text("$type · $remark", Modifier.weight(1f))
                Text(entry.currency.format(entry.amount), textAlign = TextAlign.End)
            }
        }
        if (selected != null) {
            Text(stringResource(R.string.ledger_pie_scope), style = MaterialTheme.typography.bodySmall)
            DailyPieStatistics(selected, colorStore)
        }
    }
}

private fun ledgerSum(entries: List<LedgerEntry>, kind: EntryKind, unit: MoneyUnit): BigInteger =
    entries.asSequence().filter { it.kind == kind && it.currency == unit }
        .fold(BigInteger.ZERO) { sum, entry -> sum + BigInteger.valueOf(entry.amount) }
