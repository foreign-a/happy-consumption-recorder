package com.happy.consumption.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.happy.consumption.R
import com.happy.consumption.data.*

@Composable fun RecordDialogs(model: AllRecordsViewModel) {
    val state by model.dialog.collectAsState()
    val message by model.message.collectAsState()
    when (val dialog = state) {
        ConfirmationState.Idle -> Unit
        is ConfirmationState.Editing -> {
            var values by remember(dialog.item.id) { mutableStateOf(emptyList<String>()) }
            AlertDialog(onDismissRequest = model::cancel, title = { Text(stringResource(R.string.modify)) },
                text = { AmountFields(dialog.item) { entered ->
                    SideEffect { values = entered }
                    if (message != null) Text(stringResource(message!!), color = MaterialTheme.colorScheme.error)
                } },
                confirmButton = { TextButton(onClick = { model.review(values[0], values[1], values[2], values[3]) }, enabled = values.size == 4) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = model::cancel) { Text(stringResource(R.string.cancel)) } })
        }
        else -> {
            val final = dialog is ConfirmationState.FinalModify
            val delete = dialog is ConfirmationState.FirstDelete
            AlertDialog(onDismissRequest = model::cancel,
                text = { Text(stringResource(if (final) R.string.confirm_changes else R.string.continue_operation)) },
                confirmButton = { TextButton(onClick = {
                    if (delete) model.confirmDelete() else if (final) model.confirmModify() else model.acceptFirst()
                }) { Text(stringResource(if (delete) R.string.confirm_delete else if (final) R.string.confirm_save else R.string.continue_button)) } },
                dismissButton = { TextButton(onClick = model::cancel) { Text(stringResource(R.string.cancel)) } })
        }
    }
}
