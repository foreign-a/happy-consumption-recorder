package com.happy.consumption.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*

fun operationLabel(operation: DraftOperation) = when (operation) {
    DraftOperation.REPLACE -> R.string.entry_replace
    DraftOperation.ADD -> R.string.operation_add
    DraftOperation.SUBTRACT -> R.string.operation_subtract
}

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

@Composable fun PhoneKeypad(model: AllRecordsViewModel, draft: InlineDraft?, modifier: Modifier = Modifier) {
    val busy by model.busy.collectAsState()
    val unavailable = busy || draft == null
    val operation by model.operation.collectAsState()
    val deleteLabel = stringResource(R.string.key_backspace)
    Surface(modifier.modeBackground(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)), color = Color.Transparent, shadowElevation = 8.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp).testTag("phone_keypad")) {
            @Composable fun controls() {
                Text(stringResource(operationLabel(operation)) + " · " + stringResource(mealLabel((draft?.selected ?: Meal.OTHER))) + " " + (draft?.preview(draft.selected) ?: "—"), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (operation == DraftOperation.REPLACE) R.string.outside_confirm_hint else R.string.adjust_hint), style = MaterialTheme.typography.bodySmall)
                EntryCurrencyChoices((draft?.currencies?.get(draft.selected) ?: LocalMoneyUnit.current), model::changeEntryCurrency, !unavailable && operation == DraftOperation.REPLACE)

            }
            @Composable fun keys() {
              for (keys in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "backspace"))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (key in keys) TextButton(onClick = { model.pressKey(key) }, enabled = !unavailable,
                        modifier = Modifier.weight(1f).height(if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500) 40.dp else 54.dp).testTag("key_$key")
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
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(operationLabel(operation)) + " · " + stringResource(mealLabel((draft?.selected ?: Meal.OTHER))) + " " + (draft?.preview(draft.selected) ?: "—"), style = MaterialTheme.typography.titleMedium)

                }
                Text(stringResource(if (operation == DraftOperation.REPLACE) R.string.outside_confirm_hint else R.string.adjust_hint), style = MaterialTheme.typography.bodySmall)
                EntryCurrencyChoices((draft?.currencies?.get(draft.selected) ?: LocalMoneyUnit.current), model::changeEntryCurrency, !unavailable && operation == DraftOperation.REPLACE)
                keys()
            }
        }
    }
}
