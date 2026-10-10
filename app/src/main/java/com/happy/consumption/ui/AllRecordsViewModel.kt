package com.happy.consumption.ui

import com.happy.consumption.util.LogHelper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.happy.consumption.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecordsState(val records: List<ConsumptionItem> = emptyList(), val count: Int = 0,
    val loading: Boolean = true, val error: Boolean = false)

class AllRecordsViewModel(private val repository: RecordsRepository, private val workScope: CoroutineScope? = null) : ViewModel() {
    private val scope get() = workScope ?: viewModelScope
    val state = MutableStateFlow(RecordsState())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Int?>(null)
    val addedVersion = MutableStateFlow(0)
    val dialog = MutableStateFlow<ConfirmationState>(ConfirmationState.Idle)
    val dates get() = repository.dates
    private val confirmation = RecordConfirmation(repository.session)
    private val mutex = Mutex()
    private var pages = 1
    init {
        refreshToday()
        scope.launch {
            try { repository.changes.collect { load() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogHelper.error("AllRecordsViewModel", "Operation failed; recovery preserved", e); state.value = state.value.copy(loading = false, error = true) }
        }
    }
    private suspend fun load() = mutex.withLock {
        state.value = state.value.copy(loading = true)
        try {
            val fresh = repository.snapshot(pages)
            state.value = RecordsState(fresh.records, fresh.count, false, state.value.error)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { LogHelper.error("AllRecordsViewModel", "Operation failed; recovery preserved", e); state.value = state.value.copy(loading = false, error = true) }
    }
    fun loadMore() {
        if (state.value.loading || state.value.error || state.value.records.size >= state.value.count) return
        state.value = state.value.copy(loading = true)
        scope.launch { pages++; load() }
    }
    fun refreshToday() {
        scope.launch {
            try { repository.ensureToday(); state.value = state.value.copy(error = false); load() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogHelper.error("AllRecordsViewModel", "Operation failed; recovery preserved", e); state.value = state.value.copy(loading = false, error = true) }
        }
    }
    fun retry() { LogHelper.info("Paging", "Retry requested"); refreshToday() }
    private fun operate(action: suspend () -> Unit) {
        if (busy.value || state.value.error) return
        busy.value = true; message.value = null
        scope.launch {
            try { action(); load() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogHelper.error("AllRecordsViewModel", "Operation failed; recovery preserved", e);
                message.value = errorMessage(e)
                if (e !is UserOperationException || e.reason == UserError.STORAGE) state.value = state.value.copy(error = true)
            } finally { busy.value = false }
        }
    }
    fun add(date: String, breakfast: String, lunch: String, dinner: String, other: String) = operate {
        repository.add(date, Amounts.parse(breakfast, lunch, dinner, other)); addedVersion.value++
    }
    fun requestModify(item: ConsumptionItem) {
        if (busy.value || state.value.error || confirmation.state != ConfirmationState.Idle) return
        message.value = null; confirmation.requestModify(item); dialog.value = confirmation.state
    }
    fun acceptFirst() { confirmation.acceptFirst(); dialog.value = confirmation.state }
    fun review(breakfast: String, lunch: String, dinner: String, other: String) {
        try { confirmation.review(Amounts.parse(breakfast, lunch, dinner, other)); dialog.value = confirmation.state }
        catch (e: UserOperationException) { LogHelper.error("AllRecordsViewModel", "Operation failed; recovery preserved", e); message.value = errorMessage(e) }
    }
    fun confirmModify() {
        if (busy.value) return
        val permit = confirmation.confirmModify(); dialog.value = confirmation.state
        operate { repository.modify(permit) }
    }
    fun requestDelete(item: ConsumptionItem) {
        if (busy.value || state.value.error || confirmation.state != ConfirmationState.Idle) return
        message.value = null; confirmation.requestDelete(item); dialog.value = confirmation.state
    }
    fun confirmDelete() {
        if (busy.value) return
        val permit = confirmation.confirmDelete(); dialog.value = confirmation.state
        operate { repository.delete(permit) }
    }
    fun cancel() { confirmation.cancel(); dialog.value = confirmation.state; message.value = null }
}
