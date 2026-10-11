package com.happy.consumption.data

import java.util.Locale

enum class MoneyUnit(val symbol: String) {
    YUAN("¥"), DOLLAR("$"), POUND("£");
    fun format(cents: Long) = symbol + String.format(Locale.US, "%d.%02d", cents / 100, cents % 100)
    companion object {
        fun stored(code: String?) = entries.firstOrNull { it.name == code } ?: YUAN
    }
}
