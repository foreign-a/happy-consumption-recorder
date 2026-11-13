package com.happy.consumption.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val prompting by model.leavePrompt.collectAsState()
    val preferences by model.entryPreferences.collectAsState()
    val unavailable = busy || prompting || draft == null
    val operation by model.operation.collectAsState()
    val deleteLabel = stringResource(R.string.key_backspace)
    // A flat surface keeps the shared mode background free of a dark shadow edge.
    Surface(modifier.modeBackground(MaterialTheme.colorScheme.surface), color = Color.Transparent) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp).testTag("phone_keypad")) {
            val wide = maxWidth >= 600.dp
            @Composable fun header() {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(operationLabel(operation)) + " · " + stringResource(mealLabel((draft?.selected ?: Meal.OTHER))) + " " + (draft?.preview(draft.selected) ?: "—"),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (preferences.confirmation == ConfirmationPlan.CAREFUL) {
                        TextButton(onClick = model::saveInline, enabled = !unavailable && draft?.changed == true,
                            modifier = Modifier.testTag("key_confirm")) { Text(stringResource(R.string.confirm_save)) }
                    }
                }
            }
            @Composable fun controls() {
                Text(stringResource(if (preferences.confirmation == ConfirmationPlan.QUICK) R.string.quick_keypad_hint else R.string.careful_keypad_hint), style = MaterialTheme.typography.bodySmall)
                EntryCurrencyChoices((draft?.currencies?.get(draft.selected) ?: LocalMoneyUnit.current), model::changeEntryCurrency, !unavailable && operation == DraftOperation.REPLACE)
            }
            @Composable fun keys() {
              val keyHeight = if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500) 40.dp else 54.dp
              for (keys in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "backspace"))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (key in keys) {
                        if (key == "backspace") BackspaceKey(!unavailable, deleteLabel,
                            Modifier.weight(1f).height(keyHeight)) { model.pressKey("backspace") }
                        else TextButton(onClick = { model.pressKey(key) }, enabled = !unavailable,
                            modifier = Modifier.weight(1f).height(keyHeight).testTag("key_$key")) {
                            Text(key, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
              }
            }
            Column {
                header()
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { controls() }
                    Column(Modifier.weight(1f)) { keys() }
                } else {
                    controls()
                    keys()
                }
            }
        }
    }
}

@Composable private fun BackspaceKey(enabled: Boolean, label: String, modifier: Modifier, onDelete: () -> Unit) {
    val delete by rememberUpdatedState(onDelete)
    Box(modifier.testTag("key_backspace").semantics {
        contentDescription = label
    }.clickable(enabled = enabled, role = Role.Button, onClick = { delete() }).pointerInput(enabled) {
        if (enabled) coroutineScope {
            val gestureScope = this
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var repeated = false
                val repeat = gestureScope.launch {
                    delay(400)
                    while (isActive) {
                        repeated = true
                        delete()
                        delay(80)
                    }
                }
                try {
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        if (!repeated) delete()
                    }
                } finally { repeat.cancel() }
            }
        }
    }, contentAlignment = Alignment.Center) {
        Text("⌫", style = MaterialTheme.typography.headlineSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}
