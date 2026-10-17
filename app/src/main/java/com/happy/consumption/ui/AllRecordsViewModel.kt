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

data class CalendarState(val start: String, val end: String, val available: Set<String> = emptySet(),
    val loading: Boolean = true, val error: Boolean = false)
data class DateJump(val id: Long, val date: String)

data class RecordsState(val records: List<ConsumptionItem> = emptyList(), val count: Int = 0,
    val loading: Boolean = true, val error: Boolean = false)

class AllRecordsViewModel(private val repository: RecordsRepository, private val workScope: CoroutineScope? = null) : ViewModel() {
    private val scope get() = workScope ?: viewModelScope
    val state = MutableStateFlow(RecordsState())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Int?>(null)
    val addedVersion = MutableStateFlow(0)
    val generatedCount = MutableStateFlow<Int?>(null)
    val dialog = MutableStateFlow<ConfirmationState>(ConfirmationState.Idle)
    val inline = MutableStateFlow<InlineDraft?>(null)
    val leavePrompt = MutableStateFlow(false)
    private var afterLeaving: (() -> Unit)? = null
    val dates get() = repository.dates
    val calendar = MutableStateFlow(CalendarState(repository.dates.today().minusDays(6).toString(), repository.dates.today().toString()))
    val selectedDate = MutableStateFlow(repository.dates.today().toString())
    val dateJump = MutableStateFlow<DateJump?>(null)
    val jumping = MutableStateFlow(false)
    private var calendarRequest = 0
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
            refreshCalendar()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { LogHelper.error("AllRecordsViewModel", "Operation failed; recovery preserved", e); state.value = state.value.copy(loading = false, error = true) }
    }
    fun showCalendar(start: String, end: String) {
        if (calendar.value.start == start && calendar.value.end == end && !calendar.value.error) return
        calendar.value = CalendarState(start, end)
        refreshCalendar()
    }
    fun refreshCalendar() {
        val range = calendar.value
        val request = ++calendarRequest
        calendar.value = range.copy(loading = true, error = false)
        scope.launch {
            try {
                val available = repository.recordDates(range.start, range.end)
                if (request == calendarRequest) calendar.value = range.copy(available = available, loading = false, error = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                LogHelper.error("Calendar", "Date availability failed", e)
                if (request == calendarRequest) calendar.value = range.copy(loading = false, error = true)
            }
        }
    }
    fun jumpToDate(date: String) = leave {
        if (!jumping.value && !state.value.error) {
            jumping.value = true
            scope.launch {
                try {
                    val position = repository.positionOf(date)
                    if (position != null) {
                        pages = maxOf(pages, position / 200 + 1)
                        load()
                        if (!state.value.error) state.value.records.firstOrNull { it.date == date }?.let {
                            selectedDate.value = date; dateJump.value = DateJump(it.id, date)
                        }
                    } else refreshCalendar()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { LogHelper.error("Calendar", "Date jump failed", e); message.value = errorMessage(e) }
                finally { jumping.value = false }
            }
        }
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
        busy.value = true; message.value = null; generatedCount.value = null
        scope.launch {
            try { action(); load() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogHelper.error("AllRecordsViewModel", "Operation failed; recovery preserved", e);
                message.value = errorMessage(e)
                if (e !is UserOperationException || e.reason == UserError.STORAGE) state.value = state.value.copy(error = true)
            } finally { busy.value = false }
        }
    }
    fun add(date: String, breakfast: String, lunch: String, dinner: String, other: String, currencies: EntryCurrencies? = null) = operate {
        repository.add(date, Amounts.adding(breakfast, lunch, dinner, other), currencies); addedVersion.value++
    }
    fun editAmount(item: ConsumptionItem, meal: Meal) {
        if (busy.value || state.value.error || dialog.value != ConfirmationState.Idle) return
        val current = inline.value
        if (current != null && current.item.id != item.id) {
            leave { editAmount(item, meal) }; return
        }
        message.value = null
        inline.value = current?.copy(selected = meal) ?: InlineDraft(item, meal)
    }
    fun pressKey(key: String) { if (!busy.value && !leavePrompt.value) { inline.value = inline.value?.press(key); message.value = null } }
    fun changeEntryCurrency(unit: MoneyUnit) {
        if (busy.value || leavePrompt.value) return
        inline.value = inline.value?.let { it.copy(currencies = it.currencies.replacing(it.selected, unit)) }
    }
    fun leave(action: () -> Unit = {}) {
        if (busy.value || leavePrompt.value) return
        if (inline.value?.changed == true) { afterLeaving = action; leavePrompt.value = true }
        else { inline.value = null; action() }
    }
    fun keepEditing() { leavePrompt.value = false; afterLeaving = null }
    fun discardInline() {
        if (busy.value) return
        val action = afterLeaving; inline.value = null; afterLeaving = null; leavePrompt.value = false; message.value = null
        action?.invoke()
    }
    fun saveInline() {
        val draft = inline.value ?: return
        if (busy.value || state.value.error) return
        val amounts = try { draft.amounts() }
            catch (e: UserOperationException) { LogHelper.error("InlineEdit", "Invalid draft rejected", e); message.value = errorMessage(e); leavePrompt.value = false; afterLeaving = null; return }
        if (!draft.changed) { discardInline(); return }
        busy.value = true; message.value = null
        scope.launch {
            try {
                val permit = ModificationPermit(repository.session, draft.item, amounts, draft.currencies)
                repository.modify(permit)
                val action = afterLeaving
                inline.value = null; afterLeaving = null; leavePrompt.value = false
                load(); busy.value = false; action?.invoke()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                LogHelper.error("InlineEdit", "Save failed; draft preserved", e)
                message.value = errorMessage(e); leavePrompt.value = false; afterLeaving = null
            } finally { busy.value = false }
        }
    }
    fun generatePastThirtyDays() = operate { generatedCount.value = repository.generatePastThirtyDays() }
    fun requestModify(item: ConsumptionItem) {
        if (busy.value || state.value.error || confirmation.state != ConfirmationState.Idle) return
        message.value = null; confirmation.requestModify(item); dialog.value = confirmation.state
    }
    fun review(breakfast: String, lunch: String, dinner: String, other: String) {
        try {
            val editing = confirmation.state as? ConfirmationState.Editing ?: throw UserOperationException(UserError.UNCONFIRMED)
            confirmation.review(Amounts.editing(editing.item, breakfast, lunch, dinner, other)); dialog.value = confirmation.state
        }
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
