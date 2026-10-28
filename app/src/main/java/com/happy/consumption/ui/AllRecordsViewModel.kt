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
data class BatchDeleteState(val month: java.time.YearMonth, val records: List<ConsumptionItem> = emptyList(),
    val selected: Map<String, ConsumptionItem> = emptyMap(), val loading: Boolean = true,
    val error: Boolean = false, val reviewing: Boolean = false)

data class DateJump(val id: Long, val date: String)

data class ModeReveal(val id: Long = 0, val subtract: Boolean = false, val x: Float = 0f, val y: Float = 0f, val fromSubtract: Boolean = false)

data class RecordsState(val records: List<ConsumptionItem> = emptyList(), val count: Int = 0,
    val loading: Boolean = true, val error: Boolean = false)

class AllRecordsViewModel(private val repository: RecordsRepository, private val workScope: CoroutineScope? = null, initialPreferences: EntryPreferences = EntryPreferences()) : ViewModel() {
    private val scope get() = workScope ?: viewModelScope
    val state = MutableStateFlow(RecordsState())
    val entryPreferences = MutableStateFlow(initialPreferences)
    val operation = MutableStateFlow(if (initialPreferences.mode == EntryMode.ADJUST) DraftOperation.ADD else DraftOperation.REPLACE)
    val modeReveal = MutableStateFlow(ModeReveal())
    val focusedAmount = MutableStateFlow<Pair<Long, Meal>?>(null)
    fun configureEntry(value: EntryPreferences) {
        if (busy.value || inline.value?.changed == true) return
        val changedMode = entryPreferences.value.mode != value.mode
        entryPreferences.value = value
        if (changedMode) {
            val wasSubtract = operation.value == DraftOperation.SUBTRACT
            operation.value = if (value.mode == EntryMode.ADJUST) DraftOperation.ADD else DraftOperation.REPLACE
            inline.value = null
            modeReveal.value = ModeReveal(modeReveal.value.id + 1, false, fromSubtract = wasSubtract)
        }
    }
    fun doubleTapAmount(item: ConsumptionItem, meal: Meal, x: Float, y: Float) {
        if (entryPreferences.value.mode != EntryMode.ADJUST) { editAmount(item, meal); return }
        leave {
            operation.value = if (operation.value == DraftOperation.SUBTRACT) DraftOperation.ADD else DraftOperation.SUBTRACT
            modeReveal.value = ModeReveal(modeReveal.value.id + 1, operation.value == DraftOperation.SUBTRACT, x, y, operation.value != DraftOperation.SUBTRACT)
            editAmount(selectedRecord.value?.takeIf { it.id == item.id } ?: item, meal)
        }
    }
    val batchDelete = MutableStateFlow<BatchDeleteState?>(null)
    private var batchRequest = 0
    fun openBatchDelete() = leave {
        if (!state.value.error && dialog.value == ConfirmationState.Idle) {
            batchDelete.value = BatchDeleteState(java.time.YearMonth.parse(selectedDate.value.take(7)))
            loadDeleteMonth(batchDelete.value!!.month)
        }
    }
    fun loadDeleteMonth(month: java.time.YearMonth) {
        val current = batchDelete.value ?: return
        if (busy.value || month > java.time.YearMonth.from(dates.today())) return
        val request = ++batchRequest
        batchDelete.value = current.copy(month = month, records = emptyList(), loading = true, error = false)
        scope.launch {
            try {
                val rows = repository.range(month.atDay(1).toString(), month.atEndOfMonth().toString())
                    .filter { it.date < dates.today().toString() }
                if (request == batchRequest) batchDelete.value = batchDelete.value?.copy(records = rows, loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                LogHelper.error("BatchDelete", "Date list failed", e)
                if (request == batchRequest) batchDelete.value = batchDelete.value?.copy(loading = false, error = true)
            }
        }
    }
    fun toggleDeleteDate(item: ConsumptionItem) {
        val current = batchDelete.value ?: return
        if (busy.value || current.loading || current.reviewing || item !in current.records) return
        val selected = current.selected.toMutableMap()
        if (selected.remove(item.date) == null) selected[item.date] = item
        batchDelete.value = current.copy(selected = selected)
    }
    fun toggleDeleteMonth() {
        val current = batchDelete.value ?: return
        if (busy.value || current.loading || current.reviewing) return
        val selected = current.selected.toMutableMap()
        if (current.records.all { it.date in selected }) current.records.forEach { selected.remove(it.date) }
        else current.records.forEach { selected[it.date] = it }
        batchDelete.value = current.copy(selected = selected)
    }
    fun reviewBatchDelete() {
        val current = batchDelete.value ?: return
        if (!busy.value && !current.loading && !current.error && current.selected.isNotEmpty()) {
            message.value = null; batchDelete.value = current.copy(reviewing = true)
        }
    }
    fun editBatchSelection() {
        if (!busy.value) { batchDelete.value = batchDelete.value?.copy(reviewing = false); message.value = null }
    }
    fun closeBatchDelete() {
        if (!busy.value) { ++batchRequest; batchDelete.value = null; message.value = null }
    }
    fun confirmBatchDelete() {
        val current = batchDelete.value ?: return
        if (busy.value || !current.reviewing || current.selected.isEmpty()) return
        busy.value = true; message.value = null
        scope.launch {
            try {
                repository.deleteBatch(BatchDeletionPermit(repository.session, current.selected.values.toList()))
                inline.value = null
                if (selectedDate.value in current.selected) selectedDate.value = dates.today().toString()
                ++batchRequest; batchDelete.value = null
                load(); message.value = com.happy.consumption.R.string.batch_deleted
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                LogHelper.error("BatchDelete", "Deletion failed; selection preserved", e)
                message.value = errorMessage(e)
            } finally { busy.value = false }
        }
    }
    /** Selecting a missing past day inserts zeroes once and opens that date, with no generation prompt. */
    fun openCalendarDate(date: String) = leave {
        if (!busy.value && !jumping.value && !state.value.error && batchDelete.value == null) {
            busy.value = true; jumping.value = true; message.value = null
            scope.launch {
                try {
                    val parsed = dates.parseManual(date)
                    if (repository.range(date, date).isEmpty()) {
                        if (parsed == dates.today()) repository.ensureToday() else repository.ensurePastDate(date)
                    }
                    val row = repository.range(date, date).firstOrNull()
                        ?: throw UserOperationException(UserError.RECORD_MISSING)
                    selectedDate.value = date; selectedRecord.value = row; dateJump.value = DateJump(row.id, date)
                    load()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { LogHelper.error("Calendar", "Open or create failed", e); message.value = errorMessage(e) }
                finally { busy.value = false; jumping.value = false }
            }
        }
    }
    val selectedRecord = MutableStateFlow<ConsumptionItem?>(null)
    val monthRecords = MutableStateFlow<List<ConsumptionItem>>(emptyList())
    val monthLoading = MutableStateFlow(false)
    val monthError = MutableStateFlow(false)
    private var monthRequest = 0
    private var shownMonth: java.time.YearMonth? = null
    fun loadMonth(month: java.time.YearMonth) {
        shownMonth = month
        val request = ++monthRequest
        monthLoading.value = true; monthError.value = false
        scope.launch {
            try {
                val rows = repository.range(month.atDay(1).toString(), month.atEndOfMonth().toString())
                if (request == monthRequest) monthRecords.value = rows
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (request == monthRequest) monthError.value = true }
            finally { if (request == monthRequest) monthLoading.value = false }
        }
    }
    private suspend fun loadSelected() {
        val row = repository.range(selectedDate.value, selectedDate.value).firstOrNull()
        selectedRecord.value = row
        val draft = inline.value
        if (row != null && draft != null && !draft.changed && draft.item.id == row.id && draft.item != row)
            inline.value = draft.copy(item = row, inputs = List(4) { "" }, currencies = row.currencies)
    }
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
            loadSelected()
            shownMonth?.let(::loadMonth)
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
                    dates.parseManual(date)
                    val row = repository.range(date, date).firstOrNull()
                    if (row != null) {
                        selectedDate.value = date; selectedRecord.value = row
                        dateJump.value = DateJump(row.id, date)
                    } else refreshCalendar()

                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { LogHelper.error("Calendar", "Date jump failed", e); message.value = errorMessage(e); state.value = state.value.copy(error = true) }
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
        if (inline.value?.changed != true) { inline.value = null; focusedAmount.value = null; selectedDate.value = dates.today().toString() }
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
    fun editAmount(item: ConsumptionItem, meal: Meal, clearMessage: Boolean = true) {
        if (busy.value || state.value.error || dialog.value != ConfirmationState.Idle || batchDelete.value != null) return
        val current = inline.value
        if (current != null && current.item.id != item.id) {
            leave { editAmount(item, meal, clearMessage) }; return
        }
        if (clearMessage) message.value = null
        focusedAmount.value = item.id to meal
        inline.value = current?.copy(selected = meal) ?: InlineDraft(item, meal, operation = operation.value)
    }
    fun pressKey(key: String) { if (!busy.value && !leavePrompt.value) { inline.value = inline.value?.press(key); message.value = null } }
    fun changeEntryCurrency(unit: MoneyUnit) {
        if (busy.value || leavePrompt.value || operation.value != DraftOperation.REPLACE) return
        inline.value = inline.value?.let { it.copy(currencies = it.currencies.replacing(it.selected, unit)) }
    }
    fun leave(action: () -> Unit = {}) {
        if (busy.value || leavePrompt.value) return
        if (inline.value?.changed == true) {
            afterLeaving = action
            if (entryPreferences.value.confirmation == ConfirmationPlan.QUICK) saveInline()
            else leavePrompt.value = true
        }
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
                val permit = ModificationPermit(repository.session, draft.item, amounts, draft.currencies, checkSnapshot = draft.operation != DraftOperation.REPLACE)
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
