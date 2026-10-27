package com.happy.consumption.data

/** A confirmed snapshot of dates and record identities, scoped to one session. */
class BatchDeletionPermit internal constructor(val session: UserSession, records: List<ConsumptionItem>) {
    val items = records.toList()
    private var consumed = false
    internal fun validate(dates: DatePolicy) {
        if (items.isEmpty() || items.map { it.id }.toSet().size != items.size ||
            items.map { it.date }.toSet().size != items.size) throw UserOperationException(UserError.UNCONFIRMED)
        if (items.any { it.userId != session.id }) throw AccessDeniedException()
        if (items.any { dates.parseManual(it.date) >= dates.today() }) throw UserOperationException(UserError.INVALID_DATE)
    }
    internal fun consume() {
        if (consumed) throw UserOperationException(UserError.UNCONFIRMED)
        consumed = true
    }
}
