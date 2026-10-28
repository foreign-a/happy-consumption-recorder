package com.happy.consumption.data

enum class Meal { BREAKFAST, LUNCH, DINNER, OTHER }

data class EntryCurrencies(val breakfast: MoneyUnit, val lunch: MoneyUnit, val dinner: MoneyUnit, val other: MoneyUnit) {
    operator fun get(meal: Meal) = listOf(breakfast, lunch, dinner, other)[meal.ordinal]
    fun replacing(meal: Meal, unit: MoneyUnit) = when (meal) {
        Meal.BREAKFAST -> copy(breakfast = unit)
        Meal.LUNCH -> copy(lunch = unit)
        Meal.DINNER -> copy(dinner = unit)
        Meal.OTHER -> copy(other = unit)
    }
    companion object { fun uniform(unit: MoneyUnit) = EntryCurrencies(unit, unit, unit, unit) }
}

fun ConsumptionItem.value(meal: Meal) = listOf(breakfast, lunch, dinner, other)[meal.ordinal]
val ConsumptionItem.currencies get() = EntryCurrencies(breakfastCurrency, lunchCurrency, dinnerCurrency, otherCurrency)
fun ConsumptionItem.totals(): String = Meal.entries.groupBy { currencies[it] }.entries
    .joinToString(" + ") { (unit, meals) -> unit.format(meals.sumOf { value(it) }) }

data class InlineDraft(val item: ConsumptionItem, val selected: Meal,
    val inputs: List<String> = List(4) { "" }, val currencies: EntryCurrencies = item.currencies, val operation: DraftOperation = DraftOperation.REPLACE) {
    val changed: Boolean get() = currencies != item.currencies || Meal.entries.any { meal ->
        inputs[meal.ordinal].isNotBlank() && AmountInput.previewCents(inputs[meal.ordinal]) !=
            (if (operation == DraftOperation.REPLACE) item.value(meal) else 0L)
    }
    fun press(key: String): InlineDraft {
        val previous = inputs[selected.ordinal]
        val next = when (key) {
            "backspace" -> previous.dropLast(1)
            "." -> if ('.' in previous) previous else if (previous.isEmpty()) "0." else "$previous."
            else -> { require(key.length == 1 && key[0] in '0'..'9'); (previous + key).take(24) }
        }
        return copy(inputs = inputs.toMutableList().apply { this[selected.ordinal] = next })
    }
    fun amounts(): Amounts {
        val values = Meal.entries.map { meal ->
            val input = inputs[meal.ordinal]
            if (input.isBlank()) item.value(meal) else {
                val entered = AmountInput.cents(input)
                when (operation) {
                    DraftOperation.REPLACE -> entered
                    DraftOperation.ADD -> item.value(meal) + entered
                    DraftOperation.SUBTRACT -> item.value(meal) - entered
                }
            }
        }
        if (operation != DraftOperation.REPLACE && values.any { it !in 0L..999999L })
            throw UserOperationException(UserError.INVALID_ADJUSTMENT)
        return Amounts(values[0], values[1], values[2], values[3]).also { it.validate() }
    }
    fun preview(meal: Meal) = currencies[meal].let { unit ->
        if (inputs[meal.ordinal].isBlank()) unit.format(item.value(meal)) else
            (when (operation) { DraftOperation.ADD -> "+"; DraftOperation.SUBTRACT -> "−"; else -> "" }) + unit.symbol + inputs[meal.ordinal]
    }
}
