package com.happy.consumption.data

enum class EntryMode { REPLACE, ADJUST }
enum class ConfirmationPlan { QUICK, CAREFUL }
enum class DraftOperation { REPLACE, ADD, SUBTRACT }
data class EntryPreferences(val mode: EntryMode = EntryMode.REPLACE, val confirmation: ConfirmationPlan = ConfirmationPlan.CAREFUL)
