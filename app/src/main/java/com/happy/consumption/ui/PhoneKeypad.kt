package com.happy.consumption.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*

fun mealLabel(meal: Meal) = when (meal) {
    Meal.BREAKFAST -> R.string.breakfast
    Meal.LUNCH -> R.string.lunch
    Meal.DINNER -> R.string.dinner
    Meal.OTHER -> R.string.other
}

@Composable fun EntryCurrencyChoices(current: MoneyUnit, select: (MoneyUnit) -> Unit, enabled: Boolean = true, tag: String = "entry_currency") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (unit in MoneyUnit.entries) OutlinedButton(onClick = { select(unit) }, enabled = enabled,
            border = BorderStroke(if (unit == current) 2.dp else 1.dp, if (unit == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            modifier = Modifier.weight(1f).testTag("${tag}_${unit.name}"), contentPadding = PaddingValues(4.dp)) { Text(unit.symbol) }
    }
}

@Composable fun PhoneKeypad(model: AllRecordsViewModel, draft: InlineDraft, modifier: Modifier = Modifier) {
    val busy by model.busy.collectAsState()
    val deleteLabel = stringResource(R.string.key_backspace)
    Surface(modifier, tonalElevation = 6.dp, shadowElevation = 8.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp).testTag("phone_keypad")) {
            @Composable fun controls() {
                Text(stringResource(mealLabel(draft.selected)) + " " + draft.preview(draft.selected), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.tap_amount_hint), style = MaterialTheme.typography.bodySmall)
                EntryCurrencyChoices(draft.currencies[draft.selected], model::changeEntryCurrency, !busy)
                Button(onClick = model::saveInline, enabled = !busy, modifier = Modifier.testTag("keypad_confirm")) { Text(stringResource(R.string.confirm_amount)) }
            }
            @Composable fun keys() {
              for (keys in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "backspace"))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (key in keys) TextButton(onClick = { model.pressKey(key) }, enabled = !busy,
                        modifier = Modifier.weight(1f).height(42.dp).testTag("key_$key")
                            .then(if (key == "backspace") Modifier.semantics { contentDescription = deleteLabel } else Modifier)) {
                        Text(if (key == "backspace") "⌫" else key, style = MaterialTheme.typography.headlineSmall)
                    }
                }
              }
            }
            if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { controls() }
                Column(Modifier.weight(1f)) { keys() }
            } else Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(mealLabel(draft.selected)) + " " + draft.preview(draft.selected), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = model::saveInline, enabled = !busy, modifier = Modifier.testTag("keypad_confirm")) { Text(stringResource(R.string.confirm_amount)) }
                }
                EntryCurrencyChoices(draft.currencies[draft.selected], model::changeEntryCurrency, !busy)
                keys()
            }
        }
    }
}
