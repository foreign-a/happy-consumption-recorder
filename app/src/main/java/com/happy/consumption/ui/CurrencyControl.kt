package com.happy.consumption.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.happy.consumption.R
import com.happy.consumption.data.MoneyUnit

val LocalMoneyUnit = staticCompositionLocalOf { MoneyUnit.YUAN }

@Composable fun CurrencyControl(auth: AppViewModel) {
    var choosing by remember { mutableStateOf(false) }
    val current = LocalMoneyUnit.current
    val busy by auth.busy.collectAsState()
    TextButton(onClick = { choosing = true }, enabled = !busy, modifier = Modifier.testTag("currency_control")) {
        Text(stringResource(R.string.currency) + " " + current.symbol)
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.currency)) },
        text = { Column {
            Text(stringResource(R.string.currency_hint))
            for ((unit, label) in listOf(MoneyUnit.YUAN to R.string.currency_yuan, MoneyUnit.DOLLAR to R.string.currency_dollar, MoneyUnit.POUND to R.string.currency_pound)) {
                TextButton(onClick = { auth.selectCurrency(unit); choosing = false }, enabled = !busy,
                    modifier = Modifier.testTag("currency_${unit.name}")) {
                    RadioButton(selected = unit == current, onClick = null)
                    Text(unit.symbol + " " + stringResource(label))
                }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } })
}
