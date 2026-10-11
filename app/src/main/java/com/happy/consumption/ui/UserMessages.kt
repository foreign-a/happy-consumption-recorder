package com.happy.consumption.ui

import com.happy.consumption.R
import com.happy.consumption.data.*

fun errorMessage(error: Exception): Int = when (error) {
    is AccessDeniedException -> R.string.session_expired
    is UserOperationException -> when (error.reason) {
        UserError.EMPTY_NAME -> R.string.empty_name
        UserError.SHORT_PASSWORD -> R.string.short_password
        UserError.DUPLICATE_NAME -> R.string.duplicate_name
        UserError.INVALID_LOGIN -> R.string.invalid_login
        UserError.INVALID_PASSWORD -> R.string.invalid_password
        UserError.NO_SESSION -> R.string.session_expired
        UserError.DATE_EXISTS -> R.string.date_exists
        UserError.INVALID_AMOUNT -> R.string.invalid_amount
        UserError.INVALID_DATE -> R.string.invalid_date
        UserError.STORAGE -> R.string.storage_error
        UserError.RECORD_MISSING -> R.string.record_missing
        UserError.UNCONFIRMED -> R.string.confirmation_required
        UserError.BACKUP_INVALID -> R.string.backup_invalid
        UserError.BACKUP_CONFLICT -> R.string.backup_conflict
    }
    else -> R.string.storage_error
}
