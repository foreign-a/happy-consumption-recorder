package com.happy.consumption.data

import com.happy.consumption.util.LogHelper

object AmountInput {
    fun cents(text: String?): Long {
        return previewCents(text) ?: run {
            LogHelper.info("Validation", "Invalid amount rejected")
            throw UserOperationException(UserError.INVALID_AMOUNT)
        }
    }
    // 草稿重绘需要纯解析，避免未确认的输入在每帧产生验证日志。
    internal fun previewCents(text: String?): Long? {
        if (text == null || !text.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) return null
        val value = try { java.math.BigDecimal(text).movePointRight(2).longValueExact() }
            catch (_: ArithmeticException) { return null }
        return value.takeIf { it in 0L..999999L }
    }
}
