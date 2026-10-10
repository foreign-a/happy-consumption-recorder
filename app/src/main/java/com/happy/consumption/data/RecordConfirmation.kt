package com.happy.consumption.data

data class Amounts(val breakfast: Long, val lunch: Long, val dinner: Long, val other: Long) {
    fun validate() {
        if (listOf(breakfast, lunch, dinner, other).any { it !in 0L..999999L }) throw UserOperationException(UserError.INVALID_AMOUNT)
    }
    companion object {
        fun parse(breakfast: String?, lunch: String?, dinner: String?, other: String?) =
            Amounts(AmountInput.cents(breakfast), AmountInput.cents(lunch), AmountInput.cents(dinner), AmountInput.cents(other))
    }
}

sealed interface ConfirmationState {
    data object Idle : ConfirmationState
    data class FirstModify(val item: ConsumptionItem) : ConfirmationState
    data class Editing(val item: ConsumptionItem) : ConfirmationState
    data class FinalModify(val item: ConsumptionItem, val amounts: Amounts) : ConfirmationState
    data class FirstDelete(val item: ConsumptionItem) : ConfirmationState
}

class ModificationPermit internal constructor(val session: UserSession, val item: ConsumptionItem, val amounts: Amounts) {
    private var consumed = false
    internal fun consume() {
        if (consumed) throw UserOperationException(UserError.UNCONFIRMED)
        consumed = true
    }
}
class DeletionPermit internal constructor(val session: UserSession, val item: ConsumptionItem) {
    private var consumed = false
    internal fun consume() {
        if (consumed) throw UserOperationException(UserError.UNCONFIRMED)
        consumed = true
    }
}

// UI 使用同一纯状态机。只有走完确认步骤才生成一次性写入许可。
class RecordConfirmation(private val session: UserSession) {
    var state: ConfirmationState = ConfirmationState.Idle
        private set
    fun requestModify(item: ConsumptionItem) { check(state == ConfirmationState.Idle); state = ConfirmationState.FirstModify(item) }
    fun acceptFirst() { val first = state as? ConfirmationState.FirstModify ?: throw UserOperationException(UserError.UNCONFIRMED); state = ConfirmationState.Editing(first.item) }
    fun review(amounts: Amounts) {
        val editing = state as? ConfirmationState.Editing ?: throw UserOperationException(UserError.UNCONFIRMED)
        amounts.validate(); state = ConfirmationState.FinalModify(editing.item, amounts)
    }
    fun confirmModify(): ModificationPermit {
        val final = state as? ConfirmationState.FinalModify ?: throw UserOperationException(UserError.UNCONFIRMED)
        state = ConfirmationState.Idle; return ModificationPermit(session, final.item, final.amounts)
    }
    fun requestDelete(item: ConsumptionItem) { check(state == ConfirmationState.Idle); state = ConfirmationState.FirstDelete(item) }
    fun confirmDelete(): DeletionPermit {
        val first = state as? ConfirmationState.FirstDelete ?: throw UserOperationException(UserError.UNCONFIRMED)
        state = ConfirmationState.Idle; return DeletionPermit(session, first.item)
    }
    fun cancel() { state = ConfirmationState.Idle }
}
