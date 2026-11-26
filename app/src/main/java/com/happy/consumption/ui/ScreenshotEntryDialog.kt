package com.happy.consumption.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.happy.consumption.R
import com.happy.consumption.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The request id survives retries while this dialog is open, preventing duplicate writes. */
@Composable
fun ScreenshotEntryDialog(initialAmount: String, requestId: String, repository: UserRepository, close: () -> Unit, saved: () -> Unit) {
    var amount by rememberSaveable(requestId) { mutableStateOf(initialAmount) }
    var kindName by rememberSaveable(requestId) { mutableStateOf<String?>(null) }
    val kind = kindName?.let(EntryKind::valueOf)
    // Store a primitive representation so a selected custom or built-in remark survives recreation.
    var remarkKey by rememberSaveable(requestId) { mutableStateOf<String?>(null) }
    val remark = when {
        remarkKey == "income_other" -> ScreenshotRemark.OtherIncome
        remarkKey?.startsWith("meal:") == true -> ScreenshotRemark.BuiltInMeal(Meal.valueOf(remarkKey!!.removePrefix("meal:")))
        remarkKey?.startsWith("custom:") == true -> ScreenshotRemark.Custom(remarkKey!!.removePrefix("custom:"))
        else -> null
    }
    var custom by remember(requestId) { mutableStateOf(emptyList<String>()) }
    var customName by rememberSaveable(requestId) { mutableStateOf("") }
    var adding by rememberSaveable(requestId) { mutableStateOf(false) }
    var saving by rememberSaveable(requestId) { mutableStateOf(false) }
    var clickedAtMillis by rememberSaveable(requestId) { mutableStateOf<Long?>(null) }
    var savingRemark by remember(requestId) { mutableStateOf(false) }
    var alreadyRecorded by rememberSaveable(requestId) { mutableStateOf(false) }
    val busy = saving || savingRemark
    val editable = !busy && !alreadyRecorded
    var error by rememberSaveable(requestId) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(kind) {
        val selected = kind
        custom = emptyList()
        if (selected != null) try { custom = repository.customRemarks(selected) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = errorMessage(e) }
    }
    // Once submitted, keep the draft locked across recreation and retry the same id and values.
    // A prior committed attempt is detected by the repository's receipt.
    LaunchedEffect(requestId, saving) {
        if (saving) {
            val selectedKind = kind
            val selectedRemark = remark
            val cents = AmountInput.previewCents(amount)
            val clickedAt = clickedAtMillis
            if (selectedKind == null || selectedRemark == null || cents == null || clickedAt == null) {
                saving = false
            } else try {
                if (repository.recordScreenshot(requestId, cents, selectedKind, selectedRemark, clickedAt)) {
                    saved(); close()
                } else {
                    alreadyRecorded = true
                    error = R.string.screenshot_already_recorded
                    saving = false
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = if (e is UserOperationException && e.reason.name == "CURRENCY_CONFLICT") R.string.screenshot_currency_conflict else errorMessage(e)
                saving = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.screenshot_entry_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amount, { amount = it; error = null },
                    label = { Text(stringResource(R.string.screenshot_amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = editable,
                    isError = amount.isNotEmpty() && AmountInput.previewCents(amount) == null,
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.screenshot_kind))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in EntryKind.entries) {
                        FilterChip(selected = kind == option, enabled = editable, onClick = {
                            kindName = option.name; remarkKey = null; adding = false; error = null
                        }, label = { Text(stringResource(if (option == EntryKind.INCOME) R.string.screenshot_income else R.string.screenshot_expense)) })
                    }
                }
                if (kind != null) {
                    Text(stringResource(R.string.screenshot_remark_required))
                    if (kind == EntryKind.EXPENSE) {
                        for (meal in Meal.entries) {
                            val item = ScreenshotRemark.BuiltInMeal(meal)
                            FilterChip(selected = remark == item, enabled = editable, onClick = { remarkKey = "meal:${meal.name}" },
                                label = { Text(stringResource(mealLabel(meal))) })
                        }
                    } else {
                        val item = ScreenshotRemark.OtherIncome
                        FilterChip(selected = remark == item, enabled = editable, onClick = { remarkKey = "income_other" },
                            label = { Text(stringResource(R.string.screenshot_other)) })
                    }
                    for (name in custom) {
                        val item = ScreenshotRemark.Custom(name)
                        FilterChip(selected = remark == item, enabled = editable, onClick = { remarkKey = "custom:$name" }, label = { Text(name) })
                    }
                    if (!adding) TextButton(onClick = { adding = true }, enabled = editable) { Text(stringResource(R.string.screenshot_add_remark)) }
                    else {
                        OutlinedTextField(customName, { customName = it; error = null }, enabled = editable,
                            label = { Text(stringResource(R.string.screenshot_new_remark)) }, singleLine = true)
                        TextButton(enabled = editable && customName.trim().isNotEmpty(), onClick = {
                            val selected = kind ?: return@TextButton
                            val name = customName.trim()
                            savingRemark = true
                            scope.launch {
                                try {
                                    repository.saveCustomRemark(selected, name)
                                    custom = repository.customRemarks(selected)
                                    remarkKey = "custom:$name"
                                    adding = false; customName = ""; error = null
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = errorMessage(e) }
                                finally { savingRemark = false }
                            }
                        }) { Text(stringResource(R.string.screenshot_save_remark)) }
                    }
                }
                if (error != null) Text(stringResource(error!!), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = editable && kind != null && remark != null && AmountInput.previewCents(amount) != null,
                onClick = {
                    if (kind == null || remark == null || AmountInput.previewCents(amount) == null) return@TextButton
                    clickedAtMillis = System.currentTimeMillis()
                    saving = true
                }) { Text(stringResource(R.string.screenshot_record)) }
        },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.cancel)) } }
    )
}
