package com.happy.consumption.data

import java.util.Locale
import java.math.BigDecimal
import java.math.BigInteger

enum class MoneyUnit(val symbol: String) {
    YUAN("¥"), DOLLAR("$"), POUND("£");
    fun format(cents: Long) = symbol + String.format(Locale.US, "%d.%02d", cents / 100, cents % 100)
    fun format(cents: BigInteger) = symbol + BigDecimal(cents, 2).toPlainString()
    companion object {
        fun stored(code: String?) = entries.firstOrNull { it.name == code } ?: YUAN
    }
}
