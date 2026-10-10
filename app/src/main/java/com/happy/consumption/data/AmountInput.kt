package com.happy.consumption.data

import com.happy.consumption.util.LogHelper

object AmountInput {
    fun cents(text: String?): Long {
        if (text == null || !text.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { LogHelper.info("Validation", "Invalid amount rejected"); throw UserOperationException(UserError.INVALID_AMOUNT) }
        val value = try { java.math.BigDecimal(text).movePointRight(2).longValueExact() }
            catch (e: ArithmeticException) { LogHelper.error("AmountInput", "Operation failed; recovery preserved", e); throw UserOperationException(UserError.INVALID_AMOUNT) }
        if (value !in 0L..999999L) { LogHelper.info("Validation", "Invalid amount rejected"); throw UserOperationException(UserError.INVALID_AMOUNT) }
        return value
    }
}
